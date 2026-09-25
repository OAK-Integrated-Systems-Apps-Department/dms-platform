from __future__ import annotations

import time
import uuid

import pytest

from ocr_worker.config import Settings
from ocr_worker.worker_pool import (
    OcrProcessPool,
    _run_process_rubber_band,
    _run_process_version,
    _run_process_zone,
    compute_task_timeout_s,
    compute_threads_per_process,
)


# ----------------------------------------------------------------------
# Pure derivation formulas -- no subprocess/model work, matches the Phase 1
# plan's explicit "thread-count derivation" / timeout-sizing test asks.
# ----------------------------------------------------------------------
@pytest.mark.parametrize(
    "cpu_count, worker_processes, expected",
    [
        (4, 2, 2),
        (4, 4, 1),
        (8, 2, 4),
        (1, 2, 1),  # more processes than cores -- floors to 1, never 0
        (3, 2, 1),  # uneven division floors down
    ],
)
def test_compute_threads_per_process(cpu_count, worker_processes, expected):
    assert compute_threads_per_process(cpu_count, worker_processes) == expected


def test_compute_task_timeout_s_is_the_outer_backstop_above_the_largest_in_worker_budget():
    """The pool-level ceiling must always sit ABOVE the largest deadline
    deadline.py can arm (ocr_max_pages worth) plus its hard grace -- if
    pebble fired first, the in-worker watchdog would never get to record
    TIMEOUT and every timeout would come back as WORKER_CRASH via the
    reaper, which is exactly the behavior Phase 1 close-out replaced.
    """
    settings = Settings(
        ocr_timeout_base_s=60, ocr_timeout_per_page_s=25, ocr_max_pages=50, ocr_timeout_hard_grace_s=30
    )
    largest_in_worker_hard_deadline = 60 + 50 * 25 + 30
    assert compute_task_timeout_s(settings) == 60 + 50 * 25 + 2 * 30
    assert compute_task_timeout_s(settings) > largest_in_worker_hard_deadline


# ----------------------------------------------------------------------
# Task-dispatch functions delegate to whatever pipeline the pool worker's
# own initializer built -- verified here by monkeypatching the per-process
# global directly (this test runs in the SAME process, standing in for
# "a" pool worker; the real subprocess/spawn mechanics are covered by the
# @pytest.mark.model tests below).
# ----------------------------------------------------------------------
class _FakePipeline:
    def __init__(self):
        self.calls: list[tuple] = []

    def process_version(self, version_id, correlation_id=None, requested_fields=None, resolve_fields_from_db=False):
        self.calls.append(("process_version", version_id, correlation_id, requested_fields, resolve_fields_from_db))
        return "version-result"

    def process_zone_request(self, version_id, zone_names, correlation_id=None):
        self.calls.append(("process_zone_request", version_id, zone_names, correlation_id))
        return "zone-result"

    def process_rubber_band_request(self, version_id, page, box, correlation_id=None):
        self.calls.append(("process_rubber_band_request", version_id, page, box, correlation_id))
        return "rubber-band-result"


def test_run_process_version_delegates_to_the_worker_local_pipeline(monkeypatch):
    fake = _FakePipeline()
    monkeypatch.setattr("ocr_worker.worker_pool._pipeline", fake)
    version_id = uuid.uuid4()
    correlation_id = uuid.uuid4()

    result = _run_process_version(version_id, correlation_id, None)

    assert result == "version-result"
    assert fake.calls == [("process_version", version_id, correlation_id, None, False)]


def test_run_process_version_passes_the_db_field_resolution_flag_through(monkeypatch):
    """The queue consumers -- and only they -- ask the pipeline to resolve
    requested_fields from document_versions itself (Phase 1 Decision 1);
    the dev-trigger endpoint sends explicit specs instead.
    """
    fake = _FakePipeline()
    monkeypatch.setattr("ocr_worker.worker_pool._pipeline", fake)
    version_id = uuid.uuid4()

    _run_process_version(version_id, None, None, True)

    assert fake.calls == [("process_version", version_id, None, None, True)]


def test_run_process_zone_delegates_to_the_worker_local_pipeline(monkeypatch):
    fake = _FakePipeline()
    monkeypatch.setattr("ocr_worker.worker_pool._pipeline", fake)
    version_id = uuid.uuid4()

    result = _run_process_zone(version_id, ["totals"], None)

    assert result == "zone-result"
    assert fake.calls == [("process_zone_request", version_id, ["totals"], None)]


def test_run_process_rubber_band_delegates_to_the_worker_local_pipeline(monkeypatch):
    fake = _FakePipeline()
    monkeypatch.setattr("ocr_worker.worker_pool._pipeline", fake)
    version_id = uuid.uuid4()
    box = (0.1, 0.1, 0.5, 0.5)

    result = _run_process_rubber_band(version_id, 0, box, None)

    assert result == "rubber-band-result"
    assert fake.calls == [("process_rubber_band_request", version_id, 0, box, None)]


