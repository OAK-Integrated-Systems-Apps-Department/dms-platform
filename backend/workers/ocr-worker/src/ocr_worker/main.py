from __future__ import annotations

import asyncio
import logging
from contextlib import asynccontextmanager

import uvicorn
from fastapi import FastAPI

from .api import create_app
from .config import Settings, get_settings
from .consumer import OcrRequestedConsumer, ScanCompletedConsumer
from .db import make_session_factory
from .logging_config import configure_logging
from .reaper import Reaper
from .storage import ObjectStorage
from .worker_pool import OcrProcessPool

logger = logging.getLogger(__name__)


def create_application() -> FastAPI:
    settings = get_settings()
    configure_logging(settings.log_level)

    # session_factory/storage here are lightweight, main-process-only uses
    # (/readyz's DB ping, ensure_buckets at startup) -- real OCR processing
    # never touches these; each pool worker builds its own full stack (see
    # worker_pool.py's _pool_worker_init), never this one.
    session_factory = make_session_factory(settings)
    storage = ObjectStorage(settings)
    pool = OcrProcessPool(settings)
    # ocr_requested_consumer is the real production trigger path (Phase 1
    # B.1 -- dms-core's upload/rerun publish document.version.ocr.requested
    # instead of the old synchronous HTTP call). scan_consumer stays for
    # document.version.scan.completed, still an assumed contract since
    # scan-worker doesn't exist yet. Both share the one OcrProcessPool.
    scan_consumer = ScanCompletedConsumer(settings, pool)
    ocr_requested_consumer = OcrRequestedConsumer(settings, pool)
    consumer_state = {"scan_connected": False, "ocr_requested_connected": False}
    # Own session_factory, not the lightweight one above -- kept separate
    # so the reaper's own connection pool usage is visible/attributable on
    # its own (it runs continuously for the process's whole lifetime, not
    # just at startup/readyz-check time like the one above).
    reaper = Reaper(settings, make_session_factory(settings))

    @asynccontextmanager
    async def lifespan(_: FastAPI):
        try:
            storage.ensure_buckets()
        except Exception:
            # Same reasoning as the consumer below: a transient MinIO outage
            # at boot shouldn't take down the whole process. If the bucket
            # genuinely doesn't exist and MinIO is actually up, the first
            # real OCR write will surface that loudly instead.
            logger.exception("Failed to ensure MinIO buckets exist at startup")

        try:
            await scan_consumer.start()
            consumer_state["scan_connected"] = True
        except Exception:
            # A FastAPI process that can serve /ocr/zone, /ocr/rubber-band
            # and the dev-trigger endpoint is still useful even if RabbitMQ
            # is briefly unavailable at boot -- /readyz reflects the outage
            # instead of the whole process refusing to start.
            logger.exception("Failed to start ScanCompletedConsumer; API endpoints remain available")

        try:
            await ocr_requested_consumer.start()
            consumer_state["ocr_requested_connected"] = True
        except Exception:
            logger.exception("Failed to start OcrRequestedConsumer; API endpoints remain available")

        await reaper.start()
        yield
        await reaper.stop()
        await ocr_requested_consumer.stop()
        await scan_consumer.stop()
        pool.close()

    app = create_app(
        pool, session_factory,
        lambda: consumer_state["scan_connected"] and consumer_state["ocr_requested_connected"],
        settings,
    )
    app.router.lifespan_context = lifespan
    return app


app = create_application()


if __name__ == "__main__":
    settings = get_settings()
    uvicorn.run("ocr_worker.main:app", host=settings.api_host, port=settings.api_port)
