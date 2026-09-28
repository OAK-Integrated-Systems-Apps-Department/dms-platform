from __future__ import annotations

import json
import uuid
from dataclasses import dataclass
from datetime import datetime, timezone

from sqlalchemy import and_, select
from sqlalchemy.orm import Session

from .config import ROUTING_KEY_OCR_COMPLETED, ROUTING_KEY_OCR_FAILED
from .extraction import LabelAliasRow, LabelFieldRow, LabelVocabulary, RequestedFieldResult, build_label_vocabulary
from .models import (
    Document,
    DocumentOcrFieldResult,
    DocumentType,
    DocumentTypeOcrZone,
    DocumentVersion,
    MetadataField,
    MetadataFieldLabelAlias,
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

    def get_label_vocabulary(
        self, *, tenant_id: uuid.UUID, document_type_id: uuid.UUID, field_keys: list[str]
    ) -> LabelVocabulary:
        """The tenant's OCR label hints for one document, resolved into an
        immutable LabelVocabulary -- ONE query: every non-deleted catalog
        field of this tenant (any status -- an inactive field's hints are
        still a real label that can sit next to an active field's value, so
        they still belong in the stop set), LEFT JOINed to its hint rows, so
        a field with no hints still comes back (its label is the field-name
        fallback). tenant_id is filtered explicitly on both tables even
        though the composite foreign key already implies it for the hints --
        this worker has no request-scoped tenant context of its own to lean
        on, so every read names its tenant. Hints scoped to other document
        types come back too and are dropped by build_label_vocabulary,
        which owns every resolution rule so they're unit-tested without a
        database; this method only fetches and reshapes rows.
        """
        rows = self.session.execute(
            select(
                MetadataField.field_id,
                MetadataField.field_key,
                MetadataField.label,
                MetadataFieldLabelAlias.document_type_id,
                MetadataFieldLabelAlias.alias_text,
                MetadataFieldLabelAlias.display_order,
            )
            .select_from(MetadataField)
            .outerjoin(
                MetadataFieldLabelAlias,
                and_(
                    MetadataFieldLabelAlias.field_id == MetadataField.field_id,
                    MetadataFieldLabelAlias.tenant_id == tenant_id,
                ),
            )
            .where(MetadataField.tenant_id == tenant_id, MetadataField.is_deleted.is_(False))
            .order_by(MetadataField.field_key, MetadataFieldLabelAlias.display_order)
        ).all()

        field_rows: dict[uuid.UUID, LabelFieldRow] = {}
        alias_rows: list[LabelAliasRow] = []
        for row in rows:
            field_rows.setdefault(row.field_id, LabelFieldRow(row.field_id, row.field_key, row.label))
            if row.alias_text is not None:
                alias_rows.append(LabelAliasRow(row.field_id, row.document_type_id, row.alias_text, row.display_order))

        return build_label_vocabulary(
            document_type_id=document_type_id,
            field_rows=field_rows.values(),
            alias_rows=alias_rows,
            requested_keys=field_keys,
        )

    def start_job(self, *, tenant_id: uuid.UUID, version_id: uuid.UUID, correlation_id: uuid.UUID | None) -> ProcessingJob:
        job = ProcessingJob(
            tenant_id=tenant_id,
            job_type="ocr",
            reference_type="document_versions",
            reference_id=version_id,
            status="processing",
            attempts=1,
            started_at=datetime.now(timezone.utc),
            correlation_id=correlation_id,
        )
        self.session.add(job)
        self.session.flush()
        return job

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
                existing.match_basis = result.match_basis
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
                        match_basis=result.match_basis,
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
