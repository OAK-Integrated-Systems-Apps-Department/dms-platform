"""Scoring for the evaluation corpus -- section 5's required metrics.

Deliberately dependency-free and deliberately *independent of the
worker's own normalization code*. The label files store the canonical
value the pipeline is supposed to emit ("2026-03-14", "2500.00", "GBP"),
so the comparator here only has to fold away whitespace and case.
Reusing extraction.py's normalizers to compare would hide exactly the
class of bug this harness exists to catch: a normalizer that is
consistently wrong would score a perfect match against itself.
"""

from __future__ import annotations

import json
import math
import re
import unicodedata
from decimal import Decimal, InvalidOperation
from dataclasses import dataclass, field as dc_field
from typing import Iterable, Sequence


# ----------------------------------------------------------------------
# Edit distance -- CER / WER
# ----------------------------------------------------------------------
def levenshtein(a: Sequence, b: Sequence) -> int:
    """Two-row DP. O(len(a) * len(b)) time, O(min) space.

    Pure Python on purpose: the corpus is ~90 documents of a few thousand
    characters, so this runs in well under a second in total, and a C
    extension (python-Levenshtein / rapidfuzz) would mean a new
    dependency in the worker image for the sake of a tool that never runs
    in production.
    """
    if a == b:
        return 0
    if len(a) < len(b):
        a, b = b, a
    if not b:
        return len(a)

    previous = list(range(len(b) + 1))
    for i, ca in enumerate(a, start=1):
        current = [i]
        for j, cb in enumerate(b, start=1):
            current.append(
                min(
                    previous[j] + 1,               # deletion
                    current[j - 1] + 1,            # insertion
                    previous[j - 1] + (ca != cb),  # substitution
                )
            )
        previous = current
    return previous[-1]


def normalize_text_for_cer(text: str) -> str:
    """Collapse whitespace runs and NFKC-fold.

    Reading order across a multi-column or tiled page legitimately varies
    in *where* the line breaks fall, and scoring that as character errors
    would measure layout reconstruction rather than recognition. Newlines
    therefore become single spaces. Case and punctuation are kept -- those
    are real recognition errors.
    """
    return re.sub(r"\s+", " ", unicodedata.normalize("NFKC", text)).strip()


def character_error_rate(reference: str, hypothesis: str) -> float:
    ref = normalize_text_for_cer(reference)
    hyp = normalize_text_for_cer(hypothesis)
    if not ref:
        # No reference text to score against: 0.0 when the hypothesis is
        # also empty, 1.0 when the engine invented text out of a blank
        # page. Never a ZeroDivisionError, and never silently "perfect".
        return 0.0 if not hyp else 1.0
    return levenshtein(ref, hyp) / len(ref)


def word_error_rate(reference: str, hypothesis: str) -> float:
    ref = normalize_text_for_cer(reference).split()
    hyp = normalize_text_for_cer(hypothesis).split()
    if not ref:
        return 0.0 if not hyp else 1.0
    return levenshtein(ref, hyp) / len(ref)


# ----------------------------------------------------------------------
# Field comparison
# ----------------------------------------------------------------------
def normalize_field_value(value) -> str:
    """Fold a field value to its comparable form.

    A multi_select field (tags/keywords) is compared as a sorted,
    comma-joined string so ordering -- which the output contract never
    promises -- is not scored as an error.

    It also arrives *JSON-encoded as a string*, not as a list: ocr-worker
    encodes multi_select values and dms-core's `applyOcrFields` decodes
    them with an ObjectMapper. That is the contract, so a value of
    `'["a", "b"]'` is correct and must compare equal to `["a", "b"]`.
    Scoring the encoded form as wrong would have reported every
    multi_select field at 0% accuracy and invited someone to "fix" a
    working contract.
    """
    if value is None:
        return ""
    if isinstance(value, (list, tuple)):
        return ",".join(sorted(normalize_field_value(v) for v in value))
    text = str(value).strip()
    if text.startswith("[") and text.endswith("]"):
        try:
            decoded = json.loads(text)
        except ValueError:
            pass
        else:
            if isinstance(decoded, list):
                return normalize_field_value(decoded)
    return re.sub(r"\s+", " ", unicodedata.normalize("NFKC", text)).casefold()


_NUMERIC_RE = re.compile(r"^-?[\d,\s]*\d(?:\.\d+)?$")


def _as_decimal(text: str):
    """The number a value represents, or None if it is not purely numeric.

    Anchored end to end so an identifier that merely contains digits
    ("PO-74300", "CC-1042") is never compared numerically -- only a value
    that is *entirely* a number, with optional thousands separators.
    """
    if not text or not _NUMERIC_RE.match(text):
        return None
    try:
        return Decimal(text.replace(",", "").replace(" ", ""))
    except InvalidOperation:
        return None


def field_matches(expected, actual) -> bool:
    """Exact match after folding, with one deliberate exception.

    **Numeric values compare numerically.** The pipeline emits a decimal
    with whatever thousands separator the document printed -- a real
    invoice total comes back as `"41,252.70"` -- and that is correct: the
    output contract keeps the raw OCR string, and dms-core's `parseNumber`
    strips the comma before storing it (a bug it already found and fixed
    live). Comparing those as strings scored `amount` at 0% across the
    entire corpus and looked exactly like a product defect. It was the
    ruler that was wrong.

    Only fully-numeric values take this path, so `PO-74300` and
    `CC-1042` are still compared as the identifiers they are.
    """
    expected_text = normalize_field_value(expected)
    actual_text = normalize_field_value(actual)
    if expected_text == actual_text:
        return True

    expected_number = _as_decimal(expected_text)
    actual_number = _as_decimal(actual_text)
    if expected_number is not None and actual_number is not None:
        return expected_number == actual_number
    return False


