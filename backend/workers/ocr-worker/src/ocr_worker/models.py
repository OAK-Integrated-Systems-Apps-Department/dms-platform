"""SQLAlchemy models for the tables ocr-worker reads/writes.

These map onto tables owned by dms-core's Flyway migrations (docs/database/
V4, V5, V2) -- this service does not run migrations of its own against them.
Only the columns ocr-worker actually touches are declared; anything else on
the real table is simply invisible to this model, which is safe because
SQLAlchemy never does `SELECT *` / `INSERT` of undeclared columns.
"""
from __future__ import annotations

import uuid
from datetime import datetime, timezone

from sqlalchemy import DateTime, ForeignKey, Numeric, String, Text, JSON, Uuid
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column

# sqlalchemy.Uuid (dialect-agnostic, added in 2.0) rather than
# postgresql.dialects.UUID: renders as native uuid on Postgres in
# production but also works against SQLite in tests, so repository.py's
# transactional writes can be exercised against a real (in-memory) engine
# instead of only mocks.
UUID = Uuid


class Base(DeclarativeBase):
    pass


class DocumentType(Base):
    __tablename__ = "document_types"

    # docs/database/V11's own document_types shape (document_type_name) was
    # superseded by dms-core's real Metadata & Document-Type Management
    # module (type_code/type_name, plus other columns this service never
    # reads) -- only the two columns actually used here are mapped.
    document_type_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True)
    tenant_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True))
    type_name: Mapped[str] = mapped_column(String(150))
    # Which document_type_fields rows make up the CURRENT schema -- the
    # table keeps every historical schema_version's mapping rows side by
    # side, so resolving a field set without this pin would mix versions
    # (dms-core's DocumentTypeFieldServiceImpl#getSchema filters on exactly
    # this). Defaulted so test fixtures that predate it keep working.
    current_schema_version: Mapped[int] = mapped_column(default=1)


class Document(Base):
    __tablename__ = "documents"

    document_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True)
    tenant_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True))
    document_type_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("document_types.document_type_id"))
    title: Mapped[str] = mapped_column(String(500))


class DocumentVersion(Base):
    __tablename__ = "document_versions"

    version_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True)
    document_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("documents.document_id"))
    tenant_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True))
    storage_object_key: Mapped[str] = mapped_column(String(1000))
    original_filename: Mapped[str] = mapped_column(String(500))
    mime_type: Mapped[str] = mapped_column(String(255))
    scan_status: Mapped[str] = mapped_column(String(30))
    ocr_status: Mapped[str] = mapped_column(String(30))
    # Phase 4b (V41). The review-queue decision, persisted beside
    # ocr_status because it answers the same kind of question: what state
    # is this version in. Written by this worker in the same transaction
    # as the rest of the outcome.
    requires_review: Mapped[bool] = mapped_column(default=False)
    review_reasons: Mapped[dict | None] = mapped_column(JSON, nullable=True)
    # Phase 4c: the review screen offers a page selector before it has
    # fetched any page. NULL until OCR has opened the file -- including
    # for a document that failed before it could be opened, which is
    # honest rather than guessing 1.
    page_count: Mapped[int | None] = mapped_column(nullable=True)
    # V35__add_ocr_field_selection.sql -- the field_keys the uploader
    # actually asked OCR to extract, written by dms-core's
    # uploadDocument/prepareRerun. docs/events/ocr-scanning-events.md's
    # Phase 1 Decision 1 says the `.requested` event carries no resolved
    # field specs and ocr-worker re-reads this column at claim time
    # instead; `OcrRepository.resolve_requested_field_specs` is the half
    # of that decision that was missing until Phase 1 close-out.
    # jsonb on Postgres; SQLAlchemy's dialect-agnostic JSON keeps this
    # readable against SQLite in tests, same as OutboxEvent.payload.
    requested_fields: Mapped[list | None] = mapped_column(JSON, nullable=True, default=list)


