"""Final automated verification report (quality/final_report.local.md --
local: it covers the real DMS document, whose values stay redacted).

usage: python final_report.py <results_dir> <quality_dir> <live_model>

Per-document PASS needs, in the guarded (DMS-facing) Qwen output: a
completed/partial run, >= 80% of expected fields correct, no value in a
field that is blank on the document, and >= 80% of expected table cells
correct where the document has a table. Overall PASS additionally needs
Qwen to beat the rule-based extraction on total correct checks.
"""
import json
import re
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from ab_report import aggregate, pct  # noqa: E402

FIELD_KINDS = {"text", "date", "number", "bool", "email"}


def load(path):
    return json.loads(Path(path).read_text(encoding="utf-8")) if Path(path).exists() else None


def peak(log):
    best, text = 0.0, "–"
    for line in Path(log).read_text().splitlines() if Path(log).exists() else []:
        m = re.search(r"([\d.]+)\s*(GiB|MiB)", line)
        if m:
            value = float(m.group(1)) * (1024 if m.group(2) == "GiB" else 1)
            if value > best:
                best, text = value, line.strip()
    return text


def doc_verdict(doc):
    g = doc["guarded"]
    fields = [c for c in g if c["kind"] in FIELD_KINDS]
    ok = sum(c["status"] == "correct" for c in fields)
    leaks = [c for c in g if c["kind"] == "null" and c["status"] != "correct"]
    tables = [c for c in g if c["kind"] == "table_summary"]
    cells = [tuple(map(int, t["cells_correct"].split("/"))) for t in tables]
    reasons = []
    if doc["status"] not in ("completed", "partial"):
        reasons.append(f"status {doc['status']}")
    if fields and ok / len(fields) < 0.8:
        reasons.append(f"fields {ok}/{len(fields)}")
    if leaks:
        reasons.append(f"{len(leaks)} blank field(s) filled")
    for a, b in cells:
        if b and a / b < 0.8:
            reasons.append(f"table cells {a}/{b}")
    return ("PASS" if not reasons else "FAIL"), reasons, ok, len(fields), cells, leaks


