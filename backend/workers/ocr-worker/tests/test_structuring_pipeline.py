"""End-to-end wiring of structured extraction into the real OcrPipeline,
StructuredExtractionRunner and FastAPI app -- SQLite + in-memory storage +
fake layout/OCR engines + a scripted LLM client (see conftest.py and
test_pipeline_transaction.py for the same pattern without structuring)."""
from __future__ import annotations

import copy
import itertools
import json
import sys
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path
from types import SimpleNamespace

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker
from sqlalchemy.pool import StaticPool

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "src"))

from test_pipeline_transaction import FakeLayoutDocument, FakeLayoutEngine, FakeOcrEngine  # noqa: E402

from ocr_worker.api import create_app  # noqa: E402
from ocr_worker.config import Settings  # noqa: E402
from ocr_worker.layout import LayoutEngine, NativeTextBlock  # noqa: E402
from ocr_worker.models import Base, DocumentVersion, ProcessingJob  # noqa: E402
from ocr_worker.ocr_engine import WordResult  # noqa: E402
from ocr_worker.pipeline import OcrPipeline  # noqa: E402
from ocr_worker.storage import STRUCTURED_SUFFIX, ObjectStorage  # noqa: E402
from ocr_worker.structuring import StructuredExtractionRunner, StructuredExtractionService  # noqa: E402
from ocr_worker.structuring.errors import StructuringError  # noqa: E402
from ocr_worker.structuring.llm_client import LlmResponse  # noqa: E402

TOKEN = "test-internal-token"


@pytest.fixture()
def session_factory():
    """conftest's in-memory SQLite gives every thread its own empty
    database; TestClient serves requests (and async structuring runs) on
    other threads, so share one connection across threads here."""
    engine = create_engine("sqlite:///:memory:", poolclass=StaticPool, connect_args={"check_same_thread": False})
    Base.metadata.create_all(engine)
    return sessionmaker(bind=engine, expire_on_commit=False)


class MemoryStorage:
    """ObjectStorage stand-in. JSON-copies on write like the real MinIO
    upload does, so later mutation of a dict can't leak into what was
    "stored"."""

    def __init__(self, source_bytes: bytes = b"%PDF-1.7 fake"):
        self.objects: dict[str, dict] = {}
        self._order: dict[str, int] = {}
        self._clock = itertools.count()
        self._source_bytes = source_bytes

    def get_object_bytes(self, object_key):
        return self._source_bytes

    def put_ocr_output_json(self, object_key, data):
        self.objects[object_key] = json.loads(json.dumps(data))
        self._order[object_key] = next(self._clock)
        return object_key

    def get_ocr_output_json(self, object_key):
        return copy.deepcopy(self.objects[object_key])

    def latest_ocr_output_key(self, tenant_id, version_id, *, structured=False):
        prefix = f"ocr/{tenant_id}/{version_id}/"
        keys = [k for k in self.objects if k.startswith(prefix) and k.endswith(STRUCTURED_SUFFIX) == structured]
        return max(keys, key=self._order.get) if keys else None

    def structured(self):
        return {k: v for k, v in self.objects.items() if k.endswith(STRUCTURED_SUFFIX)}


class ScriptedClient:
    provider = "fake"
    model = "fake-7b"

    def __init__(self, reply):
        self._reply = reply
        self.calls = 0

    def complete_json(self, messages, *, schema, max_tokens):
        self.calls += 1
        if isinstance(self._reply, Exception):
            raise self._reply
        return LlmResponse(content=json.dumps(self._reply), finish_reason="stop", prompt_tokens=500, completion_tokens=60, duration_ms=3)

    def health(self):
        return {"reachable": True, "model_available": True}

    def warmup(self):
        return None


INVOICE_REPLY = {"document_type": "invoice", "fields": {"invoice_no": "INV-4471", "total": "1,250.00"}, "tables": [], "sections": []}


def _native_doc():
    return FakeLayoutDocument(
        page_count=1,
        native_text_by_page={1: [
            NativeTextBlock(text="INVOICE", bbox=(10, 10, 300, 40), label="title"),
            NativeTextBlock(text="Invoice No: INV-4471", bbox=(10, 60, 300, 90)),
            NativeTextBlock(text="Total: 1,250.00", bbox=(10, 120, 300, 150)),
        ]},
    )


def _build(session_factory, *, mode="sync", reply=INVOICE_REPLY, layout_doc=None, ocr_engine=None, layout_engine=None):
    settings = Settings(llm_extraction_mode=mode, internal_service_token=TOKEN)
    storage = MemoryStorage()
    client = ScriptedClient(reply)
    runner = StructuredExtractionRunner(settings, storage, StructuredExtractionService(settings, client))
    pipeline = OcrPipeline(
        settings=settings,
        session_factory=session_factory,
        storage=storage,
        layout_engine=layout_engine or FakeLayoutEngine(layout_doc or _native_doc()),
        ocr_engine=ocr_engine or FakeOcrEngine(text="", confidence=0.99),
        structuring=runner,
    )
    return SimpleNamespace(settings=settings, storage=storage, client=client, runner=runner, pipeline=pipeline)


