from __future__ import annotations

import hashlib
import uuid
from pathlib import Path

from fastapi import Depends, FastAPI, Header, HTTPException, Response
from pebble import ProcessExpired
from pydantic import BaseModel, Field
from sqlalchemy import text

from .config import OCR_TIMEOUT_EXIT_CODE, Settings
from .errors import UnsupportedFormatError
from .extraction import FieldSpec
from .repository import VersionNotEligible, VersionNotFound
from .worker_pool import OcrProcessPool


def source_fingerprint() -> dict[str, str]:
    """md5 of every .py in this package, keyed by name.

    Read from THIS process's own package directory, so it reports what is
    actually loaded rather than what some path suggests should be.
    """
    root = Path(__file__).resolve().parent
    out: dict[str, str] = {}
    for path in sorted(root.rglob("*.py")):
        if "__pycache__" in path.parts:
            continue
        try:
            out[path.relative_to(root).as_posix()] = hashlib.md5(path.read_bytes()).hexdigest()
        except OSError:
            continue
    return out


# Computed once at import: the files cannot change under a running
# process, and a readiness-adjacent endpoint should not do file I/O.
SOURCE_FINGERPRINT = source_fingerprint()


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


def create_app(pool: OcrProcessPool, session_factory, rabbitmq_ready_check, settings: Settings) -> FastAPI:
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

    @app.get("/fingerprint", dependencies=[Depends(require_internal_token)])
    def fingerprint():
        """md5 of every source file this process is actually running.

        Exists so a verification harness can refuse to measure a stale
        container. Three measured runs have already been invalidated by an
        image older than the source, none of them visibly -- and the
        obvious check (`docker exec` + `md5sum`) is unavailable from
        inside a container, which is where every harness here runs. An
        HTTP endpoint needs no docker socket and no docker CLI.

        Computed once at import (see `source_fingerprint`), not per
        request: the files cannot change under a running process, and a
        readiness-adjacent endpoint should not do filesystem work.

        **Authenticated like every other /ocr route, and off by default in
        production.** It is a development aid, not an operational one: it
        enumerates this service's internal module names, which is exactly
        the sort of thing that belongs behind the same door as everything
        else and is worth not serving at all where nothing will ask for
        it. `OCR_FINGERPRINT_ENABLED=false` returns 404 -- not 403, so a
        disabled deployment does not even confirm the endpoint exists.
        """
        if not settings.ocr_fingerprint_enabled:
            raise HTTPException(status_code=404, detail="Not Found")
        return {"source": SOURCE_FINGERPRINT, "files": len(SOURCE_FINGERPRINT)}

    @app.get("/readyz")
    def readyz():
        try:
            with session_factory() as session:
                session.execute(text("SELECT 1"))
        except Exception as exc:  # noqa: BLE001
            raise HTTPException(status_code=503, detail=f"database not ready: {exc}") from exc

        if not rabbitmq_ready_check():
            raise HTTPException(status_code=503, detail="rabbitmq consumer not connected")

        # Gated on every pool worker having actually finished constructing
        # + warming up its own PaddleOCR/Docling models (worker_pool.py) --
        # not just DB+RabbitMQ connectivity. Dockerfile's HEALTHCHECK points
        # at this endpoint (not /healthz) specifically so docker-compose's
        # service_healthy dependency, which dms-core waits on, isn't
        # satisfied before models are actually loaded (verified live that
        # /healthz always returns ok regardless of warm-up state).
        if not pool.is_ready():
            raise HTTPException(status_code=503, detail="ocr process pool still warming up")

        return {"status": "ready"}

    @app.post("/ocr/process/{version_id}", dependencies=[Depends(require_internal_token)])
    def process_version(version_id: uuid.UUID, body: ProcessRequest | None = None):
        """Dev-trigger endpoint: runs the exact same pipeline the queue
        consumer runs, synchronously, bypassing RabbitMQ entirely, and
        returns the full output JSON (see README's "Output contract")
        directly in the response body -- the primary way to exercise the
        pipeline while scan-worker doesn't exist (see docs/events/
        ocr-scanning-events.md) and inspect real output without a storage
        round-trip.
        """
        correlation_id = body.correlation_id if body else None
        requested_fields = (
            [FieldSpec(field_key=f.field_key, data_type=f.data_type, options=f.options) for f in body.requested_fields]
            if body and body.requested_fields is not None
            else None
        )
        future = pool.schedule_process_version(version_id, correlation_id=correlation_id, requested_fields=requested_fields)
        try:
            outcome = future.result()
        except VersionNotFound as exc:
            raise HTTPException(status_code=404, detail=str(exc)) from exc
        except VersionNotEligible as exc:
            raise HTTPException(status_code=409, detail=str(exc)) from exc
        except TimeoutError as exc:
            # The outer pebble ceiling (worker_pool.py's task_timeout_s):
            # pebble already killed and replaced the specific pool worker,
            # and nothing recorded a failure, so the claimed job row is
            # left 'processing' for the reaper to reclaim -- the caller
            # gets a clear timeout now rather than hanging until its own
            # client-side timeout.
            raise HTTPException(status_code=504, detail=f"OCR processing timed out for version {version_id}") from exc
        except ProcessExpired as exc:
            if exc.exitcode == OCR_TIMEOUT_EXIT_CODE:
                # The pool worker's own per-document watchdog (deadline.py)
                # recorded TIMEOUT and terminated itself -- same 504 the
                # caller would have got above, but the job row is already
                # 'failed' rather than waiting on the reaper.
                raise HTTPException(status_code=504, detail=f"OCR processing timed out for version {version_id}") from exc
            raise HTTPException(
                status_code=500,
                detail=f"OCR pool worker died (exitcode={exc.exitcode}) processing version {version_id}",
            ) from exc
        return outcome.output if outcome.status == "completed" else _failure_response(outcome)

    @app.post("/ocr/zone", dependencies=[Depends(require_internal_token)])
    def ocr_zone(body: ZoneRequest):
        future = pool.schedule_process_zone(body.version_id, body.zone_names, correlation_id=body.correlation_id)
        try:
            outcome = future.result()
        except VersionNotFound as exc:
            raise HTTPException(status_code=404, detail=str(exc)) from exc
        except VersionNotEligible as exc:
            raise HTTPException(status_code=409, detail=str(exc)) from exc
        except (ValueError, UnsupportedFormatError) as exc:
            raise HTTPException(status_code=422, detail=str(exc)) from exc
        except TimeoutError as exc:
            raise HTTPException(status_code=504, detail=f"OCR processing timed out for version {body.version_id}") from exc
        return outcome.output if outcome.status == "completed" else _failure_response(outcome)

    @app.post("/ocr/rubber-band", dependencies=[Depends(require_internal_token)])
    def ocr_rubber_band(body: RubberBandRequest):
        future = pool.schedule_process_rubber_band(
            body.version_id, body.page, (body.x0, body.y0, body.x1, body.y1), correlation_id=body.correlation_id
        )
        try:
            outcome = future.result()
        except VersionNotFound as exc:
            raise HTTPException(status_code=404, detail=str(exc)) from exc
        except VersionNotEligible as exc:
            raise HTTPException(status_code=409, detail=str(exc)) from exc
        except (ValueError, UnsupportedFormatError) as exc:
            # Covers the "ambiguous/degenerate rubber-band selection" alternate flow.
            raise HTTPException(status_code=422, detail=str(exc)) from exc
        except TimeoutError as exc:
            raise HTTPException(status_code=504, detail=f"OCR processing timed out for version {body.version_id}") from exc
        return outcome.output if outcome.status == "completed" else _failure_response(outcome)

    @app.get("/ocr/page-image/{version_id}", dependencies=[Depends(require_internal_token)])
    def ocr_page_image(
        version_id: uuid.UUID,
        page: int = 0,
        dpi: int | None = None,
        x_ocr_job_token: str | None = Header(default=None),
    ):
        """One page of a document as a PNG, for the review screen.

        Read-only: no job is claimed, nothing is written, no event is
        published. Internal-only like every other route here -- dms-core
        proxies it and is the thing that checks the caller owns the
        tenant the version belongs to.
        """
        # The grant dms-core minted while checking the caller's permission and
        # access. Handed straight back to /internal/v1/ocr when the pool
        # worker needs the bytes - this service never mints one.
        future = pool.schedule_render_page(version_id, page, dpi, read_token=x_ocr_job_token)
        try:
            render = future.result()
        except VersionNotFound as exc:
            raise HTTPException(status_code=404, detail=str(exc)) from exc
        except VersionNotEligible as exc:
            raise HTTPException(status_code=409, detail=str(exc)) from exc
        except (ValueError, UnsupportedFormatError) as exc:
            raise HTTPException(status_code=422, detail=str(exc)) from exc
        except TimeoutError as exc:
            raise HTTPException(status_code=504, detail=f"rendering page {page} of {version_id} timed out") from exc
        return Response(
            content=render.png,
            media_type="image/png",
            headers={
                # The frontend places highlights from bbox_norm against
                # these, and needs them before the image decodes.
                "X-Page-Width": str(render.width_px),
                "X-Page-Height": str(render.height_px),
                "X-Page-Count": str(render.page_count),
                # A version is immutable once written, so its rendered
                # pages are too. Worth saying out loud: the reviewer
                # scrolls back and forth through the same pages.
                "Cache-Control": "private, max-age=3600",
            },
        )

    @app.post("/ocr/rubber-band/preview", dependencies=[Depends(require_internal_token)])
    def ocr_rubber_band_preview(
        body: RubberBandRequest,
        x_ocr_job_token: str | None = Header(default=None),
    ):
        """What does this box say? Nothing is written.

        Deliberately NOT /ocr/rubber-band: that one finalizes, replacing
        the version's whole field set and page list with the one crop's
        result. Pointing the review screen at it would wipe every other
        field on the document each time a reviewer re-checked one of
        them. The reviewer's decision is persisted by dms-core as a
        correction instead (Phase 4d).
        """
        future = pool.schedule_preview_rubber_band(
            body.version_id, body.page, (body.x0, body.y0, body.x1, body.y1),
            read_token=x_ocr_job_token,
        )
        try:
            return future.result()
        except VersionNotFound as exc:
            raise HTTPException(status_code=404, detail=str(exc)) from exc
        except VersionNotEligible as exc:
            raise HTTPException(status_code=409, detail=str(exc)) from exc
        except (ValueError, UnsupportedFormatError) as exc:
            raise HTTPException(status_code=422, detail=str(exc)) from exc
        except TimeoutError as exc:
            raise HTTPException(status_code=504, detail=f"OCR of the selected region timed out") from exc

    return app
