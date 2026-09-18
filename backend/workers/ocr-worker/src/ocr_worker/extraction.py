from __future__ import annotations

import re
from dataclasses import dataclass

# OS-FR-005: structured field extraction (invoice no., dates, IDs, form
# fields) from OCR output text. Regex-based on purpose -- this is meant to
# catch common, well-formatted layouts (the "standard document layouts"
# OS-NFR-001 scopes accuracy targets to), not to be a general NLP extractor.
# Zone/rubber-band OCR (OS-FR-003/004) is the mechanism for arbitrary or
# non-standard layouts; this module additionally runs over full-page text
# as a best-effort fallback.

_INVOICE_NUMBER_RE = re.compile(r"\b(?:invoice|inv)\b\.?\s*(?:no\.?|number|#)?\s*[:\-]?\s*([A-Z0-9][A-Z0-9\-/]{2,20})", re.IGNORECASE)
_PO_NUMBER_RE = re.compile(r"\b(?:purchase\s*order|po)\b\.?\s*(?:no\.?|number|#)?\s*[:\-]?\s*([A-Z0-9][A-Z0-9\-/]{2,20})", re.IGNORECASE)
_DATE_RE = re.compile(r"\b(\d{1,2}[/\-.]\d{1,2}[/\-.]\d{2,4}|\d{4}-\d{2}-\d{2}|(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\.?\s+\d{1,2},?\s+\d{4})\b", re.IGNORECASE)
_AMOUNT_RE = re.compile(r"(?:total\s*(?:due|amount)?|amount\s*due|balance\s*due)\s*[:\-]?\s*\$?\s*([\d,]+\.\d{2})", re.IGNORECASE)
_ID_RE = re.compile(r"\b([A-Z]{2,5}-?\d{4,12})\b")


@dataclass(frozen=True)
class ExtractedField:
    field: str
    value: str
    confidence: float
    source: str  # "regex" | "zone:<zone_name>"
    bbox: tuple[int, int, int, int] | None = None


def _first_match(pattern: re.Pattern[str], text: str, field: str, source: str, confidence: float) -> ExtractedField | None:
    match = pattern.search(text)
    if not match:
        return None
    value = match.group(1) if match.groups() else match.group(0)
    return ExtractedField(field=field, value=value.strip(), confidence=confidence, source=source)


def extract_structured_fields(text: str, base_confidence: float = 0.9, source: str = "regex") -> list[ExtractedField]:
    """Best-effort structured extraction over free text (full-page/native-
    text or a single zone/rubber-band crop). `base_confidence` should be
    derived from the OCR engine's (or native-text extraction's) own
    confidence for the text it ran over -- extraction confidence can
    never exceed that. No bbox: a regex match over concatenated text has
    no single spatial location to report.
    """
    if not text or not text.strip():
        return []

    candidates = [
        _first_match(_INVOICE_NUMBER_RE, text, "invoice_number", source, base_confidence),
        _first_match(_PO_NUMBER_RE, text, "id_number", source, base_confidence),
        _first_match(_DATE_RE, text, "invoice_date", source, base_confidence * 0.95),
        _first_match(_AMOUNT_RE, text, "total_amount", source, base_confidence),
        _first_match(_ID_RE, text, "id_number", source, base_confidence * 0.85),
    ]
    seen_fields: set[str] = set()
    results: list[ExtractedField] = []
    for candidate in candidates:
        if candidate is None or candidate.field in seen_fields:
            continue
        seen_fields.add(candidate.field)
        results.append(candidate)
    return results


def field_for_zone_name(zone_field: str, text: str, confidence: float, zone_name: str, bbox: tuple[int, int, int, int] | None = None) -> ExtractedField:
    """A zone template already tells us *which* field a region maps to --
    no regex guessing needed, the whole cropped region's text *is* the
    value, and the zone's own pixel box is a real bbox for it.
    """
    return ExtractedField(field=zone_field, value=text.strip(), confidence=confidence, source=f"zone:{zone_name}", bbox=bbox)


def fields_to_dict(fields: list[ExtractedField]) -> dict[str, dict]:
    """The output contract's `fields` object is a dict keyed by field
    name, not the list this module builds internally -- this is the one
    place that conversion happens, so pipeline.py doesn't have to.
    """
    return {
        f.field: {"value": f.value, "confidence": f.confidence, "bbox": list(f.bbox) if f.bbox else None}
        for f in fields
    }
