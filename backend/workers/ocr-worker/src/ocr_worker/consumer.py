from __future__ import annotations

import asyncio
import json
import logging
import uuid

import aio_pika
from aio_pika import ExchangeType
from aio_pika.abc import AbstractIncomingMessage
from pebble import ProcessExpired

from .config import (
    DMS_EVENTS_DLX,
    DMS_EVENTS_EXCHANGE,
    OCR_TIMEOUT_EXIT_CODE,
    QUEUE_DEAD_LETTER,
    QUEUE_OCR_REQUESTED,
    QUEUE_SCAN_COMPLETED,
    ROUTING_KEY_OCR_DEAD_LETTER,
    ROUTING_KEY_OCR_REQUESTED,
    ROUTING_KEY_SCAN_COMPLETED,
    Settings,
)
from .repository import VersionNotEligible, VersionNotFound
from .worker_pool import OcrProcessPool

logger = logging.getLogger(__name__)


async def _decode_event(message: AbstractIncomingMessage, queue_name: str) -> dict | None:
    """Decode a message body into an event dict, or nack-without-requeue and
    return None if it is a poison message.

    Every rejection path here must end in an explicit ack or nack. An
    exception escaping a consumer callback leaves the delivery neither
    acked nor nacked, so RabbitMQ redelivers it on every reconnect,
    forever, and it permanently occupies a prefetch slot. That was not
    hypothetical: dms-core's OutboxRelay used to double-encode payloads as
    JSON string literals, `json.loads` returned a `str`, `body.get(...)`
    raised `AttributeError` out of `_on_message`, and three such messages
    survived a full stack restart still unacked (verified live, then fixed
    on the producer side in OutboxRelay.jsonMessage). This guard is the
    consumer-side half: a malformed payload must dead-letter loudly, not
    wedge a prefetch slot.
    """
    try:
        body = json.loads(message.body)
    except json.JSONDecodeError:
        logger.error("Discarding unparseable message on %s", queue_name)
        await message.nack(requeue=False)
        return None

    if not isinstance(body, dict):
        logger.error(
            "Discarding message on %s whose JSON payload is a %s, not an object",
            queue_name, type(body).__name__,
        )
        await message.nack(requeue=False)
        return None

    return body


async def _parse_ids(body: dict, message: AbstractIncomingMessage, queue_name: str) -> tuple[uuid.UUID, uuid.UUID | None] | None:
    """Pull `version_id`/`correlation_id` out of a decoded event, or
    nack-without-requeue and return None. Same contract as `_decode_event`:
    a malformed id is a poison message, not an exception to let escape.
    """
    version_id_raw = body.get("version_id")
    if not version_id_raw:
        logger.error("Discarding message on %s with no version_id: %s", queue_name, body)
        await message.nack(requeue=False)
        return None

    try:
        version_id = uuid.UUID(str(version_id_raw))
        correlation_raw = body.get("correlation_id")
        correlation_id = uuid.UUID(str(correlation_raw)) if correlation_raw else None
    except (ValueError, AttributeError, TypeError):
        logger.error("Discarding message on %s with malformed ids: %s", queue_name, body)
        await message.nack(requeue=False)
        return None

    return version_id, correlation_id


async def _await_outcome_and_finalize(future, message: AbstractIncomingMessage, *, version_id: uuid.UUID, loop, task_timeout_s: float) -> None:
    """Shared by every consumer that dispatches a task into the
    OcrProcessPool and then must ack/nack based on the real outcome --
    ScanCompletedConsumer and OcrRequestedConsumer differ only in which
    routing key/payload shape they parse; once dispatched, what ack/nack
    means is identical for both.
    """
    try:
        outcome = await asyncio.wrap_future(future, loop=loop)
    except (VersionNotFound, VersionNotEligible) as exc:
        logger.warning("Ack-and-skip for version %s: %s", version_id, exc)
        await message.ack()
        return
    except TimeoutError:
        # The OUTERMOST pebble ceiling fired, meaning the pool worker's own
        # per-document watchdog (deadline.py) never got a turn either.
        # pebble already terminated and replaced that specific worker
        # process (verified live) -- but nothing recorded a failure, so the
        # claimed processing_jobs row is left 'processing' with a live
        # lease and the reaper reclaims it (requeue-or-dead-letter) once
        # that lease expires, the same as any other crashed worker.
        logger.error(
            "Version %s hit the outer pool ceiling (%.0fs) -- the in-worker deadline never fired; "
            "leaving the lease for the reaper",
            version_id, task_timeout_s,
        )
        await message.nack(requeue=False)
        return
    except ProcessExpired as exc:
        # The pool worker died mid-document. Two very different cases,
        # told apart by the exit code alone (pebble surfaces both
        # identically otherwise):
        if exc.exitcode == OCR_TIMEOUT_EXIT_CODE:
            # Its own hard per-document watchdog killed it, AFTER writing
            # the TIMEOUT failure record + '.failed' event (pipeline.py's
            # _on_hard_timeout). Nothing left to reclaim -- this is an
            # ordinary recorded failure, so nack without requeue exactly
            # like the `outcome.status != "completed"` branch below.
            logger.error("Version %s timed out; its pool worker recorded TIMEOUT and was replaced", version_id)
        else:
            # A real crash (OOM kill, segfault in a native library). No
            # failure record was written, so the lease stands and the
            # reaper owns it.
            logger.error(
                "Pool worker died (exitcode=%s, pid=%s) processing version %s; leaving the lease for the reaper",
                exc.exitcode, exc.pid, version_id,
            )
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
        await self._channel.set_qos(prefetch_count=self._settings.effective_prefetch_count)

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
            "ScanCompletedConsumer listening on %s (routing key %s), prefetch=%d",
            QUEUE_SCAN_COMPLETED, ROUTING_KEY_SCAN_COMPLETED, self._settings.effective_prefetch_count,
        )

    async def stop(self) -> None:
        if self._connection is not None:
            await self._connection.close()
        # The pool is shared with the API endpoints and owned by main.py's
        # lifespan (constructed and closed there), not by this consumer.

    async def _on_message(self, message: AbstractIncomingMessage) -> None:
        body = await _decode_event(message, QUEUE_SCAN_COMPLETED)
        if body is None:
            return

        scan_status = body.get("scan_status")
        if scan_status != "clean":
            logger.info(
                "Skipping version %s: scan_status=%r is not 'clean'",
                body.get("version_id"), scan_status,
            )
            await message.ack()
            return

        ids = await _parse_ids(body, message, QUEUE_SCAN_COMPLETED)
        if ids is None:
            return
        version_id, correlation_id = ids

        loop = asyncio.get_running_loop()
        future = self._pool.schedule_process_version(version_id, correlation_id, resolve_fields_from_db=True)
        await _await_outcome_and_finalize(
            future, message, version_id=version_id, loop=loop, task_timeout_s=self._pool.task_timeout_s()
        )


