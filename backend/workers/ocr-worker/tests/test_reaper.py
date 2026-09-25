from __future__ import annotations

import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "src"))

from ocr_worker.config import Settings  # noqa: E402
from ocr_worker.models import DocumentVersion, OutboxEvent, ProcessingJob  # noqa: E402
from ocr_worker.reaper import Reaper  # noqa: E402
from ocr_worker.repository import OcrRepository  # noqa: E402


def _expire_lease(session_factory, version_id, *, attempts: int = 1):
    """Puts the version's one active job into 'processing' with an
    already-expired lease -- simulates a crashed/killed worker that
    claimed the job but never came back to finalize it.
    """
    with session_factory() as session:
        job = session.query(ProcessingJob).filter_by(reference_id=version_id).one()
        job.status = "processing"
        job.attempts = attempts
        job.lease_expires_at = datetime.now(timezone.utc) - timedelta(seconds=1)
        session.commit()
        return job.job_id


def test_claim_job_respects_next_attempt_at_not_yet_due(session_factory, seeded_version):
    """A requeued job's backoff must actually be enforced -- otherwise the
    outbox-relayed .requested event the reaper emits on requeue could be
    reclaimed and retried immediately, making the backoff meaningless.
    """
    with session_factory() as session:
        repo = OcrRepository(session)
        repo.ensure_queued_job(tenant_id=seeded_version["tenant_id"], version_id=seeded_version["version_id"], correlation_id=None)
        job = session.query(ProcessingJob).filter_by(reference_id=seeded_version["version_id"]).one()
        job.next_attempt_at = datetime.now(timezone.utc) + timedelta(seconds=60)
        session.commit()

        not_yet = repo.claim_job(version_id=seeded_version["version_id"], lease_seconds=1800)
        session.commit()
        assert not_yet is None


def test_claim_job_succeeds_once_next_attempt_at_has_elapsed(session_factory, seeded_version):
    with session_factory() as session:
        repo = OcrRepository(session)
        repo.ensure_queued_job(tenant_id=seeded_version["tenant_id"], version_id=seeded_version["version_id"], correlation_id=None)
        job = session.query(ProcessingJob).filter_by(reference_id=seeded_version["version_id"]).one()
        job.next_attempt_at = datetime.now(timezone.utc) - timedelta(seconds=1)
        session.commit()

        claimed = repo.claim_job(version_id=seeded_version["version_id"], lease_seconds=1800)
        session.commit()
        assert claimed is not None


def test_reaper_requeues_an_expired_lease_under_max_attempts(session_factory, seeded_version):
    with session_factory() as session:
        repo = OcrRepository(session)
        repo.ensure_queued_job(tenant_id=seeded_version["tenant_id"], version_id=seeded_version["version_id"], correlation_id=None)
        session.commit()
    _expire_lease(session_factory, seeded_version["version_id"], attempts=1)

    settings = Settings(max_delivery_attempts=5, ocr_reaper_backoff_base_s=30)
    reaper = Reaper(settings, session_factory)
    reaper._reap_once()

    with session_factory() as session:
        job = session.query(ProcessingJob).filter_by(reference_id=seeded_version["version_id"]).one()
        assert job.status == "queued"
        assert job.next_attempt_at is not None
        # SQLite round-trips TIMESTAMPTZ as a naive datetime (same quirk
        # repository.py's own claim_job works around) -- compare against a
        # naive "now" rather than assuming tz-awareness survives the round trip.
        assert job.next_attempt_at > datetime.now(timezone.utc).replace(tzinfo=None)
        assert job.lease_expires_at is None

        version = session.get(DocumentVersion, seeded_version["version_id"])
        assert version.ocr_status == "pending"  # untouched -- this is a retry, not a failure

        # NO event yet: emitting one here is what silently killed every
        # retry before Phase 1 close-out (OutboxRelay delivered it inside
        # the backoff window, claim_job refused, the consumer acked and
        # skipped, and nothing ever published again). The event belongs to
        # the tick where the backoff has actually elapsed.
        events = session.query(OutboxEvent).filter_by(aggregate_id=seeded_version["version_id"]).all()
        assert [e.event_type for e in events] == []