class ProcessingJob(Base):
    __tablename__ = "processing_jobs"

    job_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    tenant_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True))
    job_type: Mapped[str] = mapped_column(String(30))
    reference_type: Mapped[str] = mapped_column(String(100))
    reference_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True))
    status: Mapped[str] = mapped_column(String(20), default="queued")
    attempts: Mapped[int] = mapped_column(default=0)
    last_error: Mapped[str | None] = mapped_column(Text, nullable=True)
    started_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    finished_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    correlation_id: Mapped[uuid.UUID | None] = mapped_column(UUID(as_uuid=True), nullable=True)
    # Only read by the reaper, as the guaranteed-non-NULL fallback clock for
    # a 'processing' row that somehow has neither a lease nor a started_at
    # (see OcrRepository._reapable). Given an application-level default for
    # the same reason OutboxEvent.created_at has one: the DB default is
    # `now()`, which Postgres freezes for a whole transaction.
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), default=lambda: datetime.now(timezone.utc)
    )
    # V36__ocr_job_leasing.sql -- see repository.py's claim_job/reaper
    # queries. Explicit DateTime(timezone=True): real bug found live in
    # this session's own tests -- without it, SQLite round-trips a naive
    # datetime, and comparing that against a Python-side
    # datetime.now(timezone.utc) in a bulk UPDATE's WHERE clause raises
    # "can't compare offset-naive and offset-aware datetimes" during
    # SQLAlchemy's session-sync evaluation. Postgres's TIMESTAMPTZ (the
    # real migration's column type) is unaffected either way, but this
    # keeps both dialects' round-trip behavior consistent and explicit.
    lease_expires_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)
    next_attempt_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)


class OcrOutput(Base):
    __tablename__ = "ocr_outputs"

    ocr_output_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    tenant_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True))
    version_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("document_versions.version_id"))
    extracted_text_ref: Mapped[str] = mapped_column(String(1000))
    language: Mapped[str | None] = mapped_column(String(30), nullable=True)
    status: Mapped[str] = mapped_column(String(30), default="pending")


class DocumentTypeOcrZone(Base):
    """OS-FR-003/OS-FR-005 template row -- docs/database/V11__create_ocr_templates.sql.
    One row per named zone; `field_name` is that zone's structured-field
    mapping, so this single table covers both zone OCR and structured
    field extraction rather than needing two.
    """

    __tablename__ = "document_type_ocr_zones"

    document_type_ocr_zone_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    tenant_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True))
    document_type_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("document_types.document_type_id"))
    zone_name: Mapped[str] = mapped_column(String(100))
    field_name: Mapped[str] = mapped_column(String(100))
    page_number: Mapped[int] = mapped_column(default=0)
    x0: Mapped[float] = mapped_column(Numeric(6, 5))
    y0: Mapped[float] = mapped_column(Numeric(6, 5))
    x1: Mapped[float] = mapped_column(Numeric(6, 5))
    y1: Mapped[float] = mapped_column(Numeric(6, 5))


class DocumentTypeSchema(Base):
    """dms-core's document-type -> field mapping (module 2.7), one row per
    published (document_type_id, schema_version). Read-only here, and only
    to resolve `document_versions.requested_fields` (a list of field_key
    strings) into the `data_type`/`options` the extraction dispatch needs --
    see `OcrRepository.resolve_requested_field_specs`.

    This replaces the old per-row `document_type_fields` junction table,
    which dms-core dropped in favour of one JSONB array column per schema
    version (dms-core's own `DocumentTypeSchema` entity/`FieldMapping`
    record). A publish never edits a row's `fields` in place -- it archives
    the previous version and inserts a new one -- so there is no per-field
    `is_deleted` here the way the old table had one: a field missing from
    the CURRENT version's array is the same fact the old table's
    `is_deleted=true` used to represent.

    Only the columns resolution actually uses are mapped; the real table
    also has `status`/`published_by`/`published_at`/`archived_at`.
    """

    __tablename__ = "document_type_schemas"

    schema_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    tenant_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True))
    document_type_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("document_types.document_type_id"))
    schema_version: Mapped[int] = mapped_column(default=1)
    # One JSON object per mapped field: {"mappingId", "fieldId", "mandatory",
    # "hidden", "requiresBackfill", "displayOrder", "fieldGroup",
    # "defaultValueOverride"} -- dms-core's FieldMapping record, serialized
    # with its Java field names verbatim (camelCase, not snake_case).
    fields: Mapped[list] = mapped_column(JSON, default=list)


