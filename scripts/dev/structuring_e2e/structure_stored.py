"""Phase 2 of the E2E: structured extraction over stored real OCR outputs
(<stem>.ocr.json from run_e2e.py) against the live local LLM -- the same
StructuredExtractionRunner.run path POST /ocr/structured/{id} uses. Run
separately from OCR because the Docker VM can't hold Docling + PaddleOCR +
a 7B model at once.

usage: python structure_stored.py <results_dir> <stem> [<stem> ...]   (env LLM_* as for the worker)
"""
import json
import sys
import time
import uuid
from pathlib import Path

sys.path.insert(0, "/app/src")

from ocr_worker.config import Settings
from ocr_worker.structuring import build_structuring_runner


class MemoryStorage:
    def __init__(self):
        self.objects = {}

    def put_ocr_output_json(self, key, data):
        self.objects[key] = data
        return key


def main():
    results = Path(sys.argv[1])
    settings = Settings(llm_extraction_mode="sync")
    runner = build_structuring_runner(settings, MemoryStorage())
    print("LLM health:", json.dumps(runner.health()), flush=True)
    summary = []
    for stem in sys.argv[2:]:
        ocr = json.loads((results / f"{stem}.ocr.json").read_text())
        started = time.monotonic()
        result = runner.run(version_id=uuid.uuid4(), extracted_text_ref=f"ocr/e2e/{stem}.json", pages=ocr["pages"])
        record = {
            "document": stem,
            "status": result["status"],
            "wall_ms": int((time.monotonic() - started) * 1000),
            "document_type": result["document_type"],
            "n_fields": len(result["fields"]),
            "n_tables": len(result["tables"]),
            "n_sections": len(result["sections"]),
            "error": result["error"],
            "warning_codes": sorted({w["code"] for w in result["warnings"]}),
            "stats": result["stats"],
        }
        (results / f"{stem}.structured.json").write_text(json.dumps(result, indent=2, ensure_ascii=False))
        summary.append(record)
        print(json.dumps(record), flush=True)
    (results / "structured_summary.json").write_text(json.dumps(summary, indent=2))


if __name__ == "__main__":
    main()
