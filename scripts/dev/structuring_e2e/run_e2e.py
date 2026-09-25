"""Real-engine E2E: OcrPipeline with real upload validation, real Docling,
real PaddleOCR and structured extraction against the real local LLM
(Ollama), per document. SQLite + in-memory storage stand in for
Postgres/MinIO only.

usage: python run_e2e.py <out_dir> <doc> [<doc> ...]   (env LLM_* as for the worker)
       E2E_NO_LLM=1 -> OCR + normalized-representation dump only
"""
import json
import mimetypes
import os
import sys
import time
import uuid
from pathlib import Path

sys.path.insert(0, "/app/src")

from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker
from sqlalchemy.pool import StaticPool

from ocr_worker.config import Settings
from ocr_worker.layout import LayoutEngine
from ocr_worker.models import Base, Document, DocumentType, DocumentVersion
from ocr_worker.ocr_engine import PaddleOcrEngine
from ocr_worker.pipeline import OcrPipeline
from ocr_worker.structuring import build_structuring_runner
from ocr_worker.structuring.chunking import plan_chunks
from ocr_worker.structuring.document_model import build_normalized_document
from ocr_worker.structuring.service import StructuredExtractionService


class MemoryStorage:
    def __init__(self):
        self.source = b""
        self.objects = {}

    def get_object_bytes(self, key):
        return self.source

    def put_ocr_output_json(self, key, data):
        self.objects[key] = json.loads(json.dumps(data))
        return key


def main():
    out = Path(sys.argv[1])
    out.mkdir(parents=True, exist_ok=True)
    docs = [Path(p) for p in sys.argv[2:]]
    no_llm = os.environ.get("E2E_NO_LLM") == "1"
    settings = Settings(llm_extraction_mode="off" if no_llm else "sync")

    engine = create_engine("sqlite:///:memory:", poolclass=StaticPool, connect_args={"check_same_thread": False})
    Base.metadata.create_all(engine)
    sf = sessionmaker(bind=engine, expire_on_commit=False)
    storage = MemoryStorage()
    runner = build_structuring_runner(settings, storage)
    pipeline = OcrPipeline(
        settings=settings, session_factory=sf, storage=storage,
        layout_engine=LayoutEngine(settings.max_document_bytes, settings.max_document_pages),
        ocr_engine=PaddleOcrEngine(), structuring=runner,
    )
    print("LLM health:", json.dumps(runner.health()), flush=True)

    tenant, doc_type = uuid.uuid4(), uuid.uuid4()
    with sf() as s:
        s.add(DocumentType(document_type_id=doc_type, tenant_id=tenant, type_name="general"))
        s.commit()

    summary = []
    for path in docs:
        mime = mimetypes.guess_type(path.name.lower())[0] or "application/octet-stream"
        document_id, version_id = uuid.uuid4(), uuid.uuid4()
        with sf() as s:
            s.add(Document(document_id=document_id, tenant_id=tenant, document_type_id=doc_type, title=path.name))
            s.add(DocumentVersion(version_id=version_id, document_id=document_id, tenant_id=tenant, storage_object_key=path.name,
                                  original_filename=path.name, mime_type=mime, scan_status="clean", ocr_status="processing"))
            s.commit()
        storage.source = path.read_bytes()

        started = time.monotonic()
        outcome = pipeline.process_version(version_id)
        total_ms = int((time.monotonic() - started) * 1000)
        record = {"document": path.name, "mime": mime, "ocr_status": outcome.status, "total_ms": total_ms}
        if outcome.status != "completed":
            record["ocr_error"] = outcome.error
        else:
            pages = outcome.output["pages"]
            (out / f"{path.stem}.ocr.json").write_text(json.dumps(outcome.output, ensure_ascii=False))
            record["pages"] = [{"n": p["page_number"], "method": p["method"], "blocks": len(p["blocks"]), "tables": len(p["tables"]), "confidence": round(p["confidence"], 3)} for p in pages]
            normalized = build_normalized_document(pages)
            service = StructuredExtractionService(settings, None)
            chunks = plan_chunks(normalized, budget_tokens=service.chunk_budget_tokens(), chars_per_token=settings.llm_chars_per_token)
            (out / f"{path.stem}.model_input.txt").write_text("\n\n===== CHUNK =====\n".join(c.render() for c in chunks))
            structured = outcome.output.get("structured")
            if structured:
                record["structured_status"] = structured["status"]
                record["structured_ms"] = structured["stats"].get("duration_ms")
                record["error"] = structured["error"]
                record["warning_codes"] = sorted({w["code"] for w in structured["warnings"]})
                record["stats"] = structured["stats"]
                (out / f"{path.stem}.structured.json").write_text(json.dumps(structured, indent=2, ensure_ascii=False))
        summary.append(record)
        print(json.dumps(record), flush=True)

    (out / "summary.json").write_text(json.dumps(summary, indent=2))


if __name__ == "__main__":
    main()
