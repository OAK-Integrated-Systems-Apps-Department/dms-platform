"""SQLAlchemy models for the tables ocr-worker reads/writes.

These map onto tables owned by dms-core's Flyway migrations (docs/database/
V4, V5, V2) -- this service does not run migrations of its own against them.
Only the columns ocr-worker actually touches are declared; anything else on
the real table is simply invisible to this model, which is safe because
SQLAlchemy never does `SELECT *` / `INSERT` of undeclared columns.
"""
from __future__ import annotations

import uuid
from datetime import datetime

from sqlalchemy import Boolean, ForeignKey, Numeric, String, Text, JSON, Uuid
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
    started_at: Mapped[datetime | None] = mapped_column(nullable=True)
    finished_at: Mapped[datetime | None] = mapped_column(nullable=True)
    correlation_id: Mapped[uuid.UUID | None] = mapped_column(UUID(as_uuid=True), nullable=True)


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
    confidence: Mapped[float | None] = mapped_column(Numeric(4, 3), nullable=True)
    status: Mapped[str] = mapped_column(String(20))  # "extracted" | "not_found" | "low_confidence"
    source_page: Mapped[int | None] = mapped_column(nullable=True)
    source_bbox: Mapped[list | None] = mapped_column(JSON, nullable=True)
    # V40__create_metadata_field_label_aliases.sql: how the value was found
    # ("label_hint" | "field_name" | "zone" | "pattern" | "generated") or, on
    # a not_found row, what was searched for -- see extraction.py's
    # MATCH_BASIS_* constants. Mapping it makes every insert name the
    # column, so this worker needs V40 applied before it runs.
    match_basis: Mapped[str | None] = mapped_column(String(30), nullable=True)


class MetadataField(Base):
    """dms-core's field catalog (V28__create_metadata_document_type_tables.sql)
    -- read-only here, and only the columns get_label_vocabulary needs: the
    catalog label is the field-name fallback for a field with no label hints
    (see extraction.py's build_label_vocabulary). dms-core stays the owner of
    the catalog and still sends data_type/options in each request (api.py's
    RequestedField); this worker never writes to it.
    """

    __tablename__ = "metadata_fields"

    field_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    tenant_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True))
    field_key: Mapped[str] = mapped_column(String(100))
    label: Mapped[str] = mapped_column(String(150))
    is_deleted: Mapped[bool] = mapped_column(Boolean, default=False)


class MetadataFieldLabelAlias(Base):
    """V40__create_metadata_field_label_aliases.sql: one tenant-configured
    OCR label hint per row, read-only here (dms-core's field and
    document-type-field screens own it). `document_type_id` NULL = a
    catalog-level ("global") hint that applies wherever the field is mapped;
    non-null = only for documents of that document type. `display_order` is
    match priority within (field, scope), lower first. The real table's
    foreign keys are composite (tenant_id, field_id)/(tenant_id,
    document_type_id); the single-column ForeignKeys here only exist so
    tests' SQLite create_all has something to relate -- this service never
    runs DDL against Postgres.
    """

    __tablename__ = "metadata_field_label_aliases"

    label_alias_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    tenant_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True))
    field_id: Mapped[uuid.UUID] = mapped_column(ForeignKey("metadata_fields.field_id"))
    document_type_id: Mapped[uuid.UUID | None] = mapped_column(ForeignKey("document_types.document_type_id"), nullable=True)
    alias_text: Mapped[str] = mapped_column(String(100))
    display_order: Mapped[int] = mapped_column()


class OutboxEvent(Base):
    __tablename__ = "outbox_events"

    outbox_event_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    tenant_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True))
    aggregate_type: Mapped[str] = mapped_column(String(100))
    aggregate_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True))
    event_type: Mapped[str] = mapped_column(String(150))
    payload: Mapped[dict] = mapped_column(JSON)
    status: Mapped[str] = mapped_column(String(20), default="pending")
    attempts: Mapped[int] = mapped_column(default=0)
