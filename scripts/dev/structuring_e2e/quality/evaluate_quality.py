"""Extraction-quality evaluation of structured results against ground truth.

usage: python evaluate_quality.py <results_dir> <report_basename> [--min-field-accuracy 0.8]

Reads <results_dir>/<stem>.structured.json (produced by structure_stored.py
with LLM_GROUNDING_POLICY=flag, so the model's raw output is kept) plus
quality/expected.json and, if present, quality/expected.local.json. Every
check is scored twice:

- raw:     exactly what the model returned;
- guarded: with every value the grounding guard flagged replaced by null --
           what production (LLM_GROUNDING_POLICY=nullify) returns.

Writes <report_basename>.md and .json. Exit code 1 when a hard check fails:
a must-be-null field carries a value in the guarded output, a document
fails to structure, or guarded field accuracy is below --min-field-accuracy.
Values of documents marked "redact" never appear in the report.

Deliberately independent of ocr_worker's own normalization code (its own
date/number parsing), so a bug there can't also hide in the evaluation.
"""
from __future__ import annotations

import argparse
import copy
import json
import re
import sys
import unicodedata
from datetime import date, datetime
from pathlib import Path

HERE = Path(__file__).resolve().parent

# ----------------------------------------------------------------------
# normalization (independent of the system under test)
# ----------------------------------------------------------------------
def canon(value) -> str:
    text = unicodedata.normalize("NFKC", str(value)).casefold()
    return re.sub(r"[^\w]+", " ", text).strip()


def compact(value) -> str:
    return canon(value).replace(" ", "")


def number_of(value) -> str | None:
    if isinstance(value, bool) or value is None:
        return None
    if isinstance(value, (int, float)):
        value = f"{value:.6f}"
    tokens = re.findall(r"\d[\d,]*(?:\.\d+)?", str(value))
    if not tokens:
        return None
    token = max(tokens, key=len).replace(",", "")
    if "." in token:
        token = token.rstrip("0").rstrip(".")
    return token.lstrip("0") or "0"


def digits_of(value) -> str:
    return re.sub(r"\D", "", str(value))


_DATE_FORMATS = ("%Y-%m-%d", "%d/%m/%Y", "%m/%d/%Y", "%d.%m.%Y", "%d-%m-%Y", "%Y/%m/%d", "%d %B %Y", "%d %b %Y", "%B %d, %Y", "%B %d %Y")


def dates_of(value) -> set[date]:
    found = set()
    text = str(value)
    for candidate in re.findall(r"\d{4}-\d{2}-\d{2}|\d{1,2}[/.\-]\d{1,2}[/.\-]\d{4}|\d{1,2} [A-Za-z]+ \d{4}|[A-Za-z]+ \d{1,2},? \d{4}", text):
        for fmt in _DATE_FORMATS:
            try:
                found.add(datetime.strptime(candidate, fmt).date())
            except ValueError:
                pass
    return found


def is_blank(value) -> bool:
    return value is None or (isinstance(value, str) and not re.sub(r"[\s_\-.]+", "", value)) or value == [] or value == {}


def value_matches(expected, actual, kind: str, accept=()) -> bool:
    if is_blank(actual):
        return False
    if isinstance(actual, list):
        return any(value_matches(expected, a, kind, accept) for a in actual)
    if isinstance(actual, dict):
        return any(value_matches(expected, a, kind, accept) for a in actual.values())
    for alternative in accept:
        if canon(alternative) == canon(actual):
            return True
    if kind == "date":
        return date.fromisoformat(expected) in dates_of(actual)
    if kind == "number":
        if number_of(expected) == number_of(actual):
            return True
        return len(digits_of(expected)) >= 7 and digits_of(expected) == digits_of(actual)  # phone numbers
    if kind == "bool":
        truthy = {"yes", "true", "y", "1"}
        return (canon(actual) in truthy or actual is True) == (canon(expected) in truthy)
    if kind == "email":
        return str(actual).strip().casefold() == str(expected).strip().casefold()
    e, a = canon(expected), canon(actual)
    if e == a or compact(expected) == compact(actual):
        return True
    # tolerate a little surrounding text ("Oak ... Ltd - Human Resources")
    return bool(e) and (e in a or compact(expected) in compact(actual)) and len(a) <= 2 * len(e)


# ----------------------------------------------------------------------
# locating values in the model output
# ----------------------------------------------------------------------
def flatten(fields: dict) -> list[tuple[str, object]]:
    """[(path, leaf-or-list value)] with dict nesting flattened: a list of
    scalars stays one entry (a repeated field), a list of objects is
    expanded."""
    out: list[tuple[str, object]] = []

    def walk(prefix, value):
        if isinstance(value, dict):
            for k, v in value.items():
                walk(f"{prefix}_{k}" if prefix else k, v)
        elif isinstance(value, list) and any(isinstance(v, dict) for v in value):
            for v in value:
                walk(prefix, v)
        else:
            out.append((prefix, value))

    walk("", fields)
    return out