def test_reaper_emits_the_retry_event_only_once_the_backoff_has_elapsed(session_factory, seeded_version):
    with session_factory() as session:
        repo = OcrRepository(session)
        repo.ensure_queued_job(tenant_id=seeded_version["tenant_id"], version_id=seeded_version["version_id"], correlation_id=None)
        session.commit()
    _expire_lease(session_factory, seeded_version["version_id"], attempts=1)

    settings = Settings(max_delivery_attempts=5, ocr_reaper_backoff_base_s=30)
    reaper = Reaper(settings, session_factory)
    reaper._reap_once()  # requeues with a 30s backoff, no event

    reaper._reap_once()  # backoff still live -- must still not emit
    with session_factory() as session:
        assert session.query(OutboxEvent).filter_by(aggregate_id=seeded_version["version_id"]).count() == 0
        job = session.query(ProcessingJob).filter_by(reference_id=seeded_version["version_id"]).one()
        assert job.next_attempt_at is not None

    # Fast-forward the backoff rather than sleeping 30s.
    with session_factory() as session:
        job = session.query(ProcessingJob).filter_by(reference_id=seeded_version["version_id"]).one()
        job.next_attempt_at = datetime.now(timezone.utc) - timedelta(seconds=1)
        session.commit()

    reaper._reap_once()

    with session_factory() as session:
        job = session.query(ProcessingJob).filter_by(reference_id=seeded_version["version_id"]).one()
        events = session.query(OutboxEvent).filter_by(aggregate_id=seeded_version["version_id"]).all()
        assert [e.event_type for e in events] == ["document.version.ocr.requested"]
        # Cleared, so claim_job's own next_attempt_at gate now passes AND a
        # later tick cannot emit a second event for the same retry.
        assert job.next_attempt_at is None
        assert job.status == "queued"

        repo = OcrRepository(session)
        claimed = repo.claim_job(version_id=seeded_version["version_id"], lease_seconds=1800)
        session.commit()
        assert claimed is not None, "the event the reaper just emitted must be claimable when it arrives"
        assert claimed.attempts == 2


def test_a_due_retry_event_is_never_emitted_twice(session_factory, seeded_version):
    """Two reaper ticks (or two ocr-worker instances) must not both publish
    -- the CAS clears next_attempt_at, which is both the claim gate and
    the de-duplication key.
    """
    with session_factory() as session:
        repo = OcrRepository(session)
        repo.ensure_queued_job(tenant_id=seeded_version["tenant_id"], version_id=seeded_version["version_id"], correlation_id=None)
        session.commit()
    _expire_lease(session_factory, seeded_version["version_id"], attempts=1)

    settings = Settings(max_delivery_attempts=5, ocr_reaper_backoff_base_s=0)
    reaper = Reaper(settings, session_factory)
    reaper._reap_once()
    reaper._reap_once()
    reaper._reap_once()

    with session_factory() as session:
        events = session.query(OutboxEvent).filter_by(aggregate_id=seeded_version["version_id"]).all()
        assert [e.event_type for e in events] == ["document.version.ocr.requested"]


def test_a_fresh_upload_is_never_picked_up_as_a_due_retry(session_factory, seeded_version):
    """dms-core inserts its own 'queued' row and publishes its own event in
    the same transaction, leaving next_attempt_at NULL. The reaper must not
    treat that as a retry it owes an event to, or every upload would get a
    duplicate.
    """
    with session_factory() as session:
        repo = OcrRepository(session)
        repo.ensure_queued_job(tenant_id=seeded_version["tenant_id"], version_id=seeded_version["version_id"], correlation_id=None)
        session.commit()

    Reaper(Settings(), session_factory)._reap_once()

    with session_factory() as session:
        assert session.query(OutboxEvent).filter_by(aggregate_id=seeded_version["version_id"]).count() == 0


def test_reaper_dead_letters_an_expired_lease_at_max_attempts(session_factory, seeded_version):
    with session_factory() as session:
        repo = OcrRepository(session)
        repo.ensure_queued_job(tenant_id=seeded_version["tenant_id"], version_id=seeded_version["version_id"], correlation_id=None)
        session.commit()
    _expire_lease(session_factory, seeded_version["version_id"], attempts=5)

    settings = Settings(max_delivery_attempts=5, ocr_reaper_backoff_base_s=30)
    reaper = Reaper(settings, session_factory)
    reaper._reap_once()

    with session_factory() as session:
        job = session.query(ProcessingJob).filter_by(reference_id=seeded_version["version_id"]).one()
        assert job.status == "dead_letter"
        assert "WORKER_CRASH" in job.last_error
        assert job.finished_at is not None

        version = session.get(DocumentVersion, seeded_version["version_id"])
        assert version.ocr_status == "failed"

        events = session.query(OutboxEvent).filter_by(aggregate_id=seeded_version["version_id"]).all()
        assert any(e.event_type == "document.version.ocr.failed" for e in events)


