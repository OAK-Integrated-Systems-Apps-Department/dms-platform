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

from sqlalchemy import ForeignKey, Numeric, String, Text, JSON, Uuid
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

    document_type_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True), primary_key=True)
    tenant_id: Mapped[uuid.UUID] = mapped_column(UUID(as_uuid=True))
    document_type_name: Mapped[str] = mapped_column(String(255))


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
