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

        events = session.query(OutboxEvent).filter_by(aggregate_id=seeded_version["version_id"]).all()
        assert any(e.event_type == "document.version.ocr.requested" for e in events)


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
        repo1 = OcrRepository(session1)
        repo2 = OcrRepository(session2)
        payload = {"schema_version": 1}

        won1 = repo1.reap_requeue(
            job_id=job_id, backoff_seconds=30, tenant_id=seeded_version["tenant_id"],
            version_id=seeded_version["version_id"], event_payload=payload,
        )
        session1.commit()

        won2 = repo2.reap_requeue(
            job_id=job_id, backoff_seconds=30, tenant_id=seeded_version["tenant_id"],
            version_id=seeded_version["version_id"], event_payload=payload,
        )
        session2.commit()

    assert won1 is True
    assert won2 is False  # second instance's CAS UPDATE matched 0 rows -- already moved by the first

    with session_factory() as session:
        events = session.query(OutboxEvent).filter_by(aggregate_id=seeded_version["version_id"]).all()
        requested_events = [e for e in events if e.event_type == "document.version.ocr.requested"]
        assert len(requested_events) == 1  # not double-emitted by the loser
