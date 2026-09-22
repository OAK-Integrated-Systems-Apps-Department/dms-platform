from __future__ import annotations

import uuid

from fastapi import Depends, FastAPI, Header, HTTPException
from pydantic import BaseModel, Field
from sqlalchemy import text

from .config import Settings
from .extraction import FieldSpec
from .pipeline import OcrPipeline, UnsupportedMimeType
from .repository import VersionNotEligible, VersionNotFound


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


def create_app(pipeline: OcrPipeline, session_factory, rabbitmq_ready_check, settings: Settings) -> FastAPI:
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

    return app