def key_tokens(path: str) -> set[str]:
    return set(canon(path.replace("_", " ")).split())


def candidates(fields: dict, keys: list[str]) -> list[tuple[str, object]]:
    found = []
    for path, value in flatten(fields):
        normalized = canon(path.replace("_", " ")).replace(" ", "_")
        for key in keys:
            if key.startswith("="):
                if normalized == key[1:]:
                    found.append((path, value))
                    break
            elif set(key.split("_")) <= key_tokens(path):
                found.append((path, value))
                break
    return found


def leaves(value) -> list:
    if isinstance(value, list):
        return [x for v in value for x in leaves(v)]
    if isinstance(value, dict):
        return [x for v in value.values() for x in leaves(v)]
    return [value]


# ----------------------------------------------------------------------
# checks
# ----------------------------------------------------------------------
def check_field(spec, result) -> dict:
    kind = spec.get("type", "text")
    found = candidates(result["fields"], spec["keys"])
    non_blank = [(p, v) for p, v in found if not is_blank(v)]
    hit = next(((p, v) for p, v in non_blank if value_matches(spec["value"], v, kind, spec.get("accept", ()))), None)
    if hit:
        status = "correct"
    elif non_blank:
        status = "wrong"
    else:
        status = "missed"
    shown = hit or (non_blank[0] if non_blank else (found[0] if found else None))
    return {"check": f"field: {spec['label']}", "expected": spec["value"], "actual": shown[1] if shown else None,
            "path": shown[0] if shown else None, "status": status, "kind": kind}


def check_null(spec, result) -> dict:
    found = [(p, v) for p, v in candidates(result["fields"], spec["keys"]) if not is_blank(v)]
    return {"check": f"must be null/absent: {spec['label']}", "expected": None, "actual": found[0][1] if found else None,
            "path": found[0][0] if found else None, "status": "invented" if found else "correct", "kind": "null"}


def check_repeated(spec, result) -> dict:
    kind = spec.get("type", "text")
    values = [x for _, v in candidates(result["fields"], spec["keys"]) for x in leaves(v) if not is_blank(x)]
    hits = [e for e in spec["values"] if any(value_matches(e, v, kind) for v in values)]
    status = "correct" if len(hits) == len(spec["values"]) else ("partial" if hits else "missed")
    return {"check": f"repeated: {spec['label']}", "expected": spec["values"], "actual": values, "status": status,
            "kind": "repeated", "found": f"{len(hits)}/{len(spec['values'])}"}


def _header_index(headers, name):
    target = canon(name)
    for i, h in enumerate(headers):
        if h is not None and (canon(h) == target or target in canon(h)):
            return i
    return None


def check_table(spec, result, results_dir: Path) -> list[dict]:
    rows = spec.get("rows")
    if rows is None:
        truth = json.loads((results_dir.parent / "docs" / spec["rows_from_truth"]).read_text())
        by_ref = {r[spec["key_columns"][0]]: r for r in truth}
        rows = [by_ref[ref] for ref in spec["sample_references"]]
        rows = [{h: r[h] for h in spec["headers"]} for r in rows]
    types = spec.get("types", {})
    tables = [t for t in result["tables"] if all(_header_index(t["headers"], h) is not None for h in spec["key_columns"])]
    all_rows = [(t, row) for t in tables for row in t["rows"]]
    checks = []
    cells_total = cells_ok = 0
    for expected_row in rows:
        match = None
        for table, row in all_rows:
            idx = [_header_index(table["headers"], k) for k in spec["key_columns"]]
            if all(i is not None and i < len(row) and value_matches(expected_row[k], row[i], types.get(k, "text")) for i, k in zip(idx, spec["key_columns"])):
                match = (table, row)
                break
        label = " / ".join(expected_row[k] for k in spec["key_columns"])
        if match is None:
            # Not in any table: did the values at least land in fields?
            flat = [v for _, v in flatten(result["fields"])]
            in_fields = all(any(value_matches(expected_row[k], v, types.get(k, "text")) for v in flat) for k in spec["key_columns"])
            status = "in_fields_only" if in_fields else "missed"
            cells_total += len(expected_row) - len(spec["key_columns"])
            checks.append({"check": f"table row: {label}", "expected": expected_row, "actual": None, "status": status, "kind": "table"})
            continue
        table, row = match
        wrong = {}
        for column, expected in expected_row.items():
            if column in spec["key_columns"]:
                continue
            cells_total += 1
            i = _header_index(table["headers"], column)
            actual = row[i] if i is not None and i < len(row) else None
            if expected == "" and is_blank(actual):
                cells_ok += 1
            elif value_matches(expected, actual, types.get(column, "text")):
                cells_ok += 1
            else:
                wrong[column] = actual
        checks.append({"check": f"table row: {label}", "expected": expected_row,
                       "actual": dict(zip([h or "" for h in table["headers"]], row)),
                       "status": "correct" if not wrong else "wrong", "kind": "table", "wrong_cells": wrong})
    summary = {"check": f"table: {spec['label']}", "kind": "table_summary", "tables_matched": len(tables),
               "cells_correct": f"{cells_ok}/{cells_total}", "rows_extracted": len(all_rows),
               "status": "correct" if cells_total and cells_ok == cells_total else ("partial" if cells_ok else "missed")}
    if "expected_row_count" in spec:
        summary["expected_row_count"] = spec["expected_row_count"]
    return [summary] + checks