def _drain(runner):
    runner._pool().shutdown(wait=True)


# ----------------------------------------------------------------------
# Pipeline
# ----------------------------------------------------------------------
def test_sync_mode_returns_structured_json_and_stores_it_next_to_the_ocr_output(session_factory, seeded_version):
    env = _build(session_factory, mode="sync")
    outcome = env.pipeline.process_version(seeded_version["version_id"])

    assert outcome.status == "completed"
    structured = outcome.output["structured"]
    assert structured["status"] == "completed"
    assert structured["document_type"] == "invoice"
    assert structured["fields"] == {"invoice_no": "INV-4471", "total": "1,250.00"}
    assert structured["ocr_output_ref"] == outcome.extracted_text_ref

    stored_key = outcome.extracted_text_ref.removesuffix(".json") + STRUCTURED_SUFFIX
    assert env.storage.objects[stored_key]["fields"] == structured["fields"]
    # The OCR artifact itself is unchanged by structuring.
    assert "structured" not in env.storage.objects[outcome.extracted_text_ref]
    # Docling's block label reaches the output contract (additively).
    assert outcome.output["pages"][0]["blocks"][0]["label"] == "title"


def test_async_mode_writes_pending_then_the_final_result_without_delaying_ocr(session_factory, seeded_version):
    env = _build(session_factory, mode="async")
    outcome = env.pipeline.process_version(seeded_version["version_id"])
    assert outcome.status == "completed"
    assert "structured" not in outcome.output

    _drain(env.runner)
    [result] = env.storage.structured().values()
    assert result["status"] == "completed" and result["fields"]["invoice_no"] == "INV-4471"


def test_llm_failure_never_fails_the_ocr_run(session_factory, seeded_version):
    env = _build(session_factory, mode="sync", reply=StructuringError("MODEL_UNAVAILABLE", "model server unreachable"))
    outcome = env.pipeline.process_version(seeded_version["version_id"])

    assert outcome.status == "completed"
    assert outcome.output["structured"]["status"] == "failed"
    assert outcome.output["structured"]["error"]["code"] == "MODEL_UNAVAILABLE"
    with session_factory() as session:
        assert session.get(DocumentVersion, seeded_version["version_id"]).ocr_status == "completed"


def test_off_mode_never_calls_the_model(session_factory, seeded_version):
    env = _build(session_factory, mode="off")
    outcome = env.pipeline.process_version(seeded_version["version_id"])
    assert outcome.status == "completed" and "structured" not in outcome.output
    assert env.client.calls == 0 and env.storage.structured() == {}


def test_scanned_page_reaches_the_model_with_paddleocr_layout(session_factory, seeded_version):
    words = [
        WordResult(text="Invoice No:", confidence=0.98, bbox=(100, 100, 300, 130)),
        WordResult(text="INV-4471", confidence=0.97, bbox=(400, 101, 600, 129)),
        WordResult(text="Total: 1,250.00", confidence=0.95, bbox=(100, 200, 500, 230)),
    ]
    ocr = FakeOcrEngine(text="Invoice No:\nINV-4471\nTotal: 1,250.00", confidence=0.97, words=words)
    captured = {}

    class Capturing(ScriptedClient):
        def complete_json(self, messages, *, schema, max_tokens):
            captured["user"] = messages[1]["content"]
            return super().complete_json(messages, schema=schema, max_tokens=max_tokens)

    env = _build(session_factory, mode="sync", layout_doc=FakeLayoutDocument(page_count=1), ocr_engine=ocr)
    env.runner._service._client = Capturing(INVOICE_REPLY)
    outcome = env.pipeline.process_version(seeded_version["version_id"])

    assert outcome.output["structured"]["fields"]["invoice_no"] == "INV-4471"
    assert '<page n="1" source="paddleocr"' in captured["user"]
    assert "Invoice No: | INV-4471" in captured["user"]  # label and value boxes joined as one visual row
    # ...which already states the relation, so no redundant key/value hint.
    assert "<key_values>" not in captured["user"]


def test_invalid_upload_fails_ocr_with_a_coded_error_before_any_parsing(session_factory, seeded_version):
    # Real LayoutEngine: validation runs before Docling would be imported.
    env = _build(session_factory, mode="sync", layout_engine=LayoutEngine())
    env.storage._source_bytes = b"MZ\x90\x00 definitely not a pdf"
    outcome = env.pipeline.process_version(seeded_version["version_id"])

    assert outcome.status == "failed"
    assert outcome.error.startswith("UNSUPPORTED_FORMAT:")
    with session_factory() as session:
        job = session.query(ProcessingJob).one()
        assert job.status == "failed" and job.last_error.startswith("UNSUPPORTED_FORMAT:")
    assert env.client.calls == 0


