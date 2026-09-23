from __future__ import annotations

import json
import uuid
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone

from sqlalchemy import select, update
from sqlalchemy.orm import Session

from .config import ROUTING_KEY_OCR_COMPLETED, ROUTING_KEY_OCR_FAILED, ROUTING_KEY_OCR_REQUESTED
from .extraction import RequestedFieldResult
from .models import (
    Document,
    DocumentOcrFieldResult,
    DocumentType,
    DocumentTypeOcrZone,
    DocumentVersion,
    OcrOutput,
    OutboxEvent,
    ProcessingJob,
)
from .zones import ZoneRegion


class VersionNotFound(Exception):
    pass


class VersionNotEligible(Exception):
    """Raised when scan_status != 'clean'. Section 2.2's precondition:
    "Scanning shall occur prior to OCR processing" -- this is the single
    enforcement point every trigger path (queue consumer, dev-trigger
    endpoint, /ocr/zone, /ocr/rubber-band) goes through, so none of them can
    accidentally run OCR on an unscanned or infected version.
    """

    def __init__(self, version_id: uuid.UUID, scan_status: str):
        super().__init__(f"version {version_id} is not eligible for OCR (scan_status={scan_status!r})")
        self.version_id = version_id
        self.scan_status = scan_status


@dataclass
class VersionContext:
    version: DocumentVersion
    document: Document
    document_type_id: uuid.UUID
    document_type_name: str