class MetadataField(Base):
    """dms-core's field catalog. Read-only here -- `field_key` and
    `data_type` are what turn a requested field_key into a `FieldSpec`.
    """

    __tablename__ = "metadata_fields"

    field_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    tenant_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True))
    field_key: Mapped[str] = mapped_column(String(100))
    data_type: Mapped[str] = mapped_column(String(30))
    # Whether this field is something a document STATES, as opposed to
    # something this organisation assigns at filing time. Seeded by V44;
    # see `extraction.FieldSpec.ocr_extractable`.
    is_ocr_extractable: Mapped[bool] = mapped_column(default=True)
    is_deleted: Mapped[bool] = mapped_column(default=False)


class DocumentOcrCorrection(Base):
    """What a reviewer settled on, per field (V43). Read-only here.

    `corrected_value` is populated for an acceptance too -- equal to the
    original -- so "the true value of this field, as a human confirmed
    it" is one column with no branch on `action`. That is what makes this
    table usable as the source of the known-vendor list.
    """

    __tablename__ = "document_ocr_corrections"

    correction_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    tenant_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True))
    field_key: Mapped[str] = mapped_column(String(100))
    corrected_value: Mapped[str | None] = mapped_column(Text, nullable=True)


class MetadataFieldOption(Base):
    """Allowed values for a `dropdown`/`multi_select` field. Read-only
    here: extraction.py matches OCR'd text against this list rather than
    free-forming a value for an option-backed field.
    """

    __tablename__ = "metadata_field_options"

    option_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    tenant_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True))
    field_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("metadata_fields.field_id"))
    option_value: Mapped[str] = mapped_column(String(150))
    display_order: Mapped[int] = mapped_column(default=0)
    status: Mapped[str] = mapped_column(String(30), default="active")
    is_deleted: Mapped[bool] = mapped_column(default=False)


class DocumentOcrFieldResult(Base):
    """dms-core/.../V35__add_ocr_field_selection.sql. One row per
    (document_id, field_key) -- the latest selectable-field extraction
    attempt for that field, written directly by ocr-worker (same
    direct-Postgres pattern as document_versions.ocr_status/ocr_outputs
    above), read-only from dms-core's side. Deliberately separate from
    document_metadata_values (dms-core's own editable-value table): this
    row exists even for a `not_found` attempt that never became a stored
    value, so the upload page can show "not found" instead of just blank.
    """

    __tablename__ = "document_ocr_field_results"

    field_result_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    tenant_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True))
    document_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("documents.document_id"))
    version_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("document_versions.version_id"))
    field_key: Mapped[str] = mapped_column(String(100))
    # A multi_select field's value (tags/keywords) is stored here as a
    # JSON-encoded array string (e.g. '["policy","hr"]'), not a real list --
    # the column is TEXT. dms-core's reader must json-decode it back for a
    # multi_select field, same as it already treats a multi_select
    # document_metadata_values row as several single-value rows internally.
    value: Mapped[str | None] = mapped_column(Text, nullable=True)
    # The string as read off the page, before normalisation (V39). Equal
    # to `value` except for dates, where `value` is ISO -- see
    # extraction._finalize_result.
    raw_value: Mapped[str | None] = mapped_column(Text, nullable=True)
    confidence: Mapped[float | None] = mapped_column(Numeric(4, 3), nullable=True)
    status: Mapped[str] = mapped_column(String(20))  # "extracted" | "not_found" | "low_confidence"
    source_page: Mapped[int | None] = mapped_column(nullable=True)
    source_bbox: Mapped[list | None] = mapped_column(JSON, nullable=True)


