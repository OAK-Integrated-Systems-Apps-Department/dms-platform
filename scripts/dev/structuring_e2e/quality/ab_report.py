"""Side-by-side A/B table from evaluate_quality.py JSON reports.

usage: python ab_report.py <out.md> <label>=<report.json> [<label>=<report.json> ...]

Every number is computed from the per-check results in the reports (the
"guarded" view -- what the DMS receives -- plus the raw hallucination
count, i.e. values the grounding guard had to remove). Nothing is typed in.
"""
import json
import sys
from pathlib import Path

FIELD_KINDS = {"text", "date", "number", "bool", "email"}


def ratio(text):
    a, b = text.split("/")
    return int(a), int(b)


def aggregate(report: dict) -> dict:
    agg = dict(fields_ok=0, fields_total=0, missed=0, wrong=0, doctype_ok=0, doctype_total=0, cells_ok=0, cells_total=0,
               rep_ok=0, rep_total=0, null_ok=0, null_total=0, null_leaks=0, flagged=0, docs=0, docs_ok=0, time_ms=0, calls=0,
               sections_ok=0, sections_total=0)
    for doc in report["documents"].values():
        agg["docs"] += 1
        agg["docs_ok"] += doc["status"] in ("completed", "partial")
        agg["flagged"] += len(doc.get("flagged_paths") or [])
        agg["time_ms"] += (doc.get("stats") or {}).get("duration_ms") or 0
        agg["calls"] += (doc.get("stats") or {}).get("llm_calls") or 0
        for c in doc["guarded"]:
            kind, status = c["kind"], c["status"]
            if kind in FIELD_KINDS:
                agg["fields_total"] += 1
                agg["fields_ok"] += status == "correct"
                agg["missed"] += status == "missed"
                agg["wrong"] += status == "wrong"
            elif kind == "doctype":
                agg["doctype_total"] += 1
                agg["doctype_ok"] += status == "correct"
            elif kind == "table_summary":
                ok, total = ratio(c["cells_correct"])
                agg["cells_ok"] += ok
                agg["cells_total"] += total
            elif kind == "repeated":
                agg["rep_total"] += 1
                agg["rep_ok"] += status == "correct"
            elif kind == "null":
                agg["null_total"] += 1
                agg["null_ok"] += status == "correct"
                agg["null_leaks"] += status == "invented"
            elif kind == "section":
                agg["sections_total"] += 1
                agg["sections_ok"] += status == "correct"
    return agg


def pct(a, b):
    return f"{100 * a / b:.0f}% ({a}/{b})" if b else "–"


def main():
    out = Path(sys.argv[1])
    runs = [(arg.split("=", 1)[0], aggregate(json.loads(Path(arg.split("=", 1)[1]).read_text(encoding="utf-8")))) for arg in sys.argv[2:]]
    rows = [
        ("Correct fields (label→value)", lambda a: pct(a["fields_ok"], a["fields_total"])),
        ("Missed fields", lambda a: str(a["missed"])),
        ("Wrong mappings", lambda a: str(a["wrong"])),
        ("Table cell accuracy (header→row)", lambda a: pct(a["cells_ok"], a["cells_total"])),
        ("Repeated fields complete", lambda a: pct(a["rep_ok"], a["rep_total"])),
        ("Blank fields returned as null", lambda a: pct(a["null_ok"], a["null_total"])),
        ("Document type correct", lambda a: pct(a["doctype_ok"], a["doctype_total"])),
        ("Sections found", lambda a: pct(a["sections_ok"], a["sections_total"])),
        ("Hallucinations reaching the DMS (values in blank fields)", lambda a: str(a["null_leaks"])),
        ("Values the model invented (removed by the grounding guard)", lambda a: str(a["flagged"])),
        ("Documents with valid, schema-checked JSON", lambda a: pct(a["docs_ok"], a["docs"])),
        ("LLM calls / total structuring time", lambda a: f"{a['calls']} / {a['time_ms'] / 1000:.0f} s"),
    ]
    lines = ["| metric | " + " | ".join(label for label, _ in runs) + " |", "|---|" + "---|" * len(runs)]
    lines += ["| " + name + " | " + " | ".join(fn(agg) for _, agg in runs) + " |" for name, fn in rows]
    out.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print("\n".join(lines))


if __name__ == "__main__":
    main()
