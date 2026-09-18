from __future__ import annotations

import uuid
from dataclasses import dataclass
from datetime import datetime, timezone

from sqlalchemy import select
from sqlalchemy.orm import Session

from .config import ROUTING_KEY_OCR_COMPLETED, ROUTING_KEY_OCR_FAILED
from .models import Document, DocumentType, DocumentTypeOcrZone, DocumentVersion, OcrOutput, OutboxEvent, ProcessingJob
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
            document_type_name=document_type.document_type_name,
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
