from __future__ import annotations

import asyncio
import json
import logging
import uuid

import aio_pika
from aio_pika import ExchangeType
from aio_pika.abc import AbstractIncomingMessage

from .config import (
    DMS_EVENTS_DLX,
    DMS_EVENTS_EXCHANGE,
    QUEUE_DEAD_LETTER,
    QUEUE_SCAN_COMPLETED,
    ROUTING_KEY_OCR_DEAD_LETTER,
    ROUTING_KEY_SCAN_COMPLETED,
    Settings,
)
from .repository import VersionNotEligible, VersionNotFound
from .worker_pool import OcrProcessPool

logger = logging.getLogger(__name__)


class ScanCompletedConsumer:
    """Owns and declares its own queue bound to the shared `oak.dms.events`
    topic exchange -- per RabbitMqConfig.java's ownership model, dms-core
    never declares queues for downstream workers.

    Consumes `document.version.scan.completed` (an assumed contract; see
    docs/events/ocr-scanning-events.md -- scan-worker does not exist yet,
    so nothing currently publishes this in production). Non-'clean' payloads
    are acked and skipped rather than processed, since OCR must never run
    on an unscanned/infected version (section 2.2 precondition).

    Concurrency (OS-NFR-004): `prefetch_count` lets RabbitMQ have that many
    unacked messages in flight; each is dispatched into the shared
    `OcrProcessPool` (docs/ocr/failure-analysis.md #2 -- real PaddleOCR/
    Docling model instances, one per pool-worker PROCESS, not one shared
    instance behind a thread pool, which measured a real 2.7x slowdown
    under concurrency from GIL/native-library contention). `pool` is the
    SAME instance the /ocr/* API endpoints dispatch through -- there must
    only ever be one, or the same contention this replaces would just
    reappear between the two dispatch paths.
    """

    def __init__(self, settings: Settings, pool: OcrProcessPool):
        self._settings = settings
        self._pool = pool
        self._connection: aio_pika.RobustConnection | None = None
        self._channel: aio_pika.RobustChannel | None = None

    async def start(self) -> None:
        self._connection = await aio_pika.connect_robust(self._settings.rabbitmq_url)
        self._channel = await self._connection.channel()
        await self._channel.set_qos(prefetch_count=self._settings.prefetch_count)

        events_exchange = await self._channel.declare_exchange(DMS_EVENTS_EXCHANGE, ExchangeType.TOPIC, durable=True)
        dlx = await self._channel.declare_exchange(DMS_EVENTS_DLX, ExchangeType.TOPIC, durable=True)

        dead_letter_queue = await self._channel.declare_queue(QUEUE_DEAD_LETTER, durable=True)
        await dead_letter_queue.bind(dlx, routing_key=ROUTING_KEY_OCR_DEAD_LETTER)

        main_queue = await self._channel.declare_queue(
            QUEUE_SCAN_COMPLETED,
            durable=True,
            arguments={
                "x-dead-letter-exchange": DMS_EVENTS_DLX,
                "x-dead-letter-routing-key": ROUTING_KEY_OCR_DEAD_LETTER,
            },
        )
        await main_queue.bind(events_exchange, routing_key=ROUTING_KEY_SCAN_COMPLETED)

        await main_queue.consume(self._on_message)
        logger.info(
            "ScanCompletedConsumer listening on %s (routing key %s), prefetch=%d, workers=%d",
            QUEUE_SCAN_COMPLETED, ROUTING_KEY_SCAN_COMPLETED, self._settings.prefetch_count, self._settings.worker_pool_size,
        )

    async def stop(self) -> None:
        if self._connection is not None:
            await self._connection.close()
        # The pool is shared with the API endpoints and owned by main.py's
        # lifespan (constructed and closed there), not by this consumer.

    async def _on_message(self, message: AbstractIncomingMessage) -> None:
        try:
            body = json.loads(message.body)
        except json.JSONDecodeError:
            logger.error("Discarding unparseable message on %s", QUEUE_SCAN_COMPLETED)
            await message.nack(requeue=False)
            return

        scan_status = body.get("scan_status")
        version_id_raw = body.get("version_id")

        if scan_status != "clean":
            logger.info("Skipping version %s: scan_status=%r is not 'clean'", version_id_raw, scan_status)
            await message.ack()
            return

        if not version_id_raw:
            logger.error("Discarding message with no version_id: %s", body)
            await message.nack(requeue=False)
            return

        version_id = uuid.UUID(version_id_raw)
        correlation_id = uuid.UUID(body["correlation_id"]) if body.get("correlation_id") else None

        loop = asyncio.get_running_loop()
        future = self._pool.schedule_process_version(version_id, correlation_id)
        try:
            outcome = await asyncio.wrap_future(future, loop=loop)
        except (VersionNotFound, VersionNotEligible) as exc:
            logger.warning("Ack-and-skip for version %s: %s", version_id, exc)
            await message.ack()
            return
        except TimeoutError:
            # pebble already terminated and replaced the specific pool
            # worker process (verified live) -- the claimed processing_jobs
            # row is left 'processing' with a live lease. Nothing else in
            # this phase reclaims it; B.4's reaper (not yet implemented)
            # is what will requeue-or-dead-letter it once its lease
            # expires, the same as any other crashed worker.
            logger.error("Timed out processing version %s (task timeout=%.0fs)", version_id, self._pool.task_timeout_s())
            await message.nack(requeue=False)
            return
        except Exception:
            # Unexpected failure *outside* the pipeline's own try/except (e.g.
            # a DB outage while writing the failure record itself) -- the
            # transaction never committed, so nack without requeue and let
            # this land on the DLQ rather than spin-retry against a down DB.
            logger.exception("Unhandled error processing version %s", version_id)
            await message.nack(requeue=False)
            return

        if outcome.status == "completed":
            await message.ack()
        else:
            # pipeline already committed the failure transaction (ocr_status
            # ='failed', processing_jobs row, outbox 'ocr.failed' event) --
            # nack without requeue so processing_jobs.attempts / the DLQ
            # governs retry policy rather than RabbitMQ's own requeue loop.
            await message.nack(requeue=False)
