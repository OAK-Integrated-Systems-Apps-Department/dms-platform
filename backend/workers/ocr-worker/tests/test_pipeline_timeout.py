"""The per-document timeout, end to end through `OcrPipeline` against a
real (in-memory) database -- not just `DocumentDeadline`'s arithmetic
(test_deadline.py) or the pool ceiling's sizing (test_worker_pool.py).

What matters here is the DB outcome, because that is what changed: a
timeout used to leave the `processing_jobs` row claimed with a live lease
and no failure record at all, so the version sat in `processing` until the
reaper noticed `ocr_lease_duration_s` (1800s) later and recorded
`WORKER_CRASH`. Now the soft check finalizes it immediately, as `TIMEOUT`,
through the ordinary failure path -- with the pool worker still alive.
"""
from __future__ import annotations

import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "src"))

from ocr_worker.config import OCR_TIMEOUT_EXIT_CODE, Settings  # noqa: E402
from ocr_worker.errors import OcrTimeoutError  # noqa: E402
from ocr_worker.layout import NativeTextBlock  # noqa: E402
from ocr_worker.models import DocumentVersion, OutboxEvent, ProcessingJob  # noqa: E402
from ocr_worker.pipeline import OcrPipeline  # noqa: E402
from test_pipeline_transaction import (  # noqa: E402
    FakeLayoutDocument,
    FakeLayoutEngine,
    FakeOcrEngine,
    FakeStorage,
)


class _SlowLayoutDocument(FakeLayoutDocument):
    """Burns real wall-clock per page, so the between-pages soft check has
    something to actually catch -- the point being that the budget is
    exceeded mid-document, not before it starts.
    """

    def __init__(self, *, page_count: int, seconds_per_page: float, **kwargs):
        super().__init__(page_count=page_count, **kwargs)
        self._seconds_per_page = seconds_per_page

    def native_text_blocks_for_page(self, page_no: int, dpi: int):
        import time

        time.sleep(self._seconds_per_page)
        return [NativeTextBlock(text=f"page {page_no}", bbox=(0, 0, 100, 20))]


def _pipeline(session_factory, layout_doc, **setting_overrides):
    settings = Settings(
        min_confidence_threshold=0.95, ocr_languages="en", ocr_render_dpi=200, **setting_overrides
    )
    return OcrPipeline(
        settings=settings,
        session_factory=session_factory,
        storage=FakeStorage(),
        layout_engine=FakeLayoutEngine(layout_doc),
        ocr_engine=FakeOcrEngine(text="", confidence=0.99),
    )


def test_a_document_that_blows_its_page_scaled_budget_is_recorded_as_timeout(session_factory, seeded_version):
    # 4 pages x 0.05s of work against a 0 + 4*0.01 = 0.04s budget.
    pipeline = _pipeline(
        session_factory,
        _SlowLayoutDocument(page_count=4, seconds_per_page=0.05),
        ocr_timeout_base_s=0,
        ocr_timeout_per_page_s=0.01,
        ocr_timeout_hard_grace_s=600,  # keep the hard watchdog well out of the way
    )

    outcome = pipeline.process_version(seeded_version["version_id"])

    assert outcome.status == "failed"
    assert outcome.error.startswith("TIMEOUT: ")

    with session_factory() as session:
        version = session.get(DocumentVersion, seeded_version["version_id"])
        job = session.query(ProcessingJob).one()
        events = session.query(OutboxEvent).all()

    # The whole point: a terminal state, written by the worker itself, at
    # the moment the budget was blown -- not a row left 'processing' with a
    # live lease for the reaper to find half an hour later.
    assert version.ocr_status == "failed"
    assert job.status == "failed"
    assert job.last_error.startswith("TIMEOUT: ")
    assert job.finished_at is not None
    assert [e.event_type for e in events] == ["document.version.ocr.failed"]


def test_the_budget_comes_from_the_real_page_count_not_ocr_max_pages(session_factory, seeded_version):
    """The regression this fix exists for. With `ocr_max_pages=50` the old
    ceiling would have been 0 + 50*0.01 = 0.5s, comfortably enough for this
    1-page document's 0.2s of work; sized from its REAL page count it gets
    0.01s and times out.
    """
    pipeline = _pipeline(
        session_factory,
        _SlowLayoutDocument(page_count=1, seconds_per_page=0.2),
        ocr_timeout_base_s=0,
        ocr_timeout_per_page_s=0.01,
        ocr_max_pages=50,
        ocr_timeout_hard_grace_s=600,
    )

    # One page means only one soft check (before page 1, when nothing has
    # elapsed), so the page itself completes -- but the deadline is armed
    # at 0.01s, which is what the hard watchdog would have fired on. Assert
    # the armed budget directly rather than the outcome.
    armed: list[float] = []
    original = pipeline._make_deadline

    def capture(**kwargs):
        deadline = original(**kwargs)
        original_arm = deadline.arm
        deadline.arm = lambda page_count: armed.append(original_arm(page_count)) or armed[-1]
        return deadline

    pipeline._make_deadline = capture
    pipeline.process_version(seeded_version["version_id"])

    assert armed == [0.01]  # 1 page x 0.01s, NOT 50 x 0.01s


def test_the_hard_watchdog_records_timeout_before_killing_its_worker(session_factory, seeded_version, monkeypatch):
    """The case the soft check cannot cover: one native call wedges and
    never returns to a between-pages check. The watchdog must write the
    failure record FIRST and only then exit -- writing it is the whole
    reason this is not just `os._exit` on a timer.

    `os._exit` is monkeypatched to just record its code -- it would
    otherwise take the whole pytest process with it. Because it therefore
    doesn't actually end the process, the main thread survives to discover
    (via `settle()` returning False) that the watchdog already owns this
    job, and stands down by raising rather than writing a second,
    contradictory outcome. In production that raise never happens, since
    the real `_exit` lands first.
    """
    import os

    exit_codes: list[int] = []
    monkeypatch.setattr(os, "_exit", exit_codes.append)

    # 1 page that takes far longer than its 0.05s budget + 0.05s grace,
    # with no second page to give the soft check a turn.
    pipeline = _pipeline(
        session_factory,
        _SlowLayoutDocument(page_count=1, seconds_per_page=1.5),
        ocr_timeout_base_s=0.05,
        ocr_timeout_per_page_s=0,
        ocr_timeout_hard_grace_s=0.05,
    )

    with pytest.raises(OcrTimeoutError):
        pipeline.process_version(seeded_version["version_id"])

    assert exit_codes == [OCR_TIMEOUT_EXIT_CODE]
    with session_factory() as session:
        version = session.get(DocumentVersion, seeded_version["version_id"])
        job = session.query(ProcessingJob).one()
        events = session.query(OutboxEvent).all()
    assert version.ocr_status == "failed"
    assert job.status == "failed"
    assert job.last_error.startswith("TIMEOUT: ")
    assert [e.event_type for e in events] == ["document.version.ocr.failed"]


def test_a_document_inside_its_budget_is_untouched(session_factory, seeded_version):
    pipeline = _pipeline(
        session_factory,
        _SlowLayoutDocument(page_count=3, seconds_per_page=0.01),
        ocr_timeout_base_s=30,
        ocr_timeout_per_page_s=30,
    )

    outcome = pipeline.process_version(seeded_version["version_id"])

    assert outcome.status == "completed"
    with session_factory() as session:
        assert session.get(DocumentVersion, seeded_version["version_id"]).ocr_status == "completed"
