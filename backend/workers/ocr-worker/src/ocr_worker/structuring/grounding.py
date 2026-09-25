"""Hallucination guard: every value the model extracts must be traceable to
text of the chunk it was extracted from.

A value is "supported" when, after case/punctuation/whitespace folding, it
occurs in the chunk's source text -- or it is an unambiguous normalization
of something that does: an ISO date the source spells as 01/09/2026 or
"1 September 2026", or a number the source writes as "150,000.00". Long
free text (section content) is accepted on 85% word coverage, since the
model legitimately rejoins lines and hyphenation. Anything else is treated
as invented: under the default "nullify" policy it becomes null (rule 8 of
the task: a value that can't be confidently extracted is null, never
guessed) and its JSON path -- never its value -- is reported in warnings.

`document_type` and field/header names are exempt: they are the model's
labels for the document, not values copied out of it.
"""
from __future__ import annotations

import re
import unicodedata
from datetime import date, datetime

from .errors import UNGROUNDED_VALUE
from .schema import ExtractionResult

_NON_WORD = re.compile(r"[^\w]+", re.UNICODE)
_NUMBER_TOKEN = re.compile(r"\d[\d,.]*\d|\d")
# "150 000" style digit grouping -- indexed in addition to, not instead of,
# the plain tokens (otherwise "Qty 2 3" would only index "23").
_SPACED_NUMBER_TOKEN = re.compile(r"\d{1,3}(?: \d{3})+(?:[.,]\d+)?")
_ISO_DATE = re.compile(r"^(\d{4})-(\d{2})-(\d{2})$")
_CURRENCY_NOISE = re.compile(r"^[\s$€£¥₹]*|[\s$€£¥₹]*$")
# Digit/letter lookarounds rather than \b: OCR glues labels to values
# ("Invoice Date12 September 2026"), which leaves no word boundary.
_DATE_CANDIDATE = re.compile(
    r"(?<!\d)\d{1,4}[/\-.]\d{1,2}[/\-.]\d{1,4}(?!\d)"
    r"|(?<!\d)\d{1,2}(?:st|nd|rd|th)?\s+[A-Za-z]{3,9}\.?,?\s+\d{4}(?!\d)"
    r"|(?<![A-Za-z])[A-Za-z]{3,9}\.?\s+\d{1,2}(?:st|nd|rd|th)?,?\s+\d{4}(?!\d)"
)
_DATE_FORMATS = (
    "%d/%m/%Y", "%m/%d/%Y", "%Y/%m/%d", "%d-%m-%Y", "%m-%d-%Y", "%Y-%m-%d", "%d.%m.%Y", "%Y.%m.%d",
    "%d/%m/%y", "%m/%d/%y", "%d-%m-%y", "%d.%m.%y",
    "%d %B %Y", "%d %b %Y", "%B %d %Y", "%b %d %Y",
)
_BOOLEAN_WORDS = {"yes", "no", "true", "false", "y", "n", "checked", "unchecked", "x", "✓", "✔", "☑", "☒"}
_COVERAGE_MIN_WORDS = 4
_COVERAGE_RATIO = 0.85

POLICIES = ("nullify", "flag", "off")


def canon(text: str) -> str:
    return _NON_WORD.sub(" ", unicodedata.normalize("NFKC", text).casefold()).strip()


def _normalize_number(token: str) -> str:
    digits = re.sub(r"[,\s]", "", token)
    if "." in digits:
        whole, _, fraction = digits.rpartition(".")
        fraction = fraction.rstrip("0")
        digits = f"{whole.replace('.', '')}.{fraction}" if fraction else whole.replace(".", "")
    return digits.lstrip("0") or "0"


def _parse_dates(text: str) -> set[date]:
    found: set[date] = set()
    for match in _DATE_CANDIDATE.finditer(text):
        raw = re.sub(r"(\d)(st|nd|rd|th)\b", r"\1", match.group(0)).replace(",", "").replace(".", " " if " " in match.group(0) else ".")
        raw = re.sub(r"\s+", " ", raw).strip()
        for fmt in _DATE_FORMATS:
            try:
                found.add(datetime.strptime(raw, fmt).date())
            except ValueError:
                continue
    return found


