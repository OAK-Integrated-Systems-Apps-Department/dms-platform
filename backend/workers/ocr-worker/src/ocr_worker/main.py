from __future__ import annotations

import asyncio
import logging
import os
from contextlib import asynccontextmanager

import uvicorn
from fastapi import FastAPI

from .api import create_app
from .config import (
    QUEUE_OCR_REQUESTED_BULK,
    ROUTING_KEY_OCR_REQUESTED_BULK,
    Settings,
    get_settings,
)
from .consumer import OcrRequestedConsumer, ScanCompletedConsumer
from .db import make_session_factory
from .logging_config import configure_logging
from .reaper import Reaper
from .worker_pool import OcrProcessPool

logger = logging.getLogger(__name__)


def apply_network_posture(settings: Settings) -> None:
    """Pin the model loaders offline, before anything can load a model.

    Must run before `OcrProcessPool` is constructed, not merely before the
    first document: pool workers are started with `spawn`, so what they
    inherit is this process's `os.environ` as it stands at fork/spawn
    time. Setting it later would leave every worker online while the
    parent looked correctly configured.

    huggingface_hub and transformers both read their flag once, at import,
    so this also has to happen before the first `import docling` pulls
    them in transitively.
    """
    if not settings.ocr_offline_models:
        return
    os.environ["HF_HUB_OFFLINE"] = "1"
    os.environ["TRANSFORMERS_OFFLINE"] = "1"
    logger.info(
        "OCR_OFFLINE_MODELS=true: model hubs pinned to the local cache. This worker now "
        "makes no outbound network call of any kind -- only Postgres, RabbitMQ and dms-core. "
        "Model weights must already be present in the mounted caches."
    )


def create_application() -> FastAPI:
    settings = get_settings()
    configure_logging(settings.log_level)
    apply_network_posture(settings)

    # Fail fast on a malformed retry policy, here rather than lazily on
    # the reaper tick that first needs it. A typo in
    # OCR_RETRY_ATTEMPTS_BY_CODE would otherwise surface as a caught
    # exception inside a background loop -- logged, swallowed, and
    # leaving every failure on the old "retry five times" fallback, which
    # is exactly the behaviour the setting exists to remove.
    policy = settings.retry_attempts_by_code
    logger.info(
        "Retry policy (retries after the first attempt): %s; an unrecorded cause gets %d, "
        "the same as WORKER_CRASH, because a container-level OOM kills the parent too",
        ", ".join(f"{code}={retries}" for code, retries in sorted(policy.items())) or "(none)",
        settings.max_attempts_for(None) - 1,
    )

    # session_factory here is a lightweight, main-process-only use
    # (/readyz's DB ping) -- real OCR processing never touches it; each pool
    # worker builds its own full stack, including its own
    # DocumentContentClient (see worker_pool.py's _pool_worker_init), never
    # this one.
    session_factory = make_session_factory(settings)
    pool = OcrProcessPool(settings)
    # ocr_requested_consumer is the real production trigger path (Phase 1
    # B.1 -- dms-core's upload/rerun publish document.version.ocr.requested
    # instead of the old synchronous HTTP call). scan_consumer stays for
    # document.version.scan.completed, still an assumed contract since
    # scan-worker doesn't exist yet. Both share the one OcrProcessPool.
    scan_consumer = ScanCompletedConsumer(settings, pool, session_factory)
    ocr_requested_consumer = OcrRequestedConsumer(settings, pool, session_factory)
    # Bulk import's OCR, on its own queue.
    #
    # Separate queue, not a priority field on one queue: a 5,000-file import
    # publishes 5,000 messages at once, and in a single queue every
    # interactive scan that arrives afterwards sits behind all of them. That
    # is head-of-line blocking in the queue order itself, which no prefetch
    # setting can undo.
    #
    # prefetch=1 rather than the pool size. Both consumers feed the SAME
    # process pool, so the queue split alone would not stop an import from
    # occupying every pool slot; holding at most one bulk delivery at a time
    # is what keeps slots free for interactive work. The cost is that bulk
    # OCR runs roughly one document at a time, which is the right trade: the
    # import has already finished from the uploader's point of view, and
    # nobody is watching its OCR.
    bulk_ocr_consumer = OcrRequestedConsumer(
        settings, pool, session_factory,
        queue_name=QUEUE_OCR_REQUESTED_BULK,
        routing_key=ROUTING_KEY_OCR_REQUESTED_BULK,
        prefetch_count=1,
    )
    consumer_state = {"scan_connected": False, "ocr_requested_connected": False}
    # Own session_factory, not the lightweight one above -- kept separate
    # so the reaper's own connection pool usage is visible/attributable on
    # its own (it runs continuously for the process's whole lifetime, not
    # just at startup/readyz-check time like the one above).
    reaper = Reaper(settings, make_session_factory(settings))

    @asynccontextmanager
    async def lifespan(_: FastAPI):
        # No bucket set-up any more. This service owns no storage: it reads
        # documents from, and writes its artifacts back to, dms-core over the
        # internal endpoint (see content_client.py), because every object is
        # encrypted at rest and only dms-core holds the keys. There is nothing
        # left to create here, and nothing to be transiently unavailable.
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

        try:
            await bulk_ocr_consumer.start()
        except Exception:
            # Deliberately NOT part of consumer_state/readyz: bulk import OCR
            # is background work nobody is waiting on, so its queue being
            # unavailable must not take the whole service out of rotation and
            # stop interactive scans being processed.
            logger.exception("Failed to start the bulk-import OCR consumer; interactive OCR is unaffected")

        await reaper.start()
        yield
        await reaper.stop()
        await bulk_ocr_consumer.stop()
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