def check_section(spec, result) -> dict:
    ok = any(canon(spec["heading"]) in canon(s.get("heading") or "") and canon(spec["content"]) in canon(s.get("content") or "") for s in result["sections"])
    heading_only = any(canon(spec["heading"]) in canon(s.get("heading") or "") for s in result["sections"])
    return {"check": f"section: {spec['heading']}", "expected": f"heading~{spec['heading']}, content contains {spec['content']!r}",
            "actual": [s.get("heading") for s in result["sections"]], "status": "correct" if ok else ("partial" if heading_only else "missed"), "kind": "section"}


def check_document_type(keywords, result) -> dict:
    actual = result.get("document_type") or ""
    ok = any(k in canon(actual).replace(" ", "_") for k in keywords)
    return {"check": "document type", "expected": " | ".join(keywords), "actual": actual or None, "status": "correct" if ok else "wrong", "kind": "doctype"}


# ----------------------------------------------------------------------
def guarded_view(result: dict) -> dict:
    """What LLM_GROUNDING_POLICY=nullify would have returned: every flagged
    path set to null."""
    view = copy.deepcopy(result)
    for warning in result.get("warnings", []):
        if warning.get("code") != "UNGROUNDED_VALUE":
            continue
        tokens = re.findall(r"\.?([^.\[\]]+)|\[(\d+)\]", warning["path"])
        parts = [int(i) if i else k for k, i in tokens]
        target = view
        for part in parts[:-1]:
            target = target[part]
        target[parts[-1]] = None
    return view


def evaluate(spec: dict, result: dict, results_dir: Path) -> list[dict]:
    checks = [check_document_type(spec["document_type"], result)]
    checks += [check_field(f, result) for f in spec.get("fields", [])]
    checks += [check_null(f, result) for f in spec.get("null_fields", [])]
    checks += [check_repeated(f, result) for f in spec.get("repeated", [])]
    for table in spec.get("tables", []):
        checks += check_table(table, result, results_dir)
    checks += [check_section(s, result) for s in spec.get("sections", [])]
    return checks


def score(checks, kinds) -> tuple[int, int]:
    relevant = [c for c in checks if c["kind"] in kinds]
    return sum(c["status"] == "correct" for c in relevant), len(relevant)


def _redacted(checks: list[dict]) -> list[dict]:
    keep = {"check", "status", "kind", "found", "cells_correct", "rows_extracted", "tables_matched", "expected_row_count", "path"}
    return [{k: (v if k in keep else "•••") for k, v in c.items()} for c in checks]


