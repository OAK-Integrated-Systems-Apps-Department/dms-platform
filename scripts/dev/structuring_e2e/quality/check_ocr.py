"""Assertions on the real PaddleOCR + Docling run (run_e2e.py's summary.json
and <stem>.ocr.json): the right engine per page, text actually recognized,
Docling tables where the native documents have them, and the negative
documents rejected with their codes. Exit 1 on any failure."""
import json
import sys
from pathlib import Path

results = Path(sys.argv[1])
summary = {Path(r["document"]).stem: r for r in json.loads((results / "summary.json").read_text())}
EXPECT = {
    # stem: (engine, must contain text, min Docling tables)
    "employee_contract_native": ("native_text", "EMP-1023", 1),
    "employee_contract_scan": ("paddleocr", "EMP-1023", 0),
    "invoice_scanned": ("paddleocr", "INV-2026-0457", 0),
    "bank_statement_3pages_native": ("native_text", "TXN100075", 1),
    "vendor_form_native": ("native_text", "PV 104522", 0),
    "hr_difficult_native": ("native_text", "Colombo Head Office", 1),
    "hr_difficult_scanned": ("paddleocr", "Colombo Head Office", 0),
    "real_purchase_order": ("native_text", "4400051211", 1),
}
REJECT = {"corrupted": "CORRUPTED_DOCUMENT", "renamed_exe": "UNSUPPORTED_FORMAT"}
failures = []
for stem, (engine, needle, min_tables) in EXPECT.items():
    record = summary.get(stem)
    if record is None:
        if stem != "real_purchase_order":
            failures.append(f"{stem}: not processed")
        continue
    if record["ocr_status"] != "completed":
        failures.append(f"{stem}: OCR {record['ocr_status']}: {record.get('ocr_error')}")
        continue
    methods = {p["method"] for p in record["pages"]}
    if methods != {engine}:
        failures.append(f"{stem}: engine {methods}, expected {engine}")
    ocr = json.loads((results / f"{stem}.ocr.json").read_text(encoding="utf-8"))
    if needle.replace(" ", "") not in ocr["text"].replace(" ", ""):
        failures.append(f"{stem}: expected text not recognized")
    if sum(p["tables"] for p in record["pages"]) < min_tables:
        failures.append(f"{stem}: expected >= {min_tables} Docling table(s)")
    print(f"ok  {stem}: {engine}, {len(record['pages'])} page(s), {sum(p['blocks'] for p in record['pages'])} blocks, "
          f"{sum(p['tables'] for p in record['pages'])} table(s), {record['total_ms'] / 1000:.0f}s")
for stem, code in REJECT.items():
    record = summary.get(stem)
    if record is None or record["ocr_status"] != "failed" or not (record.get("ocr_error") or "").startswith(code):
        failures.append(f"{stem}: expected rejection with {code}, got {record and (record['ocr_status'], record.get('ocr_error'))}")
    else:
        print(f"ok  {stem}: rejected {code} in {record['total_ms']} ms")
blank = summary.get("blank_page")
if not blank or blank["ocr_status"] != "completed" or any(p["blocks"] for p in blank["pages"]):
    failures.append("blank_page: expected completed OCR with no text")
else:
    print("ok  blank_page: completed, no text (structuring must skip it with EMPTY_OCR_RESULT)")
print("\n".join(f"FAIL {f}" for f in failures) or "all OCR checks passed")
sys.exit(1 if failures else 0)
