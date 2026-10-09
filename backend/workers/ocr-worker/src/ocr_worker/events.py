from __future__ import annotations

import uuid
from datetime import datetime, timezone
from typing import Any

from .config import ROUTING_KEY_OCR_COMPLETED, ROUTING_KEY_OCR_FAILED, ROUTING_KEY_OCR_REQUESTED

SCHEMA_VERSION = 1


def build_ocr_requested_payload(
    *,
    tenant_id: uuid.UUID,
    document_id: uuid.UUID,
    version_id: uuid.UUID,
    correlation_id: uuid.UUID | None,
) -> dict[str, Any]:
    """No resolved field specs (Phase 1 plan Decision 1) -- ocr-worker
    re-queries dms-core's schema catalog directly at claim time. Used both
    by the real upload/rerun path (dms-core, not yet built) and by the
    reaper's own requeue path (repository.py's `reap_requeue`) -- a
    downstream consumer must handle a `.requested` event identically
    regardless of which one produced it.
    """
    return {
        "schema_version": SCHEMA_VERSION,
        "event_type": ROUTING_KEY_OCR_REQUESTED,
        "tenant_id": str(tenant_id),
        "document_id": str(document_id),
        "version_id": str(version_id),
        "correlation_id": str(correlation_id) if correlation_id else None,
        "occurred_at": datetime.now(timezone.utc).isoformat(),
    }


def build_ocr_completed_payload(
    *,
    tenant_id: uuid.UUID,
    document_id: uuid.UUID,
    version_id: uuid.UUID,
    ocr_output_id: uuid.UUID,
    extracted_text_ref: str,
    page_count: int,
    flags: dict[str, bool],
    correlation_id: uuid.UUID | None,
    attempt: int,
) -> dict[str, Any]:
    """Deliberately slim: the full pages/fields/text detail lives in the
    single stored artifact at extracted_text_ref (see the "Output contract"
    in README.md) -- the event just carries enough for a downstream
    consumer (e.g. a future indexing step, OS-FR-006) to know something
    happened and where to fetch the detail, not a copy of it.

    `attempt` is the claiming processing_jobs row's own `attempts` value
    at the time this outcome was recorded -- lets a downstream consumer
    (dms-core's OcrEventsConsumer) skip a stale event for an attempt the
    reaper has already superseded with a fresh retry, by comparing it
    against the job's CURRENT attempts rather than trusting delivery order.
    """
    return {
        "schema_version": SCHEMA_VERSION,
        "event_type": ROUTING_KEY_OCR_COMPLETED,
        "tenant_id": str(tenant_id),
        "document_id": str(document_id),
        "version_id": str(version_id),
        "ocr_output_id": str(ocr_output_id),
        "extracted_text_ref": extracted_text_ref,
        "page_count": page_count,
        "flags": flags,
        "correlation_id": str(correlation_id) if correlation_id else None,
        "attempt": attempt,
        "occurred_at": datetime.now(timezone.utc).isoformat(),
    }


def build_ocr_failed_payload(
    *,
    tenant_id: uuid.UUID,
    document_id: uuid.UUID,
    version_id: uuid.UUID,
    error: str,
    correlation_id: uuid.UUID | None,
    attempt: int,
) -> dict[str, Any]:
    return {
        "schema_version": SCHEMA_VERSION,
        "event_type": ROUTING_KEY_OCR_FAILED,
        "tenant_id": str(tenant_id),
        "document_id": str(document_id),
        "version_id": str(version_id),
        "error": error,
        "correlation_id": str(correlation_id) if correlation_id else None,
        "attempt": attempt,
        "occurred_at": datetime.now(timezone.utc).isoformat(),
    }
