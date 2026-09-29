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
    longest_in_worker_hard_deadline_s,
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
        ocr_timeout_base_s=60, ocr_timeout_per_page_s=45, ocr_max_pages=50, ocr_timeout_hard_grace_s=30
    )
    largest_in_worker_hard_deadline = 60 + 50 * 45 + 30
    assert compute_task_timeout_s(settings) == 60 + 50 * 45 + 2 * 30
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
# The parent ceiling is the only bound that survives a starved watchdog.
#
# Observed live on 2026-09-29: a 35-page scan ran past its 1665s in-worker
# hard deadline and was stopped only by the parent's pebble ceiling at
# 2370s -- `TIMEOUT: hit the outer pool ceiling without the in-worker
# deadline firing`. The in-worker watchdog is a Python thread, and a
# native call holding the GIL starves it.
#
# `_sleep_and_return_pid` above cannot reproduce that: `time.sleep`
# RELEASES the GIL, so a watchdog thread would run perfectly well
# alongside it. These use a single long C call that does not.
# ----------------------------------------------------------------------
def _watchdog_starved_by_a_gil_holding_call(digits: int) -> tuple[float, float | None]:
    """Arm an in-process watchdog, then block the GIL so it cannot fire.

    Returns `(seconds the call took, when the watchdog actually ran)`,
    both relative to the same start. The watchdog is armed at 0.2s, so on
    a healthy interpreter it runs at ~0.2s; starved, it cannot run until
    the blocking call returns and its timestamp lands at the END instead.

    Comparing timestamps rather than reading a flag afterwards is
    deliberate: the timer fires the instant the GIL becomes available,
    which is immediately after the blocking call returns, so a flag read
    after the call reports True in both the starved and healthy cases and
    tests nothing.

    `math.factorial` is one C call that never releases the GIL and offers
    no bytecode boundary for the interpreter to switch threads at.
    """
    import math
    import threading
    import time as _time

    started = _time.monotonic()
    fired_at: list[float] = []
    threading.Timer(0.2, lambda: fired_at.append(_time.monotonic() - started)).start()
    math.factorial(digits)
    elapsed = _time.monotonic() - started
    _time.sleep(0.05)  # let the starved timer run now that the GIL is free
    return elapsed, (fired_at[0] if fired_at else None)


def test_a_gil_holding_call_really_does_starve_an_in_process_watchdog():
    """Guards the premise of the test below. If a future Python made this
    interruptible, the next test would still pass while no longer testing
    anything, so the starvation itself is asserted separately."""
    elapsed, fired_at = _watchdog_starved_by_a_gil_holding_call(600_000)

    assert elapsed > 1.0, "the blocking call must be long enough to starve a 0.2s timer"
    assert fired_at is not None, "the timer thread never ran at all"
    # Armed at 0.2s, but it could not run until the call released the GIL.
    assert fired_at >= elapsed * 0.9, (
        f"watchdog ran at {fired_at:.2f}s into a {elapsed:.2f}s call -- it was NOT starved, "
        "so this no longer reproduces the failure the parent ceiling exists for"
    )


def test_the_parent_ceiling_kills_a_task_whose_watchdog_cannot_fire():
    """The parent's pebble ceiling must stop a task that no in-process
    mechanism can. Uses a bare `pebble.ProcessPool` rather than
    `OcrProcessPool` deliberately -- what is under test is the ceiling
    mechanic, not model warm-up, and this way it runs in the default
    suite instead of behind `-m model`.
    """
    import pebble

    pool = pebble.ProcessPool(max_workers=1)
    try:
        started = time.monotonic()
        hung = pool.schedule(_watchdog_starved_by_a_gil_holding_call, args=(600_000,), timeout=1)
        with pytest.raises(TimeoutError):
            hung.result()
        elapsed = time.monotonic() - started

        # Killed at the ceiling, not after the call finished on its own.
        assert elapsed < 20

        # And the pool is still usable: pebble replaced the worker it had
        # to SIGKILL, which is what makes the ceiling a recovery mechanism
        # rather than a way to lose a pool slot permanently.
        revived = pool.schedule(_sleep_and_return_pid, args=(0,), timeout=30)
        assert isinstance(revived.result(), int)
    finally:
        pool.stop()
        pool.join()


def test_the_ceiling_sits_one_pool_grace_above_the_largest_in_worker_deadline():
    settings = Settings(
        ocr_timeout_base_s=60, ocr_timeout_per_page_s=45, ocr_max_pages=50,
        ocr_timeout_hard_grace_s=30, ocr_pool_ceiling_grace_s=30,
    )
    assert longest_in_worker_hard_deadline_s(settings) == 60 + 50 * 45 + 30
    assert compute_task_timeout_s(settings) == longest_in_worker_hard_deadline_s(settings) + 30
    # The ceiling must never be reachable before the watchdog has had its
    # own full grace, or the parent would pre-empt the in-worker path that
    # writes a proper TIMEOUT record from inside.
    assert compute_task_timeout_s(settings) > longest_in_worker_hard_deadline_s(settings)


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