class SourceIndex:
    def __init__(self, texts: list[str]):
        joined = unicodedata.normalize("NFKC", "\n".join(texts))
        self._canon = f" {canon(joined)} "
        self._compact = self._canon.replace(" ", "")
        self._words = set(self._canon.split())
        self._numbers = {_normalize_number(m.group(0)) for m in _NUMBER_TOKEN.finditer(joined)}
        self._numbers |= {_normalize_number(m.group(0)) for m in _SPACED_NUMBER_TOKEN.finditer(joined)}
        self._dates: set[date] | None = None
        self._raw = joined

    def _date_set(self) -> set[date]:
        if self._dates is None:
            self._dates = _parse_dates(self._raw)
        return self._dates

    def supports(self, value) -> bool:
        if value is None:
            return True
        if isinstance(value, bool):
            return bool(self._words & _BOOLEAN_WORDS)
        if isinstance(value, (int, float)):
            number = str(int(value)) if float(value).is_integer() else repr(float(value))
            return _normalize_number(number) in self._numbers
        text = str(value)
        folded = canon(text)
        if not folded:
            return True
        if f" {folded} " in self._canon:
            return True
        iso = _ISO_DATE.match(text.strip())
        if iso:
            try:
                return date(int(iso.group(1)), int(iso.group(2)), int(iso.group(3))) in self._date_set()
            except ValueError:
                return False
        if self._supports_number(text):
            return True
        compact = folded.replace(" ", "")
        if len(compact) >= 4 and compact in self._compact:
            return True
        words = folded.split()
        if len(words) >= _COVERAGE_MIN_WORDS:
            covered = sum(1 for w in words if w in self._words)
            return covered / len(words) >= _COVERAGE_RATIO
        return False

    def _supports_number(self, text: str) -> bool:
        """"LKR 150000" / "150000.00" / "$1,234.5" against a source that
        writes "LKR 150,000": the digits must match a source number and any
        remaining words (currency codes, units) must occur in the source."""
        numbers = _NUMBER_TOKEN.findall(text)
        if len(numbers) != 1:
            return False
        if _normalize_number(numbers[0]) not in self._numbers:
            return False
        rest = canon(_CURRENCY_NOISE.sub("", text.replace(numbers[0], " ")))
        return all(word in self._words for word in rest.split())


def ground(result: ExtractionResult, source: SourceIndex, policy: str) -> tuple[ExtractionResult, list[dict]]:
    """Returns the (possibly nulled) result and one warning per unsupported
    value. `policy`: nullify (default) | flag (keep, warn) | off."""
    if policy == "off":
        return result, []
    unsupported: list[str] = []

    def check(value, path: str):
        if isinstance(value, dict):
            return {k: check(v, f"{path}.{k}") for k, v in value.items()}
        if isinstance(value, list):
            return [check(v, f"{path}[{i}]") for i, v in enumerate(value)]
        if source.supports(value):
            return value
        unsupported.append(path)
        return None if policy == "nullify" else value

    fields = {key: check(value, f"fields.{key}") for key, value in result.fields.items()}
    tables = []
    for t_index, table in enumerate(result.tables):
        rows = [[check(cell, f"tables[{t_index}].rows[{r}][{c}]") for c, cell in enumerate(row)] for r, row in enumerate(table.rows)]
        tables.append(table.model_copy(update={"rows": rows}))
    sections = []
    for s_index, section in enumerate(result.sections):
        sections.append(
            section.model_copy(
                update={
                    "heading": check(section.heading, f"sections[{s_index}].heading"),
                    "content": check(section.content, f"sections[{s_index}].content"),
                }
            )
        )
    grounded = result.model_copy(update={"fields": fields, "tables": tables, "sections": sections})
    action = "replaced with null" if policy == "nullify" else "kept but flagged"
    warnings = [
        {"code": UNGROUNDED_VALUE, "message": f"value not found in the document text; {action}", "path": path}
        for path in unsupported
    ]
    return grounded, warnings