@dataclass
class FieldCounts:
    """Per-field tallies across the whole corpus.

    `produced` counts documents where the pipeline emitted any value at
    all; `expected` counts documents whose label has a value for this
    field. Precision is over what was produced, recall over what was
    expected -- so a field the pipeline never attempts scores recall 0
    rather than an undefined precision.
    """

    expected: int = 0
    produced: int = 0
    correct: int = 0
    # `extracted`-status precision (section 5): of the values the pipeline
    # was confident enough to mark "extracted", how many were right.
    status_extracted: int = 0
    status_extracted_correct: int = 0

    @property
    def precision(self) -> float:
        return self.correct / self.produced if self.produced else 0.0

    @property
    def recall(self) -> float:
        return self.correct / self.expected if self.expected else 0.0

    @property
    def f1(self) -> float:
        p, r = self.precision, self.recall
        return 2 * p * r / (p + r) if (p + r) else 0.0

    @property
    def exact_match(self) -> float:
        """Fraction of documents that expected this field and got it right.

        Same numerator and denominator as recall; kept under its own name
        because section 5 names both, and because a reader reasonably
        expects "exact match" to mean "how often was it simply correct".
        """
        return self.recall

    @property
    def extracted_precision(self) -> float:
        if not self.status_extracted:
            return 0.0
        return self.status_extracted_correct / self.status_extracted

    def to_dict(self) -> dict:
        return {
            "expected": self.expected,
            "produced": self.produced,
            "correct": self.correct,
            "precision": round(self.precision, 4),
            "recall": round(self.recall, 4),
            "f1": round(self.f1, 4),
            "exact_match": round(self.exact_match, 4),
            "status_extracted": self.status_extracted,
            "status_extracted_correct": self.status_extracted_correct,
            "extracted_precision": round(self.extracted_precision, 4),
        }


def accumulate_field_counts(
    counts: dict[str, FieldCounts],
    expected_fields: dict,
    actual_fields: dict,
    field_statuses: dict | None = None,
) -> None:
    """Fold one document's result into the running per-field tallies.

    `actual_fields` is the pipeline's output `fields` dict:
    {field_key: {"value": ..., "confidence": ..., "status": ...}}.
    The legacy full-document path omits `status`, so a present value with
    no status counts as produced but not as `extracted` -- it has not
    claimed that confidence, and scoring it as though it had would
    flatter the extracted-precision number.
    """
    for key in expected_fields:
        counts.setdefault(key, FieldCounts()).expected += 1

    statuses = field_statuses or {}
    for key, entry in (actual_fields or {}).items():
        bucket = counts.setdefault(key, FieldCounts())
        value = entry.get("value") if isinstance(entry, dict) else entry
        # `document_ocr_field_results.status` is authoritative; the
        # output dict carries no status at all on the selectable-field
        # path, and none on the legacy path either.
        status = statuses.get(key)
        if status is None and isinstance(entry, dict):
            status = entry.get("status")
        if value is None or normalize_field_value(value) == "":
            continue
        bucket.produced += 1
        ok = key in expected_fields and field_matches(expected_fields[key], value)
        if ok:
            bucket.correct += 1
        if status == "extracted":
            bucket.status_extracted += 1
            if ok:
                bucket.status_extracted_correct += 1


# ----------------------------------------------------------------------
# Latency
# ----------------------------------------------------------------------
def percentile(values: Iterable[float], pct: float) -> float:
    """Nearest-rank percentile.

    Nearest-rank rather than linear interpolation so a reported p95 is
    always a value some document actually produced -- at corpus sizes
    this small, an interpolated figure invents a number nothing observed.
    """
    ordered = sorted(v for v in values if v is not None)
    if not ordered:
        return 0.0
    rank = max(1, math.ceil(pct / 100.0 * len(ordered)))
    return ordered[min(rank, len(ordered)) - 1]


@dataclass
class LatencySummary:
    samples: list[float] = dc_field(default_factory=list)

    def add(self, seconds: float) -> None:
        self.samples.append(seconds)

    def to_dict(self) -> dict:
        return {
            "n": len(self.samples),
            "p50": round(percentile(self.samples, 50), 3),
            "p95": round(percentile(self.samples, 95), 3),
            "max": round(max(self.samples), 3) if self.samples else 0.0,
            "total": round(sum(self.samples), 3),
        }


# ----------------------------------------------------------------------
# Quality-flag scoring (Step 5 calibration)
# ----------------------------------------------------------------------
@dataclass
class BinaryCounts:
    """Confusion counts for a boolean signal against a known condition.

    Used to calibrate the poor-image-quality flags: the corpus knows
    which documents it degraded, so the condition label *is* the ground
    truth and no extra annotation is needed.
    """

    tp: int = 0
    fp: int = 0
    fn: int = 0
    tn: int = 0

    def add(self, predicted: bool, actual: bool) -> None:
        if predicted and actual:
            self.tp += 1
        elif predicted and not actual:
            self.fp += 1
        elif not predicted and actual:
            self.fn += 1
        else:
            self.tn += 1

    @property
    def precision(self) -> float:
        denom = self.tp + self.fp
        return self.tp / denom if denom else 0.0

    @property
    def recall(self) -> float:
        denom = self.tp + self.fn
        return self.tp / denom if denom else 0.0

    def to_dict(self) -> dict:
        return {
            "tp": self.tp,
            "fp": self.fp,
            "fn": self.fn,
            "tn": self.tn,
            "precision": round(self.precision, 4),
            "recall": round(self.recall, 4),
        }
