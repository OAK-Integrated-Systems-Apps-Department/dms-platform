"""`python -m eval.check_labels` -- read a reviewed label and complain.

A label is the most expensive artifact in this harness: somebody read a
real document and typed what it says. It is also the *ruler*. A malformed
label does not fail loudly -- it scores the pipeline wrong, quietly, in
the direction of "the pipeline is broken", and the natural response to
that is to go and change the pipeline. `metrics.py`'s own docstring
records exactly that happening once already: `amount` read 0% across the
whole corpus and looked like a product defect, and it was the ruler that
was wrong.

So this checker exists to catch the ruler before it is used. Its rules
are derived from how `metrics.field_matches` actually compares values,
not from a style guide -- every ERROR here is a value that would be
scored as a mismatch even when the pipeline read the document correctly.

**It never writes.** Not to labels, not to anything. It reads, it prints,
it exits 1 if it found an ERROR. A tool that "helpfully fixed" a label
would be editing ground truth to agree with a machine, which is the one
thing this whole harness is built to prevent.

By default it looks only at labels marked `reviewed: true`, since an
unreviewed draft is the pipeline's own output and complaining about it
says nothing. `--include-pending` also reports on drafts, separately and
as information only.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from dataclasses import dataclass
from datetime import date
from decimal import Decimal, InvalidOperation
from pathlib import Path

from . import metrics
from . import realset
from . import schema as schema_mod

ERROR = "ERROR"
WARN = "WARN"

# Keys the drafter writes as scaffolding for the human. Harmless to leave
# behind -- nothing reads them -- but their presence in a label marked
# reviewed usually means the review was not finished.
_DRAFT_KEYS = ("_README", "_draft_notes")

_ISO_DATE = re.compile(r"^\d{4}-\d{2}-\d{2}$")
# Mirrors metrics._NUMERIC_RE. Kept as its own copy deliberately: this
# checker's job is to say what the SCORER will accept, so if that regex
# changes, this one should be changed in step and the difference noticed.
_NUMERIC = re.compile(r"^-?[\d,\s]*\d(?:\.\d+)?$")

# The shapes people actually type instead of ISO, worth naming in the
# message rather than saying "not a date".
_SLASHED = re.compile(r"^(\d{1,2})[/.-](\d{1,2})[/.-](\d{4})$")
_YEAR_LAST_TWO = re.compile(r"^\d{1,2}[/.-]\d{1,2}[/.-]\d{2}$")


@dataclass(frozen=True)
class Finding:
    doc_id: str
    severity: str
    field: str | None
    message: str

    def render(self) -> str:
        where = f"{self.doc_id}" + (f".{self.field}" if self.field else "")
        return f"  {self.severity:5}  {where}: {self.message}"


# ----------------------------------------------------------------------
# Per-value rules
# ----------------------------------------------------------------------
def check_date(doc_id: str, field: str, value) -> list[Finding]:
    """Labels store dates as ISO `YYYY-MM-DD`, because that is what the
    pipeline emits (`extraction.py` parses whatever the document printed
    and calls `.isoformat()`). A label in the document's own format is
    compared as a string against the ISO form and always loses."""
    if not isinstance(value, str):
        return [Finding(doc_id, ERROR, field, f"a date must be a string, got {type(value).__name__}")]

    text = value.strip()
    if not text:
        return [Finding(doc_id, ERROR, field, "empty -- delete the key instead of leaving it blank")]

    if not _ISO_DATE.match(text):
        slashed = _SLASHED.match(text)
        if slashed:
            a, b, year = slashed.groups()
            # Say what it would have to become, and say plainly that we
            # cannot tell which of the two numbers is the month.
            hint = (
                f"write it as {year}-{b.zfill(2)}-{a.zfill(2)} if {a} is the day, "
                f"or {year}-{a.zfill(2)}-{b.zfill(2)} if {a} is the month"
            )
            return [Finding(doc_id, ERROR, field, f"{text!r} is not ISO -- {hint}")]
        if _YEAR_LAST_TWO.match(text):
            return [Finding(doc_id, ERROR, field, f"{text!r} has a two-digit year -- write the full year, as YYYY-MM-DD")]
        return [Finding(doc_id, ERROR, field, f"{text!r} is not a date in YYYY-MM-DD form")]

    try:
        parsed = date.fromisoformat(text)
    except ValueError:
        return [Finding(doc_id, ERROR, field, f"{text!r} is not a real date")]

    findings = []
    # Not an error -- a contract really can run to 2031 -- but a date
    # decades away is nearly always a mis-keyed year, and it is cheap to
    # ask.
    if parsed.year < 1990 or parsed.year > 2100:
        findings.append(Finding(doc_id, WARN, field, f"year {parsed.year} looks mis-keyed"))
    return findings


def check_decimal(doc_id: str, field: str, value) -> list[Finding]:
    """Amounts are plain decimals: digits, one `.`, no currency symbol.

    `metrics._as_decimal` is anchored end to end, so anything it cannot
    parse falls back to a string comparison -- which means `"$1,234.50"`
    is scored against `"1,234.50"` and marked wrong even though the
    pipeline read the number perfectly."""
    if isinstance(value, (int, float, Decimal)):
        return [Finding(
            doc_id, WARN, field,
            "written as a JSON number -- quote it as a string so trailing zeros survive "
            "(1234.50, not 1234.5)",
        )]
    if not isinstance(value, str):
        return [Finding(doc_id, ERROR, field, f"an amount must be a string, got {type(value).__name__}")]

    text = value.strip()
    if not text:
        return [Finding(doc_id, ERROR, field, "empty -- delete the key instead of leaving it blank")]

    if not _NUMERIC.match(text):
        symbol = next((s for s in ("$", "£", "€", "Rs", "LKR", "USD", "EUR", "GBP") if s in text), None)
        if symbol:
            return [Finding(
                doc_id, ERROR, field,
                f"{text!r} contains {symbol!r} -- the amount is digits only; the currency "
                f"goes in the `currency` field",
            )]
        return [Finding(doc_id, ERROR, field, f"{text!r} is not a number")]

    try:
        Decimal(text.replace(",", "").replace(" ", ""))
    except InvalidOperation:
        return [Finding(doc_id, ERROR, field, f"{text!r} is not a number")]

    findings = []
    if "," in text or " " in text:
        # Scored correctly -- field_matches compares numerically -- but
        # the canonical form has no separators, and a mix across labels
        # makes them harder to eyeball against each other.
        findings.append(Finding(
            doc_id, WARN, field,
            f"{text!r} has thousands separators; the canonical form is "
            f"{text.replace(',', '').replace(' ', '')}",
        ))
    return findings


def check_dropdown(doc_id: str, field: str, value, options: list[str] | None) -> list[Finding]:
    if not isinstance(value, str):
        return [Finding(doc_id, ERROR, field, f"a dropdown value must be a string, got {type(value).__name__}")]
    text = value.strip()
    if not text:
        return [Finding(doc_id, ERROR, field, "empty -- delete the key instead of leaving it blank")]
    if not options:
        # `language`, `project_code` and `cost_centre` are dropdowns with
        # no seeded options (V30). There is nothing to check against, and
        # inventing a list here would reject valid values.
        return []
    if text in options:
        return []
    # Case is the usual cause, and it matters: `field_matches` casefolds,
    # so "USD" and "usd" both score -- but `currency` is a code and the
    # rest of the system writes it uppercase.
    match = next((o for o in options if o.casefold() == text.casefold()), None)
    if match:
        return [Finding(doc_id, WARN, field, f"{text!r} should be written {match!r}")]
    return [Finding(
        doc_id, ERROR, field,
        f"{text!r} is not one of {', '.join(options)}",
    )]


def check_multi_select(doc_id: str, field: str, value) -> list[Finding]:
    """A multi_select value is a LIST.

    `metrics.normalize_field_value` sorts and comma-joins a list, and also
    accepts the JSON-encoded string form because that is what ocr-worker
    actually emits. What it does NOT accept is a human-written
    `"audited, renewal"` -- that folds to one string containing a comma
    and never equals the two-element list the pipeline produces."""
    if isinstance(value, list):
        bad = [v for v in value if not isinstance(v, str)]
        if bad:
            return [Finding(doc_id, ERROR, field, f"every entry must be a string; found {bad!r}")]
        if not value:
            return [Finding(doc_id, WARN, field, "empty list -- delete the key instead")]
        return []

    if not isinstance(value, str):
        return [Finding(doc_id, ERROR, field, f"must be a list of strings, got {type(value).__name__}")]

    text = value.strip()
    if text.startswith("[") and text.endswith("]"):
        try:
            decoded = json.loads(text)
        except ValueError:
            return [Finding(doc_id, ERROR, field, f"{text!r} looks like JSON but does not parse")]
        if isinstance(decoded, list):
            # The encoded form is what ocr-worker emits, so it scores --
            # but a human-written label should just use a real list.
            return [Finding(doc_id, WARN, field, "JSON-encoded list; a plain list reads better in a label")]
        return [Finding(doc_id, ERROR, field, f"{text!r} decodes to {type(decoded).__name__}, not a list")]

    if "," in text:
        parts = [p.strip() for p in text.split(",") if p.strip()]
        return [Finding(
            doc_id, ERROR, field,
            f"{text!r} is one string, not a list -- write {json.dumps(parts)}. "
            f"A comma-joined string never matches the list the pipeline produces",
        )]
    # A single value with no comma folds to the same string as a
    # one-element list, so it genuinely scores -- WARN, not ERROR. The
    # first draft of this checker had it as an ERROR, and the test that
    # asserts every ERROR is really unscoreable caught it.
    return [Finding(
        doc_id, WARN, field,
        f"{text!r} is a string -- write {json.dumps([text])}; it scores either way, "
        f"but a list is the shape the field actually has",
    )]


def check_text(doc_id: str, field: str, value) -> list[Finding]:
    if not isinstance(value, str):
        return [Finding(doc_id, ERROR, field, f"must be a string, got {type(value).__name__}")]
    if not value.strip():
        return [Finding(doc_id, ERROR, field, "empty -- delete the key instead of leaving it blank")]
    findings = []
    if value != value.strip():
        # Folded away by the scorer, so harmless -- but it is usually a
        # sign the value was pasted rather than read.
        findings.append(Finding(doc_id, WARN, field, "has leading or trailing whitespace"))
    return findings


_CHECKERS = {
    "date": check_date,
    "decimal": check_decimal,
    "multi_select": check_multi_select,
}


# ----------------------------------------------------------------------
# Per-label rules
# ----------------------------------------------------------------------
def check_line_items(doc_id: str, items) -> list[Finding]:
    """`line_items` is OPTIONAL and currently unscored (see the module
    docstring in `realset.py`). It is checked anyway because a reviewer
    typing them now is far cheaper than a second pass over every document
    later, and a malformed one is worth catching while the document is
    still open in front of them."""
    if items is None:
        return []
    if not isinstance(items, list):
        return [Finding(doc_id, ERROR, "line_items", f"must be a list, got {type(items).__name__}")]

    findings: list[Finding] = []
    for index, item in enumerate(items):
        where = f"line_items[{index}]"
        if not isinstance(item, dict):
            findings.append(Finding(doc_id, ERROR, where, f"must be an object, got {type(item).__name__}"))
            continue
        unknown = sorted(set(item) - {"description", "quantity", "unit_price", "line_total"})
        if unknown:
            findings.append(Finding(
                doc_id, ERROR, where,
                f"unknown key(s) {', '.join(unknown)} -- a line has description, quantity, "
                f"unit_price and line_total",
            ))
        for key in ("quantity", "unit_price", "line_total"):
            if key in item and item[key] is not None:
                findings.extend(check_decimal(doc_id, f"{where}.{key}", item[key]))
        if "description" in item and item["description"] is not None:
            findings.extend(check_text(doc_id, f"{where}.description", item["description"]))

        # Arithmetic, only when all three are present and parseable. A
        # line whose own numbers disagree is nearly always a typo in the
        # label, and it is the one error a reviewer cannot see by reading.
        numbers = {}
        for key in ("quantity", "unit_price", "line_total"):
            raw = item.get(key)
            if isinstance(raw, str) and _NUMERIC.match(raw.strip()):
                try:
                    numbers[key] = Decimal(raw.strip().replace(",", "").replace(" ", ""))
                except InvalidOperation:
                    pass
        if len(numbers) == 3:
            product = numbers["quantity"] * numbers["unit_price"]
            # A cent of rounding is the document's, not the reviewer's.
            if abs(product - numbers["line_total"]) > Decimal("0.01"):
                findings.append(Finding(
                    doc_id, WARN, where,
                    f"quantity x unit_price = {product} but line_total says {numbers['line_total']}",
                ))
    return findings


def check_label(label: dict, *, source: str) -> list[Finding]:
    doc_id = label.get("doc_id") or source
    findings: list[Finding] = []

    type_code = (label.get("type_code") or "").strip().upper()
    if type_code not in schema_mod.TYPE_FIELDS:
        # Everything below is relative to the type, so there is nothing
        # useful to say once this is wrong.
        return [Finding(
            doc_id, ERROR, "type_code",
            f"{label.get('type_code')!r} is not one of {', '.join(sorted(schema_mod.TYPE_FIELDS))}",
        )]

    fields = label.get("fields")
    if fields is None:
        return [Finding(doc_id, ERROR, "fields", "missing -- a reviewed label with no fields scores nothing")]
    if not isinstance(fields, dict):
        return [Finding(doc_id, ERROR, "fields", f"must be an object, got {type(fields).__name__}")]

    allowed = {key: mandatory for key, mandatory in schema_mod.TYPE_FIELDS[type_code]}

    for field, value in fields.items():
        if field not in allowed:
            known = field in schema_mod.DATA_TYPES
            findings.append(Finding(
                doc_id, ERROR, field,
                f"is not a {type_code} field"
                + (
                    f" -- it belongs to another type, so either delete it or fix type_code"
                    if known
                    else " and is not a field at all"
                ),
            ))
            continue
        if value is None:
            findings.append(Finding(
                doc_id, ERROR, field,
                "is null -- a field the document does not contain is DELETED, not set to null",
            ))
            continue

        data_type = schema_mod.DATA_TYPES.get(field, "text")
        checker = _CHECKERS.get(data_type)
        if checker is not None:
            findings.extend(checker(doc_id, field, value))
        elif data_type == "dropdown":
            findings.extend(check_dropdown(doc_id, field, value, schema_mod.OPTIONS.get(field)))
        else:
            findings.extend(check_text(doc_id, field, value))

    # A mandatory field the document genuinely lacks is a real and
    # interesting case -- it is what `requires_review` exists for -- so
    # this is a WARN asking for confirmation, never an ERROR.
    for field, mandatory in allowed.items():
        if mandatory and field not in fields:
            findings.append(Finding(
                doc_id, WARN, field,
                f"mandatory for {type_code} but absent -- correct if the document does show it",
            ))

    findings.extend(check_line_items(doc_id, label.get("line_items")))

    leftover = [key for key in _DRAFT_KEYS if key in label]
    if leftover:
        findings.append(Finding(
            doc_id, WARN, None,
            f"still carries {', '.join(leftover)} from the draft -- usually means the review is unfinished",
        ))

    # Splits the real-set report, so a string "true" or a missing key
    # would silently move a document into the printed population and
    # count it against OS-NFR-001's 95% bar.
    handwritten = label.get("handwritten")
    if handwritten is not None and not isinstance(handwritten, bool):
        findings.append(Finding(
            doc_id, ERROR, "handwritten",
            f"must be true or false, got {handwritten!r}",
        ))

    reference_text = label.get("reference_text")
    if reference_text is not None and not isinstance(reference_text, str):
        findings.append(Finding(doc_id, ERROR, "reference_text", "must be a string (empty is fine)"))
    elif isinstance(reference_text, str):
        # Same floor `run.py` applies, for the same reason and from the
        # same function: a short reference is one field value in the wrong
        # key, and scoring against it reports the engine's CER as the
        # ratio of a whole page to that one token. The real set produced
        # 55.0 this way. ERROR, not WARN -- a label that silently distorts
        # the metric is exactly what this checker exists to stop.
        problem = metrics.reference_text_problem(reference_text)
        if problem:
            findings.append(Finding(doc_id, ERROR, "reference_text", problem))

    return findings


# ----------------------------------------------------------------------
# Entry point
# ----------------------------------------------------------------------
def collect(*, include_pending: bool) -> tuple[list[Finding], list[Finding], int, int]:
    """(findings on reviewed, findings on pending, n_reviewed, n_pending).

    Reads only. `realset.load_labels` is not reused because it discards
    the file path, and a finding that cannot name the file it came from
    makes the reviewer grep for it.
    """
    if not realset.REAL_LABELS_DIR.is_dir():
        return [], [], 0, 0

    reviewed_findings: list[Finding] = []
    pending_findings: list[Finding] = []
    n_reviewed = n_pending = 0

    for path in sorted(realset.REAL_LABELS_DIR.glob("*.json")):
        try:
            label = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, ValueError) as exc:
            reviewed_findings.append(Finding(path.stem, ERROR, None, f"could not be read: {exc}"))
            continue
        if not isinstance(label, dict):
            reviewed_findings.append(Finding(path.stem, ERROR, None, "is not a JSON object"))
            continue

        if label.get("reviewed") is True:
            n_reviewed += 1
            reviewed_findings.extend(check_label(label, source=path.stem))
        else:
            n_pending += 1
            if include_pending:
                pending_findings.extend(check_label(label, source=path.stem))

    return reviewed_findings, pending_findings, n_reviewed, n_pending


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Check reviewed real-document labels for malformed values. Never modifies anything.",
    )
    parser.add_argument(
        "--include-pending",
        action="store_true",
        help="also report on unreviewed drafts, as information only (never affects the exit code)",
    )
    parser.add_argument(
        "--strict",
        action="store_true",
        help="exit 1 on warnings too, not just errors",
    )
    args = parser.parse_args(argv)

    reviewed, pending, n_reviewed, n_pending = collect(include_pending=args.include_pending)

    if not realset.REAL_LABELS_DIR.is_dir():
        print(f"[check] no label directory at {realset.REAL_LABELS_DIR} -- nothing to check.")
        return 0

    print(f"[check] {n_reviewed} reviewed label(s), {n_pending} awaiting review")

    errors = [f for f in reviewed if f.severity == ERROR]
    warnings = [f for f in reviewed if f.severity == WARN]

    if reviewed:
        print("")
        print(f"[check] reviewed labels -- {len(errors)} error(s), {len(warnings)} warning(s):")
        for finding in errors + warnings:
            print(finding.render())
    elif n_reviewed:
        print("[check] reviewed labels: clean.")

    if args.include_pending:
        print("")
        if pending:
            print(f"[check] drafts awaiting review -- {len(pending)} note(s), for information only:")
            for finding in pending:
                print(finding.render())
        else:
            print("[check] drafts awaiting review: nothing to note.")

    print("")
    if errors:
        print(f"[check] FAIL -- {len(errors)} error(s) would score the pipeline against a malformed label.")
        return 1
    if warnings and args.strict:
        print(f"[check] FAIL (--strict) -- {len(warnings)} warning(s).")
        return 1
    if warnings:
        print(f"[check] OK -- {len(warnings)} warning(s), none of which change a score.")
        return 0
    print("[check] OK -- nothing malformed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
