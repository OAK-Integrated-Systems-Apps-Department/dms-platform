"""Phase 3: the parent records WHY a pool worker died.

This is the path that leaked, and it leaked silently.

When a pool worker is OOM-killed, or the outer pebble ceiling fires, the
worker never gets to write anything. The `processing_jobs` row was left
`processing` with a live lease and **no `last_error` at all** -- so when
the reaper later reclaimed it, there was no cause to reason about and it
retried the job as an ordinary transient crash, up to five times. For a
document that is simply too large or too slow that retry can never
succeed: the 50-upload run at `OCR_RENDER_MAX_MP=4.0` spent roughly 40 of
its 60 minutes on one 35-page scan being retried five times.

The fix is small -- the parent writes the cause before leaving the lease
-- but it is exactly the kind of bookkeeping that is easy to drop in a
refactor and impossible to notice from the outside, because the symptom
is a queue that is slower than it should be rather than anything failing.
Hence a test per branch.
"""

from __future__ import annotations

import asyncio
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

import pytest
from pebble import ProcessExpired

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "src"))

from ocr_worker.config import OCR_TIMEOUT_EXIT_CODE  # noqa: E402
from ocr_worker.consumer import _await_outcome_and_finalize  # noqa: E402
from ocr_worker.errors import error_code_of  # noqa: E402
from ocr_worker.models import ProcessingJob  # noqa: E402
from ocr_worker.repository import OcrRepository  # noqa: E402


class FakeMessage:
    """Records ack/nack, because "the delivery was settled exactly once"
    is as load-bearing as the cause being written -- an unsettled message
    is redelivered forever and permanently occupies a prefetch slot."""

    def __init__(self):
        self.acked = False
        self.nacked = False
        self.requeued: bool | None = None

    async def ack(self):
        self.acked = True

    async def nack(self, requeue: bool = True):
        self.nacked = True
        self.requeued = requeue


def _claimed_job(session_factory, seeded_version):
    """A job in exactly the state a dead worker leaves behind:
    `processing`, lease live, nothing recorded."""
    with session_factory() as session:
        repo = OcrRepository(session)
        repo.ensure_queued_job(
            tenant_id=seeded_version["tenant_id"],
            version_id=seeded_version["version_id"],
            correlation_id=None,
        )
        session.commit()
    with session_factory() as session:
        job = session.query(ProcessingJob).filter_by(reference_id=seeded_version["version_id"]).one()
        job.status = "processing"
        job.attempts = 1
        job.lease_expires_at = datetime.now(timezone.utc) + timedelta(seconds=600)
        job.last_error = None
        session.commit()


def _last_error(session_factory, version_id):
    with session_factory() as session:
        return session.query(ProcessingJob).filter_by(reference_id=version_id).one().last_error


def _failed_future(exc: BaseException):
    from concurrent.futures import Future

    future: Future = Future()
    future.set_exception(exc)
    return future


def _run(future, message, version_id, session_factory, task_timeout_s=900.0):
    async def main():
        await _await_outcome_and_finalize(
            future, message, version_id=version_id,
            loop=asyncio.get_running_loop(), task_timeout_s=task_timeout_s,
            session_factory=session_factory,
        )

    asyncio.run(main())


def test_the_outer_pool_ceiling_records_a_timeout_before_releasing_the_lease(
    session_factory, seeded_version
):
    """The worker's own deadline never fired, so nothing in the worker
    wrote a cause. Without this the reaper saw a blank `last_error` and
    retried a document that cannot finish."""
    version_id = seeded_version["version_id"]
    _claimed_job(session_factory, seeded_version)
    message = FakeMessage()

    _run(_failed_future(TimeoutError()), message, version_id, session_factory)

    recorded = _last_error(session_factory, version_id)
    assert recorded is not None, "the cause must be recorded by the parent"
    assert error_code_of(recorded) == "TIMEOUT"
    assert "outer pool ceiling" in recorded
    assert (message.nacked, message.requeued) == (True, False)


def test_a_crashed_pool_worker_records_worker_crash(session_factory, seeded_version):
    """An OOM kill. The exit code is the only thing distinguishing it from
    a timeout kill, and pebble surfaces both as ProcessExpired."""
    version_id = seeded_version["version_id"]
    _claimed_job(session_factory, seeded_version)
    message = FakeMessage()

    _run(_failed_future(ProcessExpired("killed", code=-9)), message, version_id, session_factory)

    recorded = _last_error(session_factory, version_id)
    assert error_code_of(recorded) == "WORKER_CRASH"
    assert "exitcode=-9" in recorded
    assert (message.nacked, message.requeued) == (True, False)