def test_reaper_ignores_a_job_whose_lease_has_not_expired(session_factory, seeded_version):
    """A live worker still legitimately holding its lease must never be
    reaped out from under it.
    """
    with session_factory() as session:
        repo = OcrRepository(session)
        repo.ensure_queued_job(tenant_id=seeded_version["tenant_id"], version_id=seeded_version["version_id"], correlation_id=None)
        repo.claim_job(version_id=seeded_version["version_id"], lease_seconds=1800)
        session.commit()

    settings = Settings(max_delivery_attempts=5, ocr_reaper_backoff_base_s=30)
    reaper = Reaper(settings, session_factory)
    reaper._reap_once()

    with session_factory() as session:
        job = session.query(ProcessingJob).filter_by(reference_id=seeded_version["version_id"]).one()
        assert job.status == "processing"  # untouched


def test_reap_requeue_is_a_cas_only_one_reaper_instance_wins_a_race(session_factory, seeded_version):
    """Simulates two ocr-worker instances' reaper loops racing on the same
    expired-lease row -- the CAS UPDATE's WHERE clause (status='processing'
    AND lease_expires_at < now()) means only the first writer's UPDATE can
    ever match; the second necessarily finds 0 rows once the first commits.
    """
    with session_factory() as session:
        repo = OcrRepository(session)
        repo.ensure_queued_job(tenant_id=seeded_version["tenant_id"], version_id=seeded_version["version_id"], correlation_id=None)
        session.commit()
    job_id = _expire_lease(session_factory, seeded_version["version_id"], attempts=1)

    with session_factory() as session1, session_factory() as session2:
        won1 = OcrRepository(session1).reap_requeue(job_id=job_id, backoff_seconds=30, leaseless_grace_seconds=1800)
        session1.commit()

        won2 = OcrRepository(session2).reap_requeue(job_id=job_id, backoff_seconds=30, leaseless_grace_seconds=1800)
        session2.commit()

    assert won1 is True
    assert won2 is False  # second instance's CAS UPDATE matched 0 rows -- already moved by the first


# ----------------------------------------------------------------------
# Leaseless 'processing' rows (docs/ocr/phase1-closeout.md, "Still open
# after this" #3). `lease_expires_at < now()` is NULL, not true, for a row
# with no lease -- so such a row was invisible to the reaper forever and
# kept holding V36's partial unique index slot for its version.
#
# V37's CHECK constraint now forbids writing the shape at all; this arm is
# for rows that predate it. Note these tests can only exist against SQLite,
# which has no such constraint -- which is exactly why the constraint is
# the primary fix and this is the backstop.
# ----------------------------------------------------------------------
def _leaseless_processing(session_factory, version_id, *, age_seconds: float, attempts: int = 1, set_started_at: bool = True):
    with session_factory() as session:
        job = session.query(ProcessingJob).filter_by(reference_id=version_id).one()
        job.status = "processing"
        job.attempts = attempts
        job.lease_expires_at = None
        moment = datetime.now(timezone.utc) - timedelta(seconds=age_seconds)
        job.started_at = moment if set_started_at else None
        job.created_at = moment
        session.commit()
        return job.job_id


def test_an_old_leaseless_processing_row_is_reaped(session_factory, seeded_version):
    with session_factory() as session:
        repo = OcrRepository(session)
        repo.ensure_queued_job(tenant_id=seeded_version["tenant_id"], version_id=seeded_version["version_id"], correlation_id=None)
        session.commit()
    _leaseless_processing(session_factory, seeded_version["version_id"], age_seconds=1801)

    Reaper(Settings(ocr_lease_duration_s=1800, ocr_reaper_backoff_base_s=30), session_factory)._reap_once()

    with session_factory() as session:
        job = session.query(ProcessingJob).filter_by(reference_id=seeded_version["version_id"]).one()
        assert job.status == "queued", "a leaseless row older than the lease duration must not stay 'processing' forever"