class OutboxEvent(Base):
    __tablename__ = "outbox_events"

    outbox_event_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    tenant_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True))
    aggregate_type: Mapped[str] = mapped_column(String(100))
    aggregate_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True))
    event_type: Mapped[str] = mapped_column(String(150))
    # Real bug found live (docs/ocr/failure-analysis.md #1/#3): with no
    # Python-side default, this column fell through entirely to the DB's
    # own `DEFAULT now()` -- and Postgres's now()/CURRENT_TIMESTAMP is
    # frozen for a transaction's whole lifetime, so a row inserted near the
    # end of a long-held transaction (e.g. the old single-transaction
    # process_version, before the claim/run/commit split) got a created_at
    # matching the transaction's START, not its own real insert time.
    # Verified live: a 545s real job's outbox row showed created_at
    # matching processing_jobs.started_at, off by over 9 minutes from when
    # the row was actually written. The claim/run/commit split (pipeline.py)
    # is the primary fix (the commit transaction is short), but this
    # explicit application-level default removes the DB-transaction-
    # semantics footgun permanently, matching the pattern
    # ProcessingJob.started_at/finished_at already use (repository.py sets
    # those with datetime.now(timezone.utc) in Python, not SQL now()).
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc))
    payload: Mapped[dict] = mapped_column(JSON)
    status: Mapped[str] = mapped_column(String(20), default="pending")
    attempts: Mapped[int] = mapped_column(default=0)


class DocumentOcrLineItem(Base):
    """dms-core/.../V39__ocr_line_items_and_raw_values.sql.

    One row per invoice line per version. A re-run replaces this
    version's rows wholesale (see `OcrRepository.replace_line_items`)
    rather than accumulating, matching document_ocr_field_results' own
    current-state shape.

    A real table rather than a JSONB column because Phase 4's corrections
    need per-line identity -- see the migration's own DECISION comment for
    the full argument.
    """

    __tablename__ = "document_ocr_line_items"

    line_item_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    tenant_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True))
    document_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("documents.document_id"))
    version_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("document_versions.version_id"))
    line_number: Mapped[int] = mapped_column()
    description: Mapped[str | None] = mapped_column(Text, nullable=True)
    quantity: Mapped[float | None] = mapped_column(Numeric(18, 4), nullable=True)
    unit_price: Mapped[float | None] = mapped_column(Numeric(18, 4), nullable=True)
    line_total: Mapped[float | None] = mapped_column(Numeric(18, 4), nullable=True)
    raw_cells: Mapped[dict | None] = mapped_column(JSON, nullable=True)
    confidence: Mapped[float | None] = mapped_column(Numeric(4, 3), nullable=True)
    source_page: Mapped[int | None] = mapped_column(nullable=True)
    source_bbox: Mapped[list | None] = mapped_column(JSON, nullable=True)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc))
    updated_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True), nullable=True)


class Rendition(Base):
    """dms-core/.../V33__create_ingestion_processing.sql, extended by V40.

    ocr-worker writes exactly one row here, of type `searchable_pdf` --
    the original page raster plus an invisible OCR text layer (production
    prompt 3.11). Thumbnails and previews are somebody else's job.

    One row per (version_id, rendition_type), enforced by V40's unique
    constraint: a re-run replaces this version's rendition rather than
    appending another, so the table says what is true now.
    """

    __tablename__ = "renditions"

    RENDITION_TYPE_SEARCHABLE_PDF = "searchable_pdf"

    rendition_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    tenant_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True))
    version_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("document_versions.version_id"))
    rendition_type: Mapped[str] = mapped_column(String(30))
    storage_object_key: Mapped[str] = mapped_column(String(1000))
    status: Mapped[str] = mapped_column(String(30), default="pending")
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), default=lambda: datetime.now(timezone.utc))
