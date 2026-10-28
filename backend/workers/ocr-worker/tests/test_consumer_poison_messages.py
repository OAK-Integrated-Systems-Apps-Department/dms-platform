"""A malformed message must dead-letter, never wedge a prefetch slot.

Regression cover for a real, live-reproduced failure: dms-core's
OutboxRelay handed its already-serialized `jsonb` payload to
Jackson2JsonMessageConverter, which re-encoded it as a quoted JSON string
literal. ocr-worker's `json.loads` then returned a `str`, `body.get(...)`
raised `AttributeError` straight out of the consumer callback, and the
delivery was left neither acked nor nacked -- so RabbitMQ redelivered it
on every reconnect and it permanently occupied one of the four prefetch
slots. Three such messages were found still stuck after a full stack
restart.

The producer-side fix is in `OutboxRelay.jsonMessage`. These tests cover
the consumer-side half: whatever arrives, the delivery always ends in an
explicit ack or nack.
"""
from __future__ import annotations

import json
import uuid

import pytest

from ocr_worker.config import QUEUE_OCR_REQUESTED, QUEUE_SCAN_COMPLETED, Settings
from ocr_worker.consumer import OcrRequestedConsumer, ScanCompletedConsumer


class FakeMessage:
    """Only the two things a consumer is allowed to do with a delivery."""

    def __init__(self, body: bytes):
        self.body = body
        self.acked = False
        self.nacks: list[bool] = []

    async def ack(self) -> None:
        assert not self.acked and not self.nacks, "delivery settled twice"
        self.acked = True

    async def nack(self, requeue: bool = True) -> None:
        assert not self.acked and not self.nacks, "delivery settled twice"
        self.nacks.append(requeue)


class NeverScheduledPool:
    """Dispatching must not happen for any of these payloads -- if it does,
    the test fails loudly rather than silently passing on a no-op pool.
    """

    def schedule_process_version(self, version_id, correlation_id):  # pragma: no cover - must not run
        raise AssertionError(f"pool was dispatched for {version_id}, expected the message to be rejected first")

    def task_timeout_s(self) -> float:  # pragma: no cover - must not run
        raise AssertionError("pool was consulted")


@pytest.fixture()
def settings():
    return Settings()


@pytest.fixture(params=["ocr_requested", "scan_completed"])
def consumer(request, settings):
    """Both consumers share the decode/validate path, so both must reject
    identically -- neither is allowed to regress on its own.
    """
    if request.param == "ocr_requested":
        return OcrRequestedConsumer(settings, NeverScheduledPool()), QUEUE_OCR_REQUESTED
    return ScanCompletedConsumer(settings, NeverScheduledPool()), QUEUE_SCAN_COMPLETED


def _clean(body: dict) -> dict:
    """ScanCompletedConsumer skips anything not scan_status='clean' before
    it ever looks at the ids, so its payloads need the field present.
    """
    return {**body, "scan_status": "clean"}


async def test_double_encoded_json_string_is_dead_lettered(consumer):
    """The exact wire body the old OutboxRelay produced: a JSON *string*
    whose content is the real JSON object.
    """
    instance, _queue = consumer
    real_payload = _clean({"version_id": str(uuid.uuid4()), "event_type": "document.version.ocr.requested"})
    message = FakeMessage(json.dumps(json.dumps(real_payload)).encode())

    await instance._on_message(message)

    assert message.nacks == [False], "double-encoded payload must nack without requeue"
    assert not message.acked


async def test_unparseable_body_is_dead_lettered(consumer):
    instance, _queue = consumer
    message = FakeMessage(b"not json at all")

    await instance._on_message(message)

    assert message.nacks == [False]


@pytest.mark.parametrize("scalar", [b"42", b'"just-a-string"', b"null", b"[1, 2, 3]"])
async def test_non_object_json_is_dead_lettered(consumer, scalar):
    instance, _queue = consumer
    message = FakeMessage(scalar)

    await instance._on_message(message)

    assert message.nacks == [False]


async def test_missing_version_id_is_dead_lettered(consumer):
    instance, _queue = consumer
    message = FakeMessage(json.dumps(_clean({"event_type": "document.version.ocr.requested"})).encode())

    await instance._on_message(message)

    assert message.nacks == [False]


async def test_malformed_version_id_is_dead_lettered(consumer):
    """A non-UUID `version_id` used to raise ValueError out of the callback
    for exactly the same "never settled" outcome as the AttributeError.
    """
    instance, _queue = consumer
    message = FakeMessage(json.dumps(_clean({"version_id": "not-a-uuid"})).encode())

    await instance._on_message(message)

    assert message.nacks == [False]


async def test_malformed_correlation_id_is_dead_lettered(consumer):
    instance, _queue = consumer
    body = _clean({"version_id": str(uuid.uuid4()), "correlation_id": "not-a-uuid"})
    message = FakeMessage(json.dumps(body).encode())

    await instance._on_message(message)

    assert message.nacks == [False]


async def test_unscanned_version_is_acked_and_skipped(settings):
    """Not a poison message: a non-'clean' scan status is a legitimate
    skip, and must ACK (dropping it) rather than dead-letter.
    """
    instance = ScanCompletedConsumer(settings, NeverScheduledPool())
    body = {"version_id": str(uuid.uuid4()), "scan_status": "infected"}
    message = FakeMessage(json.dumps(body).encode())

    await instance._on_message(message)

    assert message.acked
    assert message.nacks == []


async def test_unscanned_version_with_malformed_id_is_still_acked(settings):
    """The scan_status check runs before id parsing on purpose -- an
    infected version is dropped without caring whether its ids parse.
    """
    instance = ScanCompletedConsumer(settings, NeverScheduledPool())
    message = FakeMessage(json.dumps({"version_id": "not-a-uuid", "scan_status": "infected"}).encode())

    await instance._on_message(message)

    assert message.acked