def fmt(value, redact: bool) -> str:
    if redact and value not in (None, "", [], {}):
        return "•••"
    text = json.dumps(value, ensure_ascii=False) if not isinstance(value, str) else value
    return (text[:90] + "…") if len(text) > 90 else text


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("results_dir")
    parser.add_argument("report")
    parser.add_argument("--min-field-accuracy", type=float, default=0.8)
    parser.add_argument("--suffix", default="structured", help="score <stem>.<suffix>.json (e.g. pipelineA)")
    args = parser.parse_args()
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")
    results_dir = Path(args.results_dir)

    specs = json.loads((HERE / "expected.json").read_text(encoding="utf-8"))
    local = HERE / "expected.local.json"
    if local.exists():
        specs.update(json.loads(local.read_text(encoding="utf-8")))
    specs = {k: v for k, v in specs.items() if not k.startswith("_")}

    report, failures, lines = {}, [], []
    lines.append("# Structured-extraction quality report\n")
    run_info = next((json.loads(p.read_text())["model"] for p in sorted(results_dir.glob(f"*.{args.suffix}.json"))), {})
    lines.append(f"Model: `{run_info.get('name')}` via `{run_info.get('provider')}`. Scores are **raw** (model output as returned) and **guarded** (after the grounding guard nulls values not found in the document -- what the DMS receives).\n")
    summary_rows = []
    for stem, spec in specs.items():
        base = specs[spec["same_as"]] if "same_as" in spec else spec
        path = results_dir / f"{stem}.{args.suffix}.json"
        if not path.exists():
            continue
        result = json.loads(path.read_text(encoding="utf-8"))
        redact = bool(spec.get("redact"))
        if result["status"] not in ("completed", "partial"):
            failures.append(f"{stem}: status {result['status']} ({(result.get('error') or {}).get('code')})")
        raw = evaluate(base, result, results_dir)
        guarded = evaluate(base, guarded_view(result), results_dir)
        flagged = [w["path"] for w in result.get("warnings", []) if w.get("code") == "UNGROUNDED_VALUE"]
        field_kinds = {c["kind"] for c in raw if c["check"].startswith("field:")} | {"doctype"}
        f_raw, f_total = score(raw, field_kinds)
        f_guard, _ = score(guarded, field_kinds)
        null_bad = [c for c in guarded if c["kind"] == "null" and c["status"] != "correct"]
        null_bad_raw = [c for c in raw if c["kind"] == "null" and c["status"] != "correct"]
        rep_ok, rep_total = score(guarded, {"repeated"})
        tbl = [c for c in guarded if c["kind"] == "table_summary"]
        sec_ok, sec_total = score(guarded, {"section"})
        accuracy = f_guard / f_total if f_total else 1.0
        if accuracy < args.min_field_accuracy:
            failures.append(f"{stem}: guarded field accuracy {accuracy:.0%} < {args.min_field_accuracy:.0%}")
        failures += [f"{stem}: {c['check']} carries a value" for c in null_bad]
        stats = result.get("stats", {})
        summary_rows.append((stem, result["status"], f"{f_raw}/{f_total}", f"{f_guard}/{f_total}",
                             f"{len(null_bad_raw)}→{len(null_bad)}", f"{rep_ok}/{rep_total}" if rep_total else "–",
                             ", ".join(t["cells_correct"] for t in tbl) or "–", f"{sec_ok}/{sec_total}" if sec_total else "–",
                             str(len(flagged)), f"{stats.get('llm_calls')}", f"{(stats.get('duration_ms') or 0) / 1000:.0f}s"))
        report[stem] = {"status": result["status"], "raw": _redacted(raw) if redact else raw, "guarded": _redacted(guarded) if redact else guarded,
                        "flagged_paths": flagged, "stats": stats}

        lines.append(f"\n## {stem}\n")
        lines.append(f"{spec['description']}\n")
        lines.append(f"Status `{result['status']}`, {stats.get('pages')} page(s), {stats.get('chunks_processed')} chunk(s), "
                     f"{stats.get('llm_calls')} LLM call(s), {stats.get('prompt_tokens')} prompt + {stats.get('completion_tokens')} completion tokens, "
                     f"{(stats.get('duration_ms') or 0) / 1000:.0f} s. Values the guard flagged as not in the document: {len(flagged)}"
                     + (f" ({', '.join(flagged[:8])}{'…' if len(flagged) > 8 else ''})" if flagged and not redact else "") + ".\n")
        lines.append("| check | expected | actual (guarded) | raw | guarded |")
        lines.append("|---|---|---|---|---|")
        for r, g in zip(raw, guarded):
            if g["kind"] == "table_summary":
                lines.append(f"| {g['check']} | {str(g['expected_row_count']) + ' rows' if 'expected_row_count' in g else '–'} | {g['rows_extracted']} rows in {g['tables_matched']} matching table(s), cells {g['cells_correct']} | {r['cells_correct']} | {g['cells_correct']} |")
                continue
            actual = g.get("actual")
            if g["kind"] == "table":
                actual = g.get("wrong_cells") or ("row found" if g["status"] == "correct" else g["status"])
            lines.append(f"| {g['check']} | {fmt(g.get('expected'), redact)} | {fmt(actual, redact)} | {r['status']} | {g['status']} |")

    lines.insert(2, "\n| document | status | fields raw | fields guarded | null leaks raw→guarded | repeated | table cells | sections | flagged | calls | time |\n|---|---|---|---|---|---|---|---|---|---|---|\n"
                 + "\n".join("| " + " | ".join(row) + " |" for row in summary_rows) + "\n")
    lines.append("\n## Hard-check failures\n")
    lines.append("\n".join(f"- {f}" for f in failures) if failures else "None.")
    Path(args.report + ".md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    Path(args.report + ".json").write_text(json.dumps({"failures": failures, "documents": report}, indent=1, ensure_ascii=False, default=str), encoding="utf-8")
    print("\n".join(lines[:3]))
    print("FAILURES:" if failures else "all hard checks passed", *failures, sep="\n  ")
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main()
