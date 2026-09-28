"""Phase 2 of the E2E: structured extraction over stored real OCR outputs
(<stem>.ocr.json from run_e2e.py) against the live local LLM -- the same
StructuredExtractionRunner.run path POST /ocr/structured/{id} uses. Run
separately from OCR because the Docker VM can't hold Docling + PaddleOCR +
a 7B model at once.

usage: python structure_stored.py <results_dir> [--targets] [--tag NAME] <stem> [<stem> ...]
  --targets  also pass the document's DMS fields (quality/expected*.json:
             key, label, alternative spellings, type) -- the same field list
             ab_pipeline_a.py gives the existing extractor
  --tag      write <stem>.<NAME>.json instead of <stem>.structured.json
(env LLM_* as for the worker)
"""
import json
import re
import sys
import time
import uuid
from pathlib import Path

sys.path.insert(0, "/app/src")

from ocr_worker.config import Settings
from ocr_worker.structuring import build_structuring_runner

QUALITY = Path(__file__).resolve().parent / "quality"
TYPE_MAP = {"date": "date", "number": "number", "bool": "text", "email": "text", "text": "text"}


class MemoryStorage:
    def __init__(self):
        self.objects = {}

    def put_ocr_output_json(self, key, data):
        self.objects[key] = data
        return key


def targets_for(stem: str) -> list:
    from ocr_worker.structuring.prompts import TargetField  # not in builds before targeted fields

    specs = json.loads((QUALITY / "expected.json").read_text(encoding="utf-8"))
    local = QUALITY / "expected.local.json"
    if local.exists():
        specs.update(json.loads(local.read_text(encoding="utf-8")))
    spec = specs[stem]
    spec = specs[spec["same_as"]] if "same_as" in spec else spec
    out = []
    for entry in spec.get("fields", []) + spec.get("null_fields", []) + spec.get("repeated", []):
        keys = [k.lstrip("=") for k in entry["keys"]]
        label = re.sub(r"\s*\(.*\)", "", entry["label"]).strip()
        hints = tuple(k.replace("_", " ") for k in keys if k.replace("_", " ").casefold() != label.casefold())
        out.append(TargetField(key=keys[0], label=label, hints=hints, data_type=TYPE_MAP.get(entry.get("type", "text"), "text")))
    return out


def main():
    args = sys.argv[1:]
    results = Path(args.pop(0))
    use_targets = "--targets" in args
    tag = "structured"
    if "--tag" in args:
        tag = args[args.index("--tag") + 1]
        args.remove("--tag"); args.remove(tag)
    stems = [a for a in args if a != "--targets"]
    settings = Settings(llm_extraction_mode="sync")
    runner = build_structuring_runner(settings, MemoryStorage())
    print("LLM health:", json.dumps(runner.health()), flush=True)
    summary = []
    for stem in stems:
        ocr = json.loads((results / f"{stem}.ocr.json").read_text())
        started = time.monotonic()
        extra = {"targets": targets_for(stem)} if use_targets else {}  # older builds have no targets
        result = runner.run(version_id=uuid.uuid4(), extracted_text_ref=f"ocr/e2e/{stem}.json", pages=ocr["pages"], **extra)
        record = {
            "document": stem, "tag": tag, "status": result["status"], "wall_ms": int((time.monotonic() - started) * 1000),
            "document_type": result["document_type"], "n_fields": len(result["fields"]), "n_tables": len(result["tables"]),
            "n_sections": len(result["sections"]), "error": result["error"],
            "warning_codes": sorted({w["code"] for w in result["warnings"]}), "stats": result["stats"],
        }
        (results / f"{stem}.{tag}.json").write_text(json.dumps(result, indent=2, ensure_ascii=False))
        summary.append(record)
        print(json.dumps(record), flush=True)
    (results / f"summary.{tag}.json").write_text(json.dumps(summary, indent=2))


if __name__ == "__main__":
    main()
