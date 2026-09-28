"""A/B baseline ("Pipeline A"): the EXISTING extraction -- PaddleOCR +
Docling output -> extraction.extract_requested_fields (label hints, spatial
label/value matching, value-shape patterns) -- run on exactly the stored OCR
output (<stem>.ocr.json) that Pipeline B (Qwen) structures.

Best case for the existing extractor: every ground-truth field is requested
as a DMS field whose label hints are the field's own label plus its key
spellings (what a tenant would configure). Its tables are Docling's grids
as they appear in the OCR output (first row = header); it has no document
classification. Writes <stem>.pipelineA.json in the structured-result shape
so evaluate_quality.py scores both pipelines identically.

usage (in the worker image): python ab_pipeline_a.py <results_dir> <stem>...
"""
import json
import re
import sys
import uuid
from pathlib import Path

sys.path.insert(0, "/app/src")

from ocr_worker import extraction as ex  # noqa: E402

HERE = Path(__file__).resolve().parent
TYPE_MAP = {"date": "date", "number": "text", "bool": "text", "email": "text", "text": "text"}


def specs_for(spec: dict) -> list[tuple[str, str, list[str]]]:
    out = []
    for entry in spec.get("fields", []) + spec.get("null_fields", []) + spec.get("repeated", []):
        keys = [k.lstrip("=") for k in entry["keys"]]
        label = re.sub(r"\s*\(.*\)", "", entry["label"]).strip()
        hints = [label] + [k.replace("_", " ") for k in keys if k.replace("_", " ").casefold() != label.casefold()]
        out.append((keys[0], TYPE_MAP.get(entry.get("type", "text"), "text"), hints))
    return out


def lines_by_page(pages: list[dict]) -> dict[int, list]:
    # Same sources pipeline.py uses: PyMuPDF lines for native pages,
    # PaddleOCR line boxes for scanned ones.
    result = {}
    for page in pages:
        source = page.get("lines") or page.get("blocks") or []
        rows = [(b["text"], tuple(b["bbox"])) for b in source if b.get("bbox")]
        if rows:
            result[page["page_number"]] = rows
    return result


def docling_tables(pages: list[dict]) -> list[dict]:
    tables = []
    for page in pages:
        for t in page.get("tables") or []:
            grid = [["" for _ in range(t["num_cols"])] for _ in range(t["num_rows"])]
            for c in t["cells"]:
                if c["row"] < t["num_rows"] and c["col"] < t["num_cols"]:
                    grid[c["row"]][c["col"]] = c["text"]
            if grid:
                tables.append({"name": None, "page": page["page_number"], "headers": grid[0], "rows": grid[1:]})
    return tables


def run(stem: str, spec: dict, results: Path) -> dict:
    ocr = json.loads((results / f"{stem}.ocr.json").read_text(encoding="utf-8"))
    pages = ocr["pages"]
    requested = specs_for(spec)
    field_specs = [ex.FieldSpec(field_key=k, data_type=t) for k, t, _ in requested]
    kwargs = dict(
        specs=field_specs,
        text="\n\n".join(p["text"] for p in pages),
        zone_fields={},
        lines_by_page=lines_by_page(pages),
        field_confidence_threshold=0.6,
    )
    if hasattr(ex, "build_label_vocabulary"):  # label-hint extraction (V40)
        ids = {k: uuid.uuid4() for k, _, _ in requested}
        kwargs["vocabulary"] = ex.build_label_vocabulary(
            document_type_id=None,
            field_rows=[ex.LabelFieldRow(ids[k], k, hints[0]) for k, _, hints in requested],
            alias_rows=[ex.LabelAliasRow(ids[k], None, h, (i + 1) * 10) for k, _, hints in requested for i, h in enumerate(hints)],
            requested_keys=[k for k, _, _ in requested],
        )
        kwargs["table_header_texts"] = {
            c["text"].strip().lower() for p in pages for t in p.get("tables") or [] for c in t["cells"] if c.get("column_header") and c.get("text")
        }
    results_list = ex.extract_requested_fields(**kwargs)
    return {
        "schema_version": 1,
        "status": "completed",
        "document_type": None,
        "fields": {r.field_key: (r.value if r.status != "not_found" else None) for r in results_list},
        "tables": docling_tables(pages),
        "sections": [],
        "warnings": [],
        "error": None,
        "model": {"provider": "none", "name": "pipeline-A (PaddleOCR + Docling + existing extraction)"},
        "stats": {"pages": len(pages), "llm_calls": 0},
        "match_basis": {r.field_key: getattr(r, "match_basis", None) for r in results_list},
    }


def main():
    results = Path(sys.argv[1])
    specs = json.loads((HERE / "expected.json").read_text(encoding="utf-8"))
    local = HERE / "expected.local.json"
    if local.exists():
        specs.update(json.loads(local.read_text(encoding="utf-8")))
    for stem in sys.argv[2:]:
        spec = specs[stem]
        spec = specs[spec["same_as"]] if "same_as" in spec else spec
        out = run(stem, spec, results)
        (results / f"{stem}.pipelineA.json").write_text(json.dumps(out, indent=1, ensure_ascii=False, default=str), encoding="utf-8")
        found = sum(v is not None for v in out["fields"].values())
        print(f"{stem}: {found}/{len(out['fields'])} requested fields filled")


if __name__ == "__main__":
    main()
