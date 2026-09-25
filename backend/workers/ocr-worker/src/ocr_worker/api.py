from __future__ import annotations

import uuid

from fastapi import Depends, FastAPI, Header, HTTPException
from fastapi.responses import JSONResponse
from pydantic import BaseModel, Field
from sqlalchemy import text

from .config import Settings
from .extraction import FieldSpec
from .models import DocumentVersion
from .pipeline import OcrPipeline, UnsupportedMimeType
from .repository import VersionNotEligible, VersionNotFound
from .structuring import NoOcrOutput, StructuredExtractionRunner


class RequestedField(BaseModel):
    """One field the caller (dms-core) wants extracted, with the field
    catalog metadata ocr-worker needs to interpret it -- dms-core owns
    that catalog, so it sends data_type/options explicitly rather than
    ocr-worker querying metadata_fields/metadata_field_options itself.
    """

    field_key: str
    data_type: str
    options: list[str] | None = None


class ZoneRequest(BaseModel):
    version_id: uuid.UUID
    zone_names: list[str] | None = Field(default=None, description="Subset of the document type's configured zones; omit for all")
    correlation_id: uuid.UUID | None = None


class RubberBandRequest(BaseModel):
    version_id: uuid.UUID
    page: int = 0
    x0: float
    y0: float
    x1: float
    y1: float
    correlation_id: uuid.UUID | None = None


class ProcessRequest(BaseModel):
    correlation_id: uuid.UUID | None = None
    # Selectable OCR field extraction: omitted (None) preserves the
    # original "extract everything the zone template/generic regex fallback
    # can find" behavior (scripts/dev/smoke_test_ocr.py and any other
    # caller that hasn't adopted this yet); present (even []) restricts
    # extraction to exactly these fields -- see extraction.py's
    # extract_requested_fields.
    requested_fields: list[RequestedField] | None = None


def _failure_response(outcome) -> dict:
    return {"status": "failed", "document_version_id": str(outcome.version_id), "error": outcome.error}


class StructuredRequest(BaseModel):
    # False (default): queue it and return 202 with the `pending` result --
    # a 7B model can take minutes. True: block and return the final result
    # (dev/testing, or a caller with a long enough timeout).
    wait: bool = False