def test_run_process_version_asserts_if_scheduled_before_init(monkeypatch):
    monkeypatch.setattr("ocr_worker.worker_pool._pipeline", None)
    with pytest.raises(AssertionError):
        _run_process_version(uuid.uuid4(), None, None)


# ----------------------------------------------------------------------
# Real subprocess-pool behavior: spawn context, per-process CPU-thread
# capping, eager model warm-up (real PaddleOCR/Docling construction --
# needs the actual installed deps this repo ships, run inside the real
# container), and readiness tracking. No DB/MinIO reachability required:
# session_factory/ObjectStorage are both lazily-connecting (see db.py/
# storage.py), so only the model-construction step does real work here.
# ----------------------------------------------------------------------
@pytest.mark.model
def test_real_pool_warms_up_all_workers_and_reports_ready():
    settings = Settings(ocr_worker_processes=1, ocr_worker_max_tasks_per_process=50)
    pool = OcrProcessPool(settings)
    try:
        assert pool.is_ready() is False
        deadline = time.monotonic() + 120
        while time.monotonic() < deadline and not pool.is_ready():
            time.sleep(1)
        assert pool.is_ready() is True, "pool worker did not report warmed-up within 120s"
    finally:
        pool.close()


def _sleep_and_return_pid(seconds: float) -> int:
    import os
    import time as _time

    _time.sleep(seconds)
    return os.getpid()


@pytest.mark.model
def test_real_pool_kills_and_replaces_the_worker_on_timeout():
    """schedule_process_version's own timeout (task_timeout_s) is a large,
    real worst-case ceiling (~1310s by default) -- too slow to exercise
    directly in a test. This instead drives the SAME underlying
    pebble.ProcessPool this class builds (identical spawn context and
    initializer chain, so the same CPU-thread-capping/warm-up already ran)
    with a short timeout on a trivial task, to verify pebble genuinely
    kills and replaces the worker process rather than just abandoning the
    task client-side -- the exact mechanic B.3's dispatching-side TIMEOUT
    handling (consumer.py/api.py) depends on.
    """
    settings = Settings(ocr_worker_processes=1, ocr_worker_max_tasks_per_process=50)
    pool = OcrProcessPool(settings)
    try:
        deadline = time.monotonic() + 120
        while time.monotonic() < deadline and not pool.is_ready():
            time.sleep(1)
        assert pool.is_ready() is True

        hung = pool._pool.schedule(_sleep_and_return_pid, args=(5,), timeout=1)
        with pytest.raises(TimeoutError):
            hung.result()

        # A fresh task must still complete -- proving the pool replaced the
        # killed worker rather than being left permanently short a slot.
        revived = pool._pool.schedule(_sleep_and_return_pid, args=(0,), timeout=30)
        assert isinstance(revived.result(), int)
    finally:
        pool.close()


# ----------------------------------------------------------------------
# Lease-vs-document-duration invariant. Found the hard way during Phase 1
# close-out (see OcrProcessPool.warn_on_misconfigured_lease): a lease
# shorter than the longest a document may legitimately take turns the
# reaper from a backstop into a competitor, and long documents livelock.
# ----------------------------------------------------------------------
def test_lease_longer_than_the_longest_document_is_not_warned_about():
    settings = Settings(
        ocr_lease_duration_s=1800, ocr_timeout_base_s=60, ocr_timeout_per_page_s=25,
        ocr_max_pages=50, ocr_timeout_hard_grace_s=30,
    )
    # The shipped defaults must satisfy this: 1800s > 60 + 50*25 + 2*30 = 1370s.
    assert compute_task_timeout_s(settings) < settings.ocr_lease_duration_s
    assert OcrProcessPool.warn_on_misconfigured_lease(_SettingsOnlyPool(settings)) is None


def test_a_lease_shorter_than_the_longest_document_is_warned_about():
    settings = Settings(
        ocr_lease_duration_s=60, ocr_timeout_base_s=60, ocr_timeout_per_page_s=25,
        ocr_max_pages=50, ocr_timeout_hard_grace_s=30,
    )
    message = OcrProcessPool.warn_on_misconfigured_lease(_SettingsOnlyPool(settings))
    assert message is not None
    assert "OCR_LEASE_DURATION_S=60s" in message


class _SettingsOnlyPool:
    """Stands in for a real OcrProcessPool: `warn_on_misconfigured_lease`
    reads nothing but `self._settings`, and constructing a real pool would
    spawn processes that load PaddleOCR/Docling models.
    """

    def __init__(self, settings: Settings):
        self._settings = settings