def test_a_leaseless_processing_row_with_no_started_at_is_still_reaped(session_factory, seeded_version):
    """All four rows found in the local database had started_at NULL too,
    so started_at alone would have left them just as invisible. created_at
    is NOT NULL with a DB default, which is what makes the fallback total.
    """
    with session_factory() as session:
        repo = OcrRepository(session)
        repo.ensure_queued_job(tenant_id=seeded_version["tenant_id"], version_id=seeded_version["version_id"], correlation_id=None)
        session.commit()
    _leaseless_processing(session_factory, seeded_version["version_id"], age_seconds=1801, set_started_at=False)

    Reaper(Settings(ocr_lease_duration_s=1800, ocr_reaper_backoff_base_s=30), session_factory)._reap_once()

    with session_factory() as session:
        assert session.query(ProcessingJob).filter_by(reference_id=seeded_version["version_id"]).one().status == "queued"


def test_a_recent_leaseless_processing_row_is_left_alone(session_factory, seeded_version):
    """The grace period is the point -- reaping immediately would race a
    writer that is mid-claim.
    """
    with session_factory() as session:
        repo = OcrRepository(session)
        repo.ensure_queued_job(tenant_id=seeded_version["tenant_id"], version_id=seeded_version["version_id"], correlation_id=None)
        session.commit()
    _leaseless_processing(session_factory, seeded_version["version_id"], age_seconds=5)

    Reaper(Settings(ocr_lease_duration_s=1800, ocr_reaper_backoff_base_s=30), session_factory)._reap_once()

    with session_factory() as session:
        assert session.query(ProcessingJob).filter_by(reference_id=seeded_version["version_id"]).one().status == "processing"


def test_an_old_leaseless_row_at_max_attempts_is_dead_lettered(session_factory, seeded_version):
    """The dead-letter CAS has to share the scan's predicate too. When it
    didn't, the scan returned the row on every tick and this UPDATE matched
    nothing -- a silent infinite loop rather than a visible failure.
    """
    with session_factory() as session:
        repo = OcrRepository(session)
        repo.ensure_queued_job(tenant_id=seeded_version["tenant_id"], version_id=seeded_version["version_id"], correlation_id=None)
        session.commit()
    _leaseless_processing(session_factory, seeded_version["version_id"], age_seconds=1801, attempts=5)

    Reaper(Settings(ocr_lease_duration_s=1800, max_delivery_attempts=5), session_factory)._reap_once()

    with session_factory() as session:
        job = session.query(ProcessingJob).filter_by(reference_id=seeded_version["version_id"]).one()
        assert job.status == "dead_letter"
        assert "WORKER_CRASH" in job.last_error
        assert session.get(DocumentVersion, seeded_version["version_id"]).ocr_status == "failed"


def test_reaping_a_leaseless_row_logs_a_warning_naming_it(session_factory, seeded_version, caplog):
    """A missing lease is a different and more alarming thing than an
    expired one -- nothing should be writing that shape -- so it must not
    be reaped silently.
    """
    with session_factory() as session:
        repo = OcrRepository(session)
        repo.ensure_queued_job(tenant_id=seeded_version["tenant_id"], version_id=seeded_version["version_id"], correlation_id=None)
        session.commit()
    job_id = _leaseless_processing(session_factory, seeded_version["version_id"], age_seconds=1801)

    with caplog.at_level("WARNING", logger="ocr_worker.reaper"):
        Reaper(Settings(ocr_lease_duration_s=1800), session_factory)._reap_once()

    leaseless_warnings = [r.getMessage() for r in caplog.records if "NO lease" in r.getMessage()]
    assert len(leaseless_warnings) == 1
    assert str(job_id) in leaseless_warnings[0]


def test_an_ordinary_expired_lease_does_not_trigger_the_leaseless_warning(session_factory, seeded_version):
    with session_factory() as session:
        repo = OcrRepository(session)
        repo.ensure_queued_job(tenant_id=seeded_version["tenant_id"], version_id=seeded_version["version_id"], correlation_id=None)
        session.commit()
    _expire_lease(session_factory, seeded_version["version_id"], attempts=1)

    with session_factory() as session:
        job = session.query(ProcessingJob).filter_by(reference_id=seeded_version["version_id"]).one()
        assert OcrRepository.is_leaseless(job) is False