class OcrRepository:
    def __init__(self, session: Session):
        self.session = session

    def get_version_for_processing(self, version_id: uuid.UUID) -> VersionContext:
        version = self.session.get(DocumentVersion, version_id)
        if version is None:
            raise VersionNotFound(str(version_id))
        if version.scan_status != "clean":
            raise VersionNotEligible(version_id, version.scan_status)

        document = self.session.get(Document, version.document_id)
        document_type = self.session.get(DocumentType, document.document_type_id)
        return VersionContext(
            version=version,
            document=document,
            document_type_id=document_type.document_type_id,
            document_type_name=document_type.type_name,
        )

    def get_zone_regions(self, document_type_id: uuid.UUID) -> list[ZoneRegion]:
        """OS-FR-003/OS-FR-005 template lookup -- docs/database/V11__create_ocr_templates.sql.
        Empty list (not an exception) for a document type with no
        configured zones, matching the alternate flow "document type does
        not match any configured zone OCR template -> falls back to basic
        full-document OCR".
        """
        rows = (
            self.session.execute(
                select(DocumentTypeOcrZone)
                .where(DocumentTypeOcrZone.document_type_id == document_type_id)
                .order_by(DocumentTypeOcrZone.page_number, DocumentTypeOcrZone.zone_name)
            )
            .scalars()
            .all()
        )
        return [
            ZoneRegion(
                name=row.zone_name,
                field=row.field_name,
                page=row.page_number,
                x0=float(row.x0),
                y0=float(row.y0),
                x1=float(row.x1),
                y1=float(row.y1),
            )
            for row in rows
        ]

    def ensure_queued_job(self, *, tenant_id: uuid.UUID, version_id: uuid.UUID, correlation_id: uuid.UUID | None) -> None:
        """Dev/smoke-test convenience only -- the real production path
        (dms-core's upload/rerun) always inserts a queued row itself, in
        the SAME transaction as the `document.version.ocr.requested`
        outbox event (see DocumentServiceImpl.uploadDocument/prepareRerun).
        A no-op if an active (queued/processing) row already exists for
        this version -- relies on the same partial unique index
        (V36__ocr_job_leasing.sql) `claim_job` itself depends on, so this
        is always safe to call unconditionally before claiming, whether or
        not a real caller already inserted the row.
        """
        existing = self.session.execute(
            select(ProcessingJob.job_id).where(
                ProcessingJob.reference_type == "document_versions",
                ProcessingJob.reference_id == version_id,
                ProcessingJob.job_type == "ocr",
                ProcessingJob.status.in_(["queued", "processing"]),
            )
        ).scalar_one_or_none()
        if existing is not None:
            return
        self.session.add(
            ProcessingJob(
                tenant_id=tenant_id,
                job_type="ocr",
                reference_type="document_versions",
                reference_id=version_id,
                status="queued",
                attempts=0,
                correlation_id=correlation_id,
            )
        )
        self.session.flush()

    def claim_job(self, *, version_id: uuid.UUID, lease_seconds: int) -> ProcessingJob | None:
        """Compare-and-swap claim: atomically transitions the version's one
        active ocr job (a 'queued' row, or a 'processing' row whose lease
        has expired) to 'processing' with a fresh lease, incrementing
        attempts. Returns None if no matching row exists -- another
        worker/a still-live lease already owns it, a requeued job's
        backoff (`next_attempt_at`) hasn't elapsed yet, or (should never
        happen after ensure_queued_job) no active row exists at all -- the
        caller must ack-and-skip, not treat this as a failure. The same
        shape is reused by the reaper's own requeue path (repository.py's
        `reap_requeue`), just against an already-expired lease instead of
        a fresh 'queued' row.

        The `next_attempt_at` check exists because the outbox-relayed
        `.requested` event `reap_requeue` emits (B.4) can be delivered to
        a consumer well before its backoff window elapses -- without this,
        a systematically-failing document would be reclaimed and retried
        immediately instead of actually backing off, making the reaper's
        own backoff meaningless.
        """
        now = datetime.now(timezone.utc)
        lease_expires_at = now + timedelta(seconds=lease_seconds)
        stmt = (
            update(ProcessingJob)
            .where(
                ProcessingJob.reference_type == "document_versions",
                ProcessingJob.reference_id == version_id,
                ProcessingJob.job_type == "ocr",
                ProcessingJob.status.in_(["queued", "processing"]),
                (ProcessingJob.status != "processing") | (ProcessingJob.lease_expires_at < now),
                (ProcessingJob.next_attempt_at.is_(None)) | (ProcessingJob.next_attempt_at <= now),
            )
            .values(status="processing", attempts=ProcessingJob.attempts + 1, started_at=now, lease_expires_at=lease_expires_at)
            .returning(ProcessingJob)
            # Skip SQLAlchemy's in-memory session-sync re-evaluation of the
            # WHERE clause against already-loaded ProcessingJob objects --
            # not needed (RETURNING already gives us the authoritative
            # updated row) and real bug found live: that re-evaluation is a
            # Python-level comparison, which crashed on SQLite round-
            # tripping lease_expires_at as a naive datetime against this
            # method's own timezone-aware `now`.
            .execution_options(synchronize_session=False)
        )
        job = self.session.execute(stmt).scalar_one_or_none()
        self.session.flush()
        if job is not None:
            # With synchronize_session=False (see above), a job already
            # loaded in this session's identity map (e.g. the reaper's own
            # find_expired_leases result being reclaimed) is returned as
            # that SAME stale Python object rather than one reflecting the
            # just-written values -- refresh it explicitly so callers never
            # see a pre-update `attempts`/`status`/`lease_expires_at`.
            self.session.refresh(job)
        return job

    def get_job(self, job_id: uuid.UUID) -> ProcessingJob:
        job = self.session.get(ProcessingJob, job_id)
        if job is None:
            raise VersionNotFound(f"processing_jobs row {job_id}")
        return job

    def find_expired_leases(self, *, limit: int = 50) -> list[ProcessingJob]:
        """Reaper scan (repository.py's own `ix_processing_jobs_lease_reaper`
        index, V36) -- every 'processing' row whose lease has expired
        without a matching completion. A crashed/hung worker never comes
        back to move these out of 'processing' on its own.
        """
        now = datetime.now(timezone.utc)
        rows = (
            self.session.execute(
                select(ProcessingJob)
                .where(ProcessingJob.job_type == "ocr", ProcessingJob.status == "processing", ProcessingJob.lease_expires_at < now)
                .limit(limit)
            )
            .scalars()
            .all()
        )
        return list(rows)

    def reap_requeue(self, *, job_id: uuid.UUID, backoff_seconds: int, tenant_id: uuid.UUID, version_id: uuid.UUID, event_payload: dict) -> bool:
        """CAS: requeues one expired-lease job for retry, and -- in the
        SAME transaction -- inserts a fresh `document.version.ocr.requested`
        outbox event (never publishes to RabbitMQ directly; matches
        ocr-worker's own existing, already-proven outbox-write pattern for
        `.completed`/`.failed`). Guarded by the identical `status='processing'
        AND lease_expires_at < now()` predicate `claim_job` uses, so this
        is safe under multiple concurrent ocr-worker instances each running
        their own reaper loop -- at most one wins per row, with no
        additional locking primitive needed. Returns False if another
        reaper (or a worker that came back just in time) already moved this
        row, so the caller knows not to also emit the event.
        """
        now = datetime.now(timezone.utc)
        stmt = (
            update(ProcessingJob)
            .where(ProcessingJob.job_id == job_id, ProcessingJob.status == "processing", ProcessingJob.lease_expires_at < now)
            .values(status="queued", next_attempt_at=now + timedelta(seconds=backoff_seconds), lease_expires_at=None)
            .execution_options(synchronize_session=False)
        )
        result = self.session.execute(stmt)
        if result.rowcount != 1:
            self.session.flush()
            return False
        self.session.add(
            OutboxEvent(
                tenant_id=tenant_id,
                aggregate_type="document_version",
                aggregate_id=version_id,
                event_type=ROUTING_KEY_OCR_REQUESTED,
                payload=event_payload,
            )
        )
        self.session.flush()
        return True

    def reap_dead_letter(self, *, job_id: uuid.UUID, version: DocumentVersion, error: str, event_payload: dict) -> bool:
        """CAS: moves one expired-lease job to 'dead_letter' after it has
        exhausted max_delivery_attempts, sets document_versions.ocr_status
        ='failed', and -- in the SAME transaction -- inserts a
        `document.version.ocr.failed` outbox event. This is what actually
        satisfies "zero stuck processing versions": nothing else would ever
        move this version out of 'processing' if the worker that held the
        lease crashed (e.g. an OOM kill) without ever coming back.
        """
        now = datetime.now(timezone.utc)
        stmt = (
            update(ProcessingJob)
            .where(ProcessingJob.job_id == job_id, ProcessingJob.status == "processing", ProcessingJob.lease_expires_at < now)
            .values(status="dead_letter", last_error=error[:4000], finished_at=now)
            .execution_options(synchronize_session=False)
        )
        result = self.session.execute(stmt)
        if result.rowcount != 1:
            self.session.flush()
            return False
        version.ocr_status = "failed"
        self.session.add(
            OutboxEvent(
                tenant_id=version.tenant_id,
                aggregate_type="document_version",
                aggregate_id=version.version_id,
                event_type=ROUTING_KEY_OCR_FAILED,
                payload=event_payload,
            )
        )
        self.session.flush()
        return True

    def complete_success(
        self,
        *,
        job: ProcessingJob,
        version: DocumentVersion,
        extracted_text_ref: str,
        language: str | None,
        event_payload: dict,
    ) -> OcrOutput:
        ocr_output = OcrOutput(
            tenant_id=version.tenant_id,
            version_id=version.version_id,
            extracted_text_ref=extracted_text_ref,
            language=language,
            status="completed",
        )
        self.session.add(ocr_output)

        version.ocr_status = "completed"

        job.status = "completed"
        job.finished_at = datetime.now(timezone.utc)

        self.session.add(
            OutboxEvent(
                tenant_id=version.tenant_id,
                aggregate_type="document_version",
                aggregate_id=version.version_id,
                event_type=ROUTING_KEY_OCR_COMPLETED,
                payload=event_payload,
            )
        )
        self.session.flush()
        return ocr_output

    def upsert_field_results(
        self,
        *,
        tenant_id: uuid.UUID,
        document_id: uuid.UUID,
        version_id: uuid.UUID,
        results: list[RequestedFieldResult],
    ) -> None:
        """One row per (document_id, field_key) -- a re-run replaces the
        row for that field rather than accumulating history, matching
        document_metadata_values' own "current state" shape (see V35's own
        comment). Runs inside the same session/transaction as
        ocr_outputs/processing_jobs/outbox_events (see session_scope) --
        one atomic commit for the whole pipeline run.
        """
        now = datetime.now(timezone.utc)
        for result in results:
            value = json.dumps(result.value) if isinstance(result.value, list) else result.value
            bbox = list(result.bbox) if result.bbox else None
            existing = self.session.execute(
                select(DocumentOcrFieldResult).where(
                    DocumentOcrFieldResult.document_id == document_id,
                    DocumentOcrFieldResult.field_key == result.field_key,
                )
            ).scalar_one_or_none()
            if existing is not None:
                existing.version_id = version_id
                existing.value = value
                existing.confidence = result.confidence
                existing.status = result.status
                existing.source_page = result.page
                existing.source_bbox = bbox
                existing.updated_at = now
            else:
                self.session.add(
                    DocumentOcrFieldResult(
                        tenant_id=tenant_id,
                        document_id=document_id,
                        version_id=version_id,
                        field_key=result.field_key,
                        value=value,
                        confidence=result.confidence,
                        status=result.status,
                        source_page=result.page,
                        source_bbox=bbox,
                    )
                )
        self.session.flush()

    def complete_failure(self, *, job: ProcessingJob, version: DocumentVersion, error: str, event_payload: dict) -> None:
        version.ocr_status = "failed"

        job.status = "failed"
        job.last_error = error[:4000]
        job.finished_at = datetime.now(timezone.utc)

        self.session.add(
            OutboxEvent(
                tenant_id=version.tenant_id,
                aggregate_type="document_version",
                aggregate_id=version.version_id,
                event_type=ROUTING_KEY_OCR_FAILED,
                payload=event_payload,
            )
        )
        self.session.flush()