def main():
    results, quality, live_model = Path(sys.argv[1]), Path(sys.argv[2]), sys.argv[3]
    steps = [line.split("\t") for line in (results / "steps.tsv").read_text().splitlines() if line.strip()] if (results / "steps.tsv").exists() else []
    reports = {p.stem.replace("report.local.", ""): load(p) for p in sorted(quality.glob("report.local.*.json"))}
    qwen_tags = [t for t in reports if t.startswith("qwen_")]
    primary = "liveB" if "liveB" in reports else (qwen_tags[0] if qwen_tags else None)
    baseline = "liveA" if primary == "liveB" and "liveA" in reports else "pipelineA"
    out = ["# OCR + Qwen structured extraction: automated verification report\n"]
    commit = subprocess.run(["git", "rev-parse", "--short", "HEAD"], capture_output=True, text=True).stdout.strip()
    out.append(f"Branch HEAD `{commit}` (+ working tree). Live model: `{live_model}`. Grounding guard ON for the DMS-facing numbers.\n")

    out.append("## Automated steps\n\n| step | result | time |\n|---|---|---|")
    out += [f"| {s[0]} | {s[1]} | {s[2] if len(s) > 2 else ''} |" for s in steps]
    ocr_log = results / "step.paddleocr_docling.log"
    if ocr_log.exists():
        checks = [l for l in ocr_log.read_text(errors="replace").splitlines() if l.startswith(("ok  ", "FAIL "))]
        out.append("\n### PaddleOCR + Docling checks\n\n" + "\n".join(f"- {c}" for c in checks))

    out.append("\n## Without Qwen vs with Qwen (same documents, same OCR input)\n")
    columns = [(label, reports[t]) for label, t in
               [("Without Qwen: rules, label hints configured (offline)", "pipelineA"), ("Without Qwen: live /ocr/process", "liveA")] +
               [(f"With Qwen {t[5:]} (offline, same OCR input)", t) for t in qwen_tags] +
               [(f"With Qwen: live DMS rows ({live_model})", "liveB")] if reports.get(t)]
    rows = [("Correct fields", lambda a: pct(a["fields_ok"], a["fields_total"])), ("Missed fields", lambda a: str(a["missed"])),
            ("Wrong mappings", lambda a: str(a["wrong"])), ("Table cell accuracy", lambda a: pct(a["cells_ok"], a["cells_total"])),
            ("Repeated fields complete", lambda a: pct(a["rep_ok"], a["rep_total"])), ("Blank fields kept null", lambda a: pct(a["null_ok"], a["null_total"])),
            ("Document type", lambda a: pct(a["doctype_ok"], a["doctype_total"])),
            ("Hallucinations reaching the DMS", lambda a: str(a["null_leaks"])),
            ("Invented values removed by the guard", lambda a: str(a["flagged"])),
            ("Valid schema-checked JSON", lambda a: pct(a["docs_ok"], a["docs"])),
            ("Structuring time (sum)", lambda a: f"{a['time_ms'] / 1000:.0f} s")]
    aggs = [(label, aggregate(rep)) for label, rep in columns]
    out.append("| metric | " + " | ".join(l for l, _ in aggs) + " |\n|---|" + "---|" * len(aggs))
    out += ["| " + name + " | " + " | ".join(fn(a) for _, a in aggs) + " |" for name, fn in rows]

    mem = [(p.stem.replace("memory.", ""), peak(p)) for p in sorted(results.glob("memory.*.log"))]
    if mem:
        out.append("\nPeak memory (docker stats): " + "; ".join(f"{k}: {v}" for k, v in mem))

    overall = "INCONCLUSIVE"
    if primary:
        out.append(f"\n## Per-document result (`{primary}`, vs `{baseline}`)\n")
        out.append("| document | result | fields correct | table cells | blank-field leaks | without Qwen: fields | why |\n|---|---|---|---|---|---|---|")
        verdicts = []
        base_docs = (reports.get(baseline) or {}).get("documents", {})
        for stem, doc in reports[primary]["documents"].items():
            verdict, reasons, ok, total, cells, leaks = doc_verdict(doc)
            verdicts.append(verdict)
            base = base_docs.get(stem)
            base_text = "–"
            if base:
                _, _, bok, btotal, _, _ = doc_verdict(base)
                base_text = f"{bok}/{btotal}"
            out.append(f"| {stem} | **{verdict}** | {ok}/{total} | {', '.join(f'{a}/{b}' for a, b in cells) or '–'} | {len(leaks)} | {base_text} | {'; '.join(reasons)} |")
            wrong = [c for c in doc["guarded"] if c["status"] in ("wrong", "missed", "invented", "partial", "in_fields_only")]
            if wrong:
                redact = any(v == "•••" for c in doc["guarded"] for v in c.values())
                detail = "; ".join(f"{c['check']} → {c['status']}" + ("" if redact or c.get("actual") in (None, "") else f" ({str(c.get('actual'))[:40]})") for c in wrong[:12])
                out.append(f"|  | | | | | | issues: {detail} |")
        b = aggregate(reports[primary])
        a = aggregate(reports[baseline]) if reports.get(baseline) else None
        score = lambda x: x["fields_ok"] + x["cells_ok"] + x["rep_ok"] + x["null_ok"] + x["doctype_ok"]  # noqa: E731
        improved = a is not None and score(b) > score(a)
        overall = "PASS" if all(v == "PASS" for v in verdicts) and improved else "FAIL"
        if a is not None:
            out.append(f"\nCorrect checks, rules vs Qwen: {score(a)} vs {score(b)} (fields + table cells + repeated + nulls + doc type).")

    browser = load(results / "browser_results.json")
    out.append("\n## Browser (DMS UI) verification\n")
    if browser:
        out.append(json.dumps(browser, indent=1, ensure_ascii=False))
    else:
        out.append("Not run by this script -- needs a signed-in browser session (see README \"Browser verification\").")
    out.append(f"\n## Overall: **{overall}**\n")
    target = quality / "final_report.local.md"
    target.write_text("\n".join(out) + "\n", encoding="utf-8")
    print("\n".join(out))


if __name__ == "__main__":
    main()