class OcrRequestedConsumer:
    """Owns and declares its own queue bound to the shared `oak.dms.events`
    topic exchange, same ownership model as ScanCompletedConsumer.

    Consumes `document.version.ocr.requested` -- the real production
    trigger path once dms-core's upload/rerun start publishing it (Phase 1
    B.1), replacing the old synchronous HTTP call to /ocr/process/{id}.
    No `scan_status` field to check here (unlike ScanCompletedConsumer):
    dms-core's uploadDocument already sets scan_status='clean' directly at
    upload time since scan-worker doesn't exist yet, so
    get_version_for_processing's own scan_status=='clean' check already
    covers this precondition without this consumer needing to duplicate it.

    Shares the SAME dead-letter queue/routing key as ScanCompletedConsumer
    (QUEUE_DEAD_LETTER is a generic name, not scan-specific) -- no need for
    a second DLQ.
    """

    def __init__(self, settings: Settings, pool: OcrProcessPool):
        self._settings = settings
        self._pool = pool
        self._connection: aio_pika.RobustConnection | None = None
        self._channel: aio_pika.RobustChannel | None = None

    async def start(self) -> None:
        self._connection = await aio_pika.connect_robust(self._settings.rabbitmq_url)
        self._channel = await self._connection.channel()
        await self._channel.set_qos(prefetch_count=self._settings.effective_prefetch_count)

        events_exchange = await self._channel.declare_exchange(DMS_EVENTS_EXCHANGE, ExchangeType.TOPIC, durable=True)
        dlx = await self._channel.declare_exchange(DMS_EVENTS_DLX, ExchangeType.TOPIC, durable=True)

        dead_letter_queue = await self._channel.declare_queue(QUEUE_DEAD_LETTER, durable=True)
        await dead_letter_queue.bind(dlx, routing_key=ROUTING_KEY_OCR_DEAD_LETTER)

        main_queue = await self._channel.declare_queue(
            QUEUE_OCR_REQUESTED,
            durable=True,
            arguments={
                "x-dead-letter-exchange": DMS_EVENTS_DLX,
                "x-dead-letter-routing-key": ROUTING_KEY_OCR_DEAD_LETTER,
            },
        )
        await main_queue.bind(events_exchange, routing_key=ROUTING_KEY_OCR_REQUESTED)

        await main_queue.consume(self._on_message)
        logger.info(
            "OcrRequestedConsumer listening on %s (routing key %s), prefetch=%d",
            QUEUE_OCR_REQUESTED, ROUTING_KEY_OCR_REQUESTED, self._settings.effective_prefetch_count,
        )

    async def stop(self) -> None:
        if self._connection is not None:
            await self._connection.close()
        # The pool is shared with the API endpoints/ScanCompletedConsumer
        # and owned by main.py's lifespan, not by this consumer.

    async def _on_message(self, message: AbstractIncomingMessage) -> None:
        body = await _decode_event(message, QUEUE_OCR_REQUESTED)
        if body is None:
            return

        ids = await _parse_ids(body, message, QUEUE_OCR_REQUESTED)
        if ids is None:
            return
        version_id, correlation_id = ids

        loop = asyncio.get_running_loop()
        future = self._pool.schedule_process_version(version_id, correlation_id, resolve_fields_from_db=True)
        await _await_outcome_and_finalize(
            future, message, version_id=version_id, loop=loop, task_timeout_s=self._pool.task_timeout_s()
        )
