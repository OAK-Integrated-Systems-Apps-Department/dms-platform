from __future__ import annotations

import asyncio
import logging
from contextlib import asynccontextmanager

import uvicorn
from fastapi import FastAPI

from .api import create_app
from .config import Settings, get_settings
from .consumer import ScanCompletedConsumer
from .db import make_session_factory
from .layout import LayoutEngine
from .logging_config import configure_logging
from .ocr_engine import PaddleOcrEngine
from .pipeline import OcrPipeline
from .storage import ObjectStorage
from .structuring import build_structuring_runner

logger = logging.getLogger(__name__)


def build_pipeline(settings: Settings) -> tuple[OcrPipeline, "sessionmaker", ObjectStorage]:
    session_factory = make_session_factory(settings)
    storage = ObjectStorage(settings)
    pipeline = OcrPipeline(
        settings=settings,
        session_factory=session_factory,
        storage=storage,
        layout_engine=LayoutEngine(max_document_bytes=settings.max_document_bytes, max_document_pages=settings.max_document_pages),
        ocr_engine=PaddleOcrEngine(),
        structuring=build_structuring_runner(settings, storage),
    )
    return pipeline, session_factory, storage


def create_application() -> FastAPI:
    settings = get_settings()
    configure_logging(settings.log_level)

    pipeline, session_factory, storage = build_pipeline(settings)
    consumer = ScanCompletedConsumer(settings, pipeline)
    consumer_state = {"connected": False}

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
            await consumer.start()
            consumer_state["connected"] = True
        except Exception:
            # A FastAPI process that can serve /ocr/zone, /ocr/rubber-band
            # and the dev-trigger endpoint is still useful even if RabbitMQ
            # is briefly unavailable at boot -- /readyz reflects the outage
            # instead of the whole process refusing to start.
            logger.exception("Failed to start RabbitMQ consumer; API endpoints remain available")

        if settings.llm_warmup_on_startup:
            pipeline.structuring.warmup_in_background()
        yield
        await consumer.stop()
        pipeline.structuring.shutdown()

    app = create_app(pipeline, session_factory, lambda: consumer_state["connected"], settings, structuring=pipeline.structuring)
    app.router.lifespan_context = lifespan
    return app


app = create_application()


if __name__ == "__main__":
    settings = get_settings()
    uvicorn.run("ocr_worker.main:app", host=settings.api_host, port=settings.api_port)