def create_app(
    pipeline: OcrPipeline,
    session_factory,
    rabbitmq_ready_check,
    settings: Settings,
    structuring: StructuredExtractionRunner | None = None,
) -> FastAPI:
    app = FastAPI(title="ocr-worker", description="Oak DMS OCR & Scanning microservice (internal-only)")

    def require_internal_token(x_internal_token: str | None = Header(default=None)) -> None:
        """Defense in depth, not authentication: ocr-worker has no login
        of its own and is never reachable outside the docker network (no
        published host port -- see README "Auth & service exposure").
        dms-core is the only intended caller and is expected to pass
        tenant_id/user_id/document_version_id explicitly; this token just
        means a stray request on the internal network from something
        other than dms-core still gets rejected.
        """
        if x_internal_token != settings.internal_service_token:
            raise HTTPException(status_code=401, detail="missing or invalid X-Internal-Token")

    @app.get("/healthz")
    def healthz():
        return {"status": "ok"}

    @app.get("/readyz")
    def readyz():
        try:
            with session_factory() as session:
                session.execute(text("SELECT 1"))
        except Exception as exc:  # noqa: BLE001
            raise HTTPException(status_code=503, detail=f"database not ready: {exc}") from exc

        if not rabbitmq_ready_check():
            raise HTTPException(status_code=503, detail="rabbitmq consumer not connected")

        return {"status": "ready"}

    @app.post("/ocr/process/{version_id}", dependencies=[Depends(require_internal_token)])
    def process_version(version_id: uuid.UUID, body: ProcessRequest | None = None):
        """Dev-trigger endpoint: runs the exact same pipeline the queue
        consumer runs, synchronously, bypassing RabbitMQ entirely, and
        returns the full output JSON (see README's "Output contract")
        directly in the response body -- the primary way to exercise the
        pipeline while scan-worker doesn't exist (see docs/events/
        ocr-scanning-events.md) and inspect real output without a MinIO
        round-trip.
        """
        correlation_id = body.correlation_id if body else None
        requested_fields = (
            [FieldSpec(field_key=f.field_key, data_type=f.data_type, options=f.options) for f in body.requested_fields]
            if body and body.requested_fields is not None
            else None
        )
        try:
            outcome = pipeline.process_version(version_id, correlation_id=correlation_id, requested_fields=requested_fields)
        except VersionNotFound as exc:
            raise HTTPException(status_code=404, detail=str(exc)) from exc
        except VersionNotEligible as exc:
            raise HTTPException(status_code=409, detail=str(exc)) from exc
        return outcome.output if outcome.status == "completed" else _failure_response(outcome)

    @app.post("/ocr/zone", dependencies=[Depends(require_internal_token)])
    def ocr_zone(body: ZoneRequest):
        try:
            outcome = pipeline.process_zone_request(body.version_id, body.zone_names, correlation_id=body.correlation_id)
        except VersionNotFound as exc:
            raise HTTPException(status_code=404, detail=str(exc)) from exc
        except VersionNotEligible as exc:
            raise HTTPException(status_code=409, detail=str(exc)) from exc
        except (ValueError, UnsupportedMimeType) as exc:
            raise HTTPException(status_code=422, detail=str(exc)) from exc
        return outcome.output if outcome.status == "completed" else _failure_response(outcome)

    @app.post("/ocr/rubber-band", dependencies=[Depends(require_internal_token)])
    def ocr_rubber_band(body: RubberBandRequest):
        try:
            outcome = pipeline.process_rubber_band_request(
                body.version_id, body.page, (body.x0, body.y0, body.x1, body.y1), correlation_id=body.correlation_id
            )
        except VersionNotFound as exc:
            raise HTTPException(status_code=404, detail=str(exc)) from exc
        except VersionNotEligible as exc:
            raise HTTPException(status_code=409, detail=str(exc)) from exc
        except (ValueError, UnsupportedMimeType) as exc:
            # Covers the "ambiguous/degenerate rubber-band selection" alternate flow.
            raise HTTPException(status_code=422, detail=str(exc)) from exc
        return outcome.output if outcome.status == "completed" else _failure_response(outcome)

    # ------------------------------------------------------------------
    # Structured JSON extraction (local LLM) -- README "Structured JSON
    # extraction (local LLM)". Declared before /{version_id} so "health"
    # isn't parsed as a version id.
    # ------------------------------------------------------------------
    def _require_structuring() -> StructuredExtractionRunner:
        if structuring is None:
            raise HTTPException(status_code=503, detail="structured extraction is not configured in this process")
        return structuring

    def _tenant_of(version_id: uuid.UUID) -> uuid.UUID:
        with session_factory() as session:
            version = session.get(DocumentVersion, version_id)
            if version is None:
                raise HTTPException(status_code=404, detail=f"document version {version_id} not found")
            return version.tenant_id

    @app.get("/ocr/structured/health", dependencies=[Depends(require_internal_token)])
    def structured_health():
        """Model server reachability + whether LLM_MODEL is actually pulled."""
        return _require_structuring().health()

    @app.get("/ocr/structured/{version_id}", dependencies=[Depends(require_internal_token)])
    def get_structured(version_id: uuid.UUID):
        """Newest structured result for a version: status pending | completed
        | partial | failed | skipped, plus document_type/fields/tables/
        sections, warnings and error details."""
        runner = _require_structuring()
        result = runner.latest(tenant_id=_tenant_of(version_id), version_id=version_id)
        if result is None:
            raise HTTPException(status_code=404, detail=f"no structured result for version {version_id}")
        return result

    @app.post("/ocr/structured/{version_id}", dependencies=[Depends(require_internal_token)])
    def run_structured(version_id: uuid.UUID, body: StructuredRequest | None = None):
        """Re-structures the version's newest OCR output (no OCR re-run)."""
        runner = _require_structuring()
        if not runner.enabled:
            raise HTTPException(status_code=409, detail="structured extraction is disabled (LLM_EXTRACTION_MODE=off)")
        wait = body.wait if body else False
        try:
            result = runner.rerun(tenant_id=_tenant_of(version_id), version_id=version_id, wait=wait)
        except NoOcrOutput as exc:
            raise HTTPException(status_code=409, detail=str(exc)) from exc
        return JSONResponse(result, status_code=200 if wait else 202)

    return app
