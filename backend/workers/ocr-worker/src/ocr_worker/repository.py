from __future__ import annotations

import json
import logging
import uuid
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone

from sqlalchemy import and_, func, or_, select, update
from sqlalchemy.orm import Session

from .config import ROUTING_KEY_OCR_COMPLETED, ROUTING_KEY_OCR_FAILED, ROUTING_KEY_OCR_REQUESTED
from .extraction import FieldSpec, RequestedFieldResult
from .models import (
    Document,
    DocumentOcrFieldResult,
    DocumentType,
    DocumentTypeField,
    DocumentTypeOcrZone,
    DocumentVersion,
    MetadataField,
    MetadataFieldOption,
    DocumentOcrLineItem,
    OcrOutput,
    OutboxEvent,
    ProcessingJob,
    Rendition,
)
from .zones import ZoneRegion

logger = logging.getLogger(__name__)

# Field data types whose allowed values come from metadata_field_options --
# mirrors dms-core's MetadataField.OPTION_BACKED_DATA_TYPES exactly.
OPTION_BACKED_DATA_TYPES = frozenset({"dropdown", "multi_select"})


def _as_utc(value: datetime) -> datetime:
    """SQLite round-trips a TIMESTAMPTZ as a naive datetime (the same quirk
    `claim_job` and models.py already work around); Postgres keeps it
    aware. Only used by diagnostics, never by a claim predicate -- those
    compare in SQL, where the dialect handles it.
    """
    return value if value.tzinfo is not None else value.replace(tzinfo=timezone.utc)


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

    def resolve_requested_field_specs(self, *, version: DocumentVersion, document_type_id: uuid.UUID) -> list[FieldSpec] | None:
        """Turn `document_versions.requested_fields` (a jsonb array of
        field_key strings, written by dms-core's uploadDocument/
        prepareRerun) into the `FieldSpec`s extraction.py needs, by
        resolving each key against the document type's CURRENT schema.

        This is the half of docs/events/ocr-scanning-events.md's "Phase 1
        Decision 1" that was specified but never implemented: the
        `document.version.ocr.requested` event deliberately carries no
        resolved field specs *because* the worker was supposed to re-read
        them here at claim time. It didn't, so the queue path always ran
        with `requested_fields=None` and `document_ocr_field_results` was
        never written by a real upload -- only by the dev-trigger endpoint,
        which passes specs explicitly in its request body. Everything
        downstream of that table (dms-core's OcrEventsConsumer ->
        applyOcrFields -> document_metadata_values) therefore had nothing
        to act on.

        Returns `None` when the column is empty, which preserves the
        pre-existing behavior exactly: `None` means "legacy zone-template +
        generic regex over everything", and a document uploaded without a
        `requestedFields` parameter must keep getting that. A non-empty
        column returns a list -- possibly empty, if none of the requested
        keys resolve, which is a real (and loudly logged) data problem, not
        a reason to silently fall back to guessing at unrelated fields.

        Mirrors DocumentTypeFieldServiceImpl#getSchema's own filters:
        current schema version only, non-deleted mapping AND field rows,
        tenant-scoped, options only for option-backed data types and only
        the active ones in display order.
        """
        raw = version.requested_fields
        if isinstance(raw, str):  # jsonb round-tripped as text by some drivers
            try:
                raw = json.loads(raw)
            except json.JSONDecodeError:
                logger.error("version %s: requested_fields is not valid JSON (%r)", version.version_id, raw)
                return None
        keys = [k for k in (raw or []) if isinstance(k, str) and k]
        if not keys:
            return None

        rows = self.session.execute(
            select(MetadataField.field_id, MetadataField.field_key, MetadataField.data_type,
                   MetadataField.is_ocr_extractable, DocumentTypeField.is_mandatory)
            .join(DocumentTypeField, DocumentTypeField.field_id == MetadataField.field_id)
            .join(DocumentType, DocumentType.document_type_id == DocumentTypeField.document_type_id)
            .where(
                DocumentTypeField.document_type_id == document_type_id,
                DocumentTypeField.tenant_id == version.tenant_id,
                DocumentTypeField.schema_version == DocumentType.current_schema_version,
                DocumentTypeField.is_deleted.is_(False),
                MetadataField.tenant_id == version.tenant_id,
                MetadataField.is_deleted.is_(False),
                MetadataField.field_key.in_(keys),
            )
        ).all()
        by_key = {row.field_key: row for row in rows}

        option_backed_ids = [row.field_id for row in rows if row.data_type in OPTION_BACKED_DATA_TYPES]
        options_by_field_id: dict[uuid.UUID, list[str]] = {}
        if option_backed_ids:
            option_rows = self.session.execute(
                select(MetadataFieldOption.field_id, MetadataFieldOption.option_value)
                .where(
                    MetadataFieldOption.field_id.in_(option_backed_ids),
                    MetadataFieldOption.tenant_id == version.tenant_id,
                    MetadataFieldOption.is_deleted.is_(False),
                    MetadataFieldOption.status == "active",
                )
                .order_by(MetadataFieldOption.field_id, MetadataFieldOption.display_order)
            ).all()
            for option in option_rows:
                options_by_field_id.setdefault(option.field_id, []).append(option.option_value)

        specs: list[FieldSpec] = []
        unresolved: list[str] = []
        for key in keys:  # caller's order, not the schema's
            row = by_key.get(key)
            if row is None:
                unresolved.append(key)
                continue
            specs.append(
                FieldSpec(
                    field_key=row.field_key,
                    data_type=row.data_type,
                    options=options_by_field_id.get(row.field_id) if row.data_type in OPTION_BACKED_DATA_TYPES else None,
                    is_mandatory=bool(row.is_mandatory),
                    ocr_extractable=bool(row.is_ocr_extractable),
                )
            )
        if unresolved:
            # Not fatal: extract what we can and say plainly what was
            # dropped. A key can legitimately vanish here if the document
            # type's schema was edited between upload and processing.
            logger.warning(
                "version %s: %d of %d requested field(s) are not in document type %s's current schema: %s",
                version.version_id, len(unresolved), len(keys), document_type_id, ", ".join(unresolved),
            )
        return specs

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

    def describe_unclaimable(self, version_id: uuid.UUID) -> str:
        """Why `claim_job` returned None, in words, for the ack-and-skip log
        line. Diagnostic only -- never control flow.
        """
        now = datetime.now(timezone.utc)
        row = self.session.execute(
            select(ProcessingJob.status, ProcessingJob.lease_expires_at, ProcessingJob.next_attempt_at)
            .where(
                ProcessingJob.reference_type == "document_versions",
                ProcessingJob.reference_id == version_id,
                ProcessingJob.job_type == "ocr",
                ProcessingJob.status.in_(["queued", "processing"]),
            )
        ).first()
        if row is None:
            return "no queued/processing job row exists for this version"
        status, lease_expires_at, next_attempt_at = row
        if next_attempt_at is not None and _as_utc(next_attempt_at) > now:
            return f"retry backoff has not elapsed (next_attempt_at={next_attempt_at})"
        if status == "processing":
            return f"another worker holds a live lease (expires {lease_expires_at})"
        return f"job is {status!r} but did not match the claim predicate"

    def get_job(self, job_id: uuid.UUID) -> ProcessingJob:
        job = self.session.get(ProcessingJob, job_id)
        if job is None:
            raise VersionNotFound(f"processing_jobs row {job_id}")
        return job

    @staticmethod
    def _reapable(now: datetime, leaseless_grace_seconds: int):
        """The one definition of "this 'processing' row is the reaper's to
        take", shared by the scan and by both CAS updates that act on its
        results. They must agree exactly, or the scan hands the reaper rows
        its own UPDATE can never match and the reaper spins on them every
        tick.

        Two cases:

        1. **A real expired lease** -- the ordinary one.
        2. **No lease at all**, on a row older than the lease duration.
           `lease_expires_at IS NULL` makes case 1's comparison NULL, so
           such a row was invisible to the reaper *forever* and sat in
           'processing' holding V36's partial unique index slot for its
           version. V37's CHECK constraint now forbids writing that shape
           in the first place, and `claim_job` (the only production writer
           of `status='processing'`) always set both columns together
           anyway -- this arm is defense in depth for a row that predates
           the constraint or arrives by some future path, not a case that
           should occur.

        `started_at` is the right clock for case 2 (it is what a claim
        sets), but a row with no lease plausibly has no `started_at`
        either -- all four found in the local database had both NULL. So
        it falls back to `created_at`, which is NOT NULL with a DB default
        and therefore guarantees such a row can never be invisible again.
        """
        leaseless_cutoff = now - timedelta(seconds=leaseless_grace_seconds)
        return or_(
            ProcessingJob.lease_expires_at < now,
            and_(
                ProcessingJob.lease_expires_at.is_(None),
                func.coalesce(ProcessingJob.started_at, ProcessingJob.created_at) < leaseless_cutoff,
            ),
        )

    def find_expired_leases(self, *, leaseless_grace_seconds: int, limit: int = 50) -> list[ProcessingJob]:
        """Reaper scan (repository.py's own `ix_processing_jobs_lease_reaper`
        index, V36) -- every 'processing' row the reaper may take, per
        `_reapable`. A crashed/hung worker never comes back to move these
        out of 'processing' on its own.
        """
        now = datetime.now(timezone.utc)
        rows = (
            self.session.execute(
                select(ProcessingJob)
                .where(
                    ProcessingJob.job_type == "ocr",
                    ProcessingJob.status == "processing",
                    self._reapable(now, leaseless_grace_seconds),
                )
                .limit(limit)
            )
            .scalars()
            .all()
        )
        return list(rows)

    @staticmethod
    def is_leaseless(job: ProcessingJob) -> bool:
        """True for the case-2 rows above, so the reaper can log them
        distinctly -- a missing lease is a different (and more alarming)
        thing than an expired one, and worth saying out loud rather than
        quietly reaping.
        """
        return job.status == "processing" and job.lease_expires_at is None

    def record_death_cause(self, *, version_id: uuid.UUID, error: str) -> bool:
        """Write WHY a pool worker died, from the PARENT, without touching
        the job's status or its lease.

        This exists because of a real hole. When a pool worker is
        OOM-killed, or the outer pebble ceiling fires, the worker never
        gets to write anything -- so the row was left `processing` with a
        live lease and *no `last_error` at all*, and the reaper, seeing no
        cause, retried it as though it were a transient crash. For a
        document that is simply too large or too slow that retry can never
        succeed: the 50-upload run at 4 MP spent ~40 of its 60 minutes on
        one 35-page scan being retried five times.

        Deliberately does NOT change `status` or clear the lease. The
        reaper still owns reclaiming the row -- this only leaves it the
        evidence it needs to decide how many times that is worth doing.
        Returns False if the row is no longer `processing` (a worker that
        came back just in time, or another reaper got there first), which
        is not an error.
        """
        result = self.session.execute(
            update(ProcessingJob)
            .where(
                ProcessingJob.job_type == "ocr",
                ProcessingJob.reference_type == "document_versions",
                ProcessingJob.reference_id == version_id,
                ProcessingJob.status == "processing",
            )
            .values(last_error=error[:4000])
        )
        return bool(result.rowcount)

    def reap_requeue(self, *, job_id: uuid.UUID, backoff_seconds: int, leaseless_grace_seconds: int) -> bool:
        """CAS: moves one reapable job back to 'queued' with its backoff
        window set. Guarded by the same `_reapable` predicate the scan
        used, so this is safe under multiple concurrent ocr-worker
        instances each running their own reaper loop -- at most one wins
        per row, with no additional locking primitive needed. Returns False
        if another reaper (or a worker that came back just in time) already
        moved this row.

        **Deliberately emits no event.** It used to insert the
        `document.version.ocr.requested` outbox row right here, in the same
        transaction -- and that combination silently dropped every single
        retry. Verified live during Phase 1 close-out:

            05:23:59  Reaper requeued job ... attempts=1/5, backoff=5s
            05:24:01  Version ...: no claimable job; ack-and-skip

        OutboxRelay polls every 2s, so the event always reached the
        consumer *inside* the backoff window; `claim_job`'s own
        `next_attempt_at <= now` guard then correctly refused the claim,
        the consumer acked and skipped -- and nothing ever published
        again. The job sat in 'queued' with an elapsed `next_attempt_at`
        forever (`find_expired_leases` only looks at 'processing'), and its
        version never left 'pending'. The larger the backoff, the more
        certain the drop, so the production 30s backoff was worse than the
        5s one this was caught with.

        `publish_due_retry` below is the other half: the event is emitted
        on a later reaper tick, once the backoff has actually elapsed.
        """
        now = datetime.now(timezone.utc)
        stmt = (
            update(ProcessingJob)
            .where(
                ProcessingJob.job_id == job_id,
                ProcessingJob.status == "processing",
                self._reapable(now, leaseless_grace_seconds),
            )
            .values(status="queued", next_attempt_at=now + timedelta(seconds=backoff_seconds), lease_expires_at=None)
            .execution_options(synchronize_session=False)
        )
        result = self.session.execute(stmt)
        self.session.flush()
        return result.rowcount == 1

    def find_due_retries(self, *, limit: int = 50) -> list[ProcessingJob]:
        """Jobs the reaper has already requeued whose backoff has now
        elapsed, and which therefore need their `.requested` event emitted.

        `next_attempt_at IS NOT NULL` is what distinguishes these from an
        ordinary 'queued' row dms-core just inserted -- dms-core publishes
        its own event in the same transaction and never sets this column,
        so a fresh upload is never picked up here and never double-fired.
        """
        now = datetime.now(timezone.utc)
        rows = (
            self.session.execute(
                select(ProcessingJob)
                .where(
                    ProcessingJob.job_type == "ocr",
                    ProcessingJob.status == "queued",
                    ProcessingJob.next_attempt_at.is_not(None),
                    ProcessingJob.next_attempt_at <= now,
                )
                .limit(limit)
            )
            .scalars()
            .all()
        )
        return list(rows)

    def publish_due_retry(self, *, job_id: uuid.UUID, tenant_id: uuid.UUID, version_id: uuid.UUID, event_payload: dict) -> bool:
        """CAS: clears the elapsed backoff and, in the SAME transaction,
        inserts the `document.version.ocr.requested` outbox event (never
        publishes to RabbitMQ directly -- same outbox pattern as
        `.completed`/`.failed`).

        Clearing `next_attempt_at` is both the claim gate opening and the
        de-duplication key: the UPDATE only matches while it is still set,
        so two racing reapers (or two ticks) can never emit the event
        twice, and `claim_job`'s `next_attempt_at <= now` check passes
        immediately afterwards.
        """
        now = datetime.now(timezone.utc)
        stmt = (
            update(ProcessingJob)
            .where(
                ProcessingJob.job_id == job_id,
                ProcessingJob.status == "queued",
                ProcessingJob.next_attempt_at.is_not(None),
                ProcessingJob.next_attempt_at <= now,
            )
            .values(next_attempt_at=None)
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

    def reap_dead_letter(
        self, *, job_id: uuid.UUID, version: DocumentVersion, error: str, event_payload: dict, leaseless_grace_seconds: int
    ) -> bool:
        """CAS: moves one reapable job to 'dead_letter' after it has
        exhausted max_delivery_attempts, sets document_versions.ocr_status
        ='failed', and -- in the SAME transaction -- inserts a
        `document.version.ocr.failed` outbox event. This is what actually
        satisfies "zero stuck processing versions": nothing else would ever
        move this version out of 'processing' if the worker that held the
        lease crashed (e.g. an OOM kill) without ever coming back.

        Guarded by the same `_reapable` predicate the scan used -- if this
        narrowed it (as it did before, to `lease_expires_at < now`), a
        leaseless row would be returned by the scan on every tick and this
        UPDATE would match nothing, forever.
        """
        now = datetime.now(timezone.utc)
        stmt = (
            update(ProcessingJob)
            .where(
                ProcessingJob.job_id == job_id,
                ProcessingJob.status == "processing",
                self._reapable(now, leaseless_grace_seconds),
            )
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
        review: dict | None = None,
        page_count: int | None = None,
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
        # Phase 4b: the queue reads this column, not the MinIO artifact.
        # Always written, including the false case -- a re-run that FIXES
        # a document must take it out of the queue, and leaving the old
        # true behind would keep it there forever.
        version.requires_review = bool((review or {}).get("requires_review"))
        version.review_reasons = review or None
        # Phase 4c. Only a full-document run passes this; a zone or
        # rubber-band run visits a subset of pages and leaves the real
        # count alone rather than shrinking it to what it happened to
        # look at.
        if page_count is not None:
            version.page_count = page_count

        job.status = "completed"
        # A job that failed once and then succeeded must not keep the old
        # error. Two things go wrong if it does, and the second is not
        # cosmetic: a `completed` row reading "WORKER_CRASH: pool worker
        # died" is simply false to anyone inspecting it, AND the reaper
        # reads `last_error` to choose a retry budget -- so a later rerun
        # that died with no recorded cause would inherit this stale one,
        # and the "OOM-killed twice" rule would fire on a crash that
        # already resolved. Observed live: version 32520b6a completed on
        # attempt 2 still carrying attempt 1's WORKER_CRASH.
        job.last_error = None
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
                existing.raw_value = result.raw_value
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
                        raw_value=result.raw_value,
                        confidence=result.confidence,
                        status=result.status,
                        source_page=result.page,
                        source_bbox=bbox,
                    )
                )
        self.session.flush()

    def replace_line_items(self, *, tenant_id: uuid.UUID, document_id: uuid.UUID, version_id: uuid.UUID, items) -> int:
        """This version's line items, replacing whatever was there.

        Delete-then-insert rather than an upsert keyed on line_number,
        because a re-run can legitimately produce a DIFFERENT NUMBER of
        lines -- a page that previously failed its table pass now reads 7
        rows where it read 0, or a better routing decision merges two
        wrapped descriptions into one line. An upsert would leave the
        surplus rows from the longer previous run behind, silently
        inventing lines that the current read does not support.

        Runs in the caller's transaction, so the delete and the inserts
        commit with the rest of the outcome -- there is no window where a
        version has no line items because a re-run was interrupted.
        """
        self.session.query(DocumentOcrLineItem).filter(
            DocumentOcrLineItem.version_id == version_id
        ).delete(synchronize_session=False)

        for item in items or []:
            self.session.add(
                DocumentOcrLineItem(
                    tenant_id=tenant_id,
                    document_id=document_id,
                    version_id=version_id,
                    line_number=item.line_number,
                    description=item.description,
                    quantity=item.quantity,
                    unit_price=item.unit_price,
                    line_total=item.line_total,
                    raw_cells=item.raw_cells or None,
                    confidence=item.confidence,
                    source_page=item.page,
                    source_bbox=list(item.bbox) if item.bbox else None,
                )
            )
        self.session.flush()
        return len(items or [])

    def upsert_rendition(self, *, tenant_id: uuid.UUID, version_id: uuid.UUID, rendition_type: str,
                         storage_object_key: str, status: str = "completed") -> None:
        """Record one rendition for this version, replacing any previous
        one of the same type (V40's unique constraint).

        Update-in-place rather than delete-and-insert so the
        `rendition_id` is stable across re-runs -- anything that has
        linked to a rendition by id keeps resolving, which delete-insert
        would silently break.
        """
        existing = self.session.execute(
            select(Rendition).where(
                Rendition.version_id == version_id,
                Rendition.rendition_type == rendition_type,
            )
        ).scalar_one_or_none()
        if existing is not None:
            existing.storage_object_key = storage_object_key
            existing.status = status
        else:
            self.session.add(
                Rendition(
                    tenant_id=tenant_id,
                    version_id=version_id,
                    rendition_type=rendition_type,
                    storage_object_key=storage_object_key,
                    status=status,
                )
            )
        self.session.flush()

    def requeue_after_failure(self, *, job: ProcessingJob, error: str, backoff_seconds: int) -> None:
        """A failure the retry policy says is worth another attempt: back
        to 'queued' with a backoff, instead of terminal 'failed'.

        This closes a real asymmetry. The retry policy
        (`OCR_RETRY_ATTEMPTS_BY_CODE`) was only ever consulted by the
        reaper, which sees a job whose worker *died*. A worker that
        survives and records its own failure went straight to terminal --
        so the identical cause got a different number of attempts
        depending only on whether the process lived through it. TIMEOUT is
        the case that matters: `TIMEOUT=1` in the policy, but an in-worker
        deadline produced zero retries while the pool ceiling produced one.

        **Emits no event, deliberately** -- the same rule, and for the same
        measured reason, as `reap_requeue` above: OutboxRelay polls every
        2s, so an event published here always arrives inside the backoff
        window, `claim_job`'s `next_attempt_at <= now` guard refuses it,
        the consumer acks and skips, and nothing ever publishes again. The
        reaper's `publish_due_retry` is what re-requests this job, on a
        later tick, once the backoff has actually elapsed.

        The version is left non-terminal and untouched, matching what a
        reaper requeue leaves behind -- `claim_job` sets it back to
        'processing' on the next attempt. Writing 'failed' here would show
        the user a terminal failure for a document that is about to be
        retried.
        """
        now = datetime.now(timezone.utc)
        job.status = "queued"
        job.last_error = error[:4000]
        job.next_attempt_at = now + timedelta(seconds=backoff_seconds)
        job.lease_expires_at = None
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