def test_the_hard_watchdog_path_does_not_overwrite_the_workers_own_record(
    session_factory, seeded_version
):
    """The one ProcessExpired case that must NOT be touched.

    Exit code 69 means the worker's own watchdog killed it *after* writing
    a complete TIMEOUT failure record -- status, outbox event and all.
    Writing over that from the parent would replace a finished, terminal
    failure with a half-one, and the CAS in `record_death_cause` is what
    makes that safe: the row is no longer `processing`, so nothing is
    written.
    """
    version_id = seeded_version["version_id"]
    _claimed_job(session_factory, seeded_version)
    with session_factory() as session:
        job = session.query(ProcessingJob).filter_by(reference_id=version_id).one()
        job.status = "failed"
        job.last_error = "TIMEOUT: exceeded the 85s budget for this document"
        session.commit()
    message = FakeMessage()

    _run(
        _failed_future(ProcessExpired("timed out", code=OCR_TIMEOUT_EXIT_CODE)),
        message, version_id, session_factory,
    )

    assert _last_error(session_factory, version_id) == "TIMEOUT: exceeded the 85s budget for this document"
    assert (message.nacked, message.requeued) == (True, False)


def test_recording_the_cause_never_costs_the_nack(session_factory, seeded_version):
    """Bookkeeping must not be able to lose the delivery. If the database
    is unreachable at exactly this moment, the message still has to be
    settled -- an unsettled delivery is redelivered forever."""
    version_id = seeded_version["version_id"]
    _claimed_job(session_factory, seeded_version)
    message = FakeMessage()

    def exploding_factory():
        raise RuntimeError("database is down")

    _run(_failed_future(ProcessExpired("killed", code=-9)), message, version_id, exploding_factory)

    assert (message.nacked, message.requeued) == (True, False)


def test_a_job_that_is_no_longer_processing_is_left_alone(session_factory, seeded_version):
    """The CAS guard, directly: a worker that came back just in time, or
    another reaper that got there first, must not have its terminal state
    overwritten by a late parent write."""
    version_id = seeded_version["version_id"]
    _claimed_job(session_factory, seeded_version)
    with session_factory() as session:
        job = session.query(ProcessingJob).filter_by(reference_id=version_id).one()
        job.status = "completed"
        session.commit()

    with session_factory() as session:
        wrote = OcrRepository(session).record_death_cause(
            version_id=version_id, error="WORKER_CRASH: too late"
        )
    assert wrote is False
    assert _last_error(session_factory, version_id) is None


@pytest.mark.parametrize("cause", [
    "TIMEOUT: hit the outer pool ceiling (900s) without the in-worker deadline firing",
    "WORKER_CRASH: pool worker died with exitcode=-9 while processing this document",
])
def test_what_the_parent_writes_is_readable_by_the_reaper(cause):
    """The two halves have to agree on the format, or the reaper silently
    falls back to the old budget for every death."""
    assert error_code_of(cause) in ("TIMEOUT", "WORKER_CRASH")


def test_a_kill_at_the_ceiling_is_recorded_as_a_timeout_not_a_crash(
    session_factory, seeded_version
):
    """A SIGKILL and an OOM kill are the same exit code.

    pebble force-terminates a worker to enforce its own ceiling, and on
    Linux that is SIGKILL -- exitcode -9, identical to what the kernel's
    OOM killer produces. Only the timing distinguishes them. Calling a
    ceiling kill `WORKER_CRASH` would give it the wrong retry budget (3
    instead of 1) and tell the user their worker died when in fact their
    document took too long.
    """
    version_id = seeded_version["version_id"]
    _claimed_job(session_factory, seeded_version)
    message = FakeMessage()

    # task_timeout_s of 0 makes any elapsed time count as "at the ceiling".
    _run(
        _failed_future(ProcessExpired("killed", code=-9)),
        message, version_id, session_factory, task_timeout_s=0.0,
    )

    recorded = _last_error(session_factory, version_id)
    assert error_code_of(recorded) == "TIMEOUT"
    assert "ceiling" in recorded
    assert (message.nacked, message.requeued) == (True, False)


def test_a_kill_well_inside_the_ceiling_is_still_a_crash(session_factory, seeded_version):
    """The other side of the same test: a worker that dies after two
    seconds of a fifteen-minute budget was killed by something else --
    an OOM, a segfault in a native library -- and that genuinely can be
    transient, so it keeps the crash budget."""
    version_id = seeded_version["version_id"]
    _claimed_job(session_factory, seeded_version)
    message = FakeMessage()

    _run(
        _failed_future(ProcessExpired("killed", code=-9)),
        message, version_id, session_factory, task_timeout_s=900.0,
    )

    assert error_code_of(_last_error(session_factory, version_id)) == "WORKER_CRASH"