def test_invalid_mode_or_grounding_policy_is_rejected_at_startup():
    storage = MemoryStorage()
    for settings in (Settings(llm_extraction_mode="sometimes"), Settings(llm_grounding_policy="trust-me")):
        with pytest.raises(ValueError):
            StructuredExtractionRunner(settings, storage, StructuredExtractionService(settings, None))


# ----------------------------------------------------------------------
# API
# ----------------------------------------------------------------------
def _client(env, session_factory) -> TestClient:
    app = create_app(env.pipeline, session_factory, lambda: True, env.settings, structuring=env.runner)
    return TestClient(app)


HEADERS = {"X-Internal-Token": TOKEN}


def test_structured_endpoints_require_the_internal_token(session_factory, seeded_version):
    client = _client(_build(session_factory), session_factory)
    assert client.get(f"/ocr/structured/{seeded_version['version_id']}").status_code == 401
    assert client.get("/ocr/structured/health").status_code == 401


def test_get_structured_result_lifecycle(session_factory, seeded_version):
    env = _build(session_factory, mode="sync")
    client = _client(env, session_factory)
    version_id = seeded_version["version_id"]

    assert client.get(f"/ocr/structured/{version_id}", headers=HEADERS).status_code == 404
    assert client.get(f"/ocr/structured/{uuid.uuid4()}", headers=HEADERS).status_code == 404

    process = client.post(f"/ocr/process/{version_id}", headers=HEADERS, json={})
    assert process.status_code == 200 and process.json()["structured"]["status"] == "completed"

    fetched = client.get(f"/ocr/structured/{version_id}", headers=HEADERS)
    assert fetched.status_code == 200
    assert fetched.json()["fields"]["invoice_no"] == "INV-4471"


def test_post_structured_reruns_on_the_stored_ocr_output(session_factory, seeded_version):
    env = _build(session_factory, mode="async")
    client = _client(env, session_factory)
    version_id = seeded_version["version_id"]

    assert client.post(f"/ocr/structured/{version_id}", headers=HEADERS).status_code == 409  # no OCR output yet

    env.pipeline.process_version(version_id)
    _drain(env.runner)
    env.runner._executor = None  # fresh pool for the next submissions

    waited = client.post(f"/ocr/structured/{version_id}", headers=HEADERS, json={"wait": True})
    assert waited.status_code == 200 and waited.json()["status"] == "completed"

    queued = client.post(f"/ocr/structured/{version_id}", headers=HEADERS)
    assert queued.status_code == 202 and queued.json()["status"] == "pending"
    _drain(env.runner)
    assert client.get(f"/ocr/structured/{version_id}", headers=HEADERS).json()["status"] == "completed"
    assert env.client.calls == 3


def test_post_structured_when_disabled_is_409(session_factory, seeded_version):
    client = _client(_build(session_factory, mode="off"), session_factory)
    response = client.post(f"/ocr/structured/{seeded_version['version_id']}", headers=HEADERS)
    assert response.status_code == 409 and "LLM_EXTRACTION_MODE=off" in response.json()["detail"]


def test_health_reports_mode_model_and_availability(session_factory):
    env = _build(session_factory, mode="async")
    body = _client(env, session_factory).get("/ocr/structured/health", headers=HEADERS).json()
    assert body == {"mode": "async", "provider": "ollama", "name": "qwen2.5:7b-instruct", "reachable": True, "model_available": True}


# ----------------------------------------------------------------------
# Real ObjectStorage.latest_ocr_output_key over a fake MinIO client
# ----------------------------------------------------------------------
def test_object_storage_picks_the_newest_ocr_or_structured_object():
    now = datetime.now(timezone.utc)
    listing = [
        SimpleNamespace(object_name="ocr/t/v/a.json", last_modified=now - timedelta(minutes=5)),
        SimpleNamespace(object_name="ocr/t/v/b.json", last_modified=now),
        SimpleNamespace(object_name="ocr/t/v/a.structured.json", last_modified=now - timedelta(minutes=4)),
        SimpleNamespace(object_name="ocr/t/v/b.structured.json", last_modified=now + timedelta(seconds=1)),
        SimpleNamespace(object_name="ocr/t/v/notes.txt", last_modified=now + timedelta(hours=1)),
    ]
    storage = ObjectStorage(Settings())
    storage._client = SimpleNamespace(list_objects=lambda bucket, prefix, recursive: iter(listing))
    assert storage.latest_ocr_output_key("t", "v") == "ocr/t/v/b.json"
    assert storage.latest_ocr_output_key("t", "v", structured=True) == "ocr/t/v/b.structured.json"
