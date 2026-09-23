from __future__ import annotations

import json
import re
from collections import Counter
from dataclasses import dataclass, replace
from datetime import datetime

try:
    from langdetect import detect_langs
    from langdetect.lang_detect_exception import LangDetectException
except ImportError:  # pragma: no cover - langdetect is a hard runtime dependency (pyproject.toml); only
    # absent in an environment that skipped `pip install -e .`, which unit tests for other modules
    # shouldn't fail over -- only the "language" field itself becomes unavailable in that case.
    detect_langs = None
    LangDetectException = Exception

# OS-FR-005: structured field extraction (invoice no., dates, IDs, form
# fields) from OCR output text. Regex-based on purpose -- this is meant to
# catch common, well-formatted layouts (the "standard document layouts"
# OS-NFR-001 scopes accuracy targets to), not to be a general NLP extractor.
# Zone/rubber-band OCR (OS-FR-003/004) is the mechanism for arbitrary or
# non-standard layouts; this module additionally runs over full-page text
# as a best-effort fallback.

_INVOICE_NUMBER_RE = re.compile(r"\b(?:invoice|inv)\b\.?\s*(?:no\.?|number|#)?\s*[:\-]?\s*((?=[A-Z0-9\-/]*\d)[A-Z0-9][A-Z0-9\-/]{2,20})", re.IGNORECASE)
_PO_NUMBER_RE = re.compile(r"\b(?:purchase\s*order|po)\b\.?\s*(?:no\.?|number|#)?\s*[:\-]?\s*((?=[A-Z0-9\-/]*\d)[A-Z0-9][A-Z0-9\-/]{2,20})", re.IGNORECASE)
_DATE_RE = re.compile(
    r"\b(\d{1,2}[/\-.]\d{1,2}[/\-.]\d{2,4}"
    r"|\d{4}-\d{2}-\d{2}"
    r"|\d{4}/\d{2}/\d{2}"
    r"|(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\.?\s+\d{1,2},?\s+\d{4}"
    # day-first month-name form (e.g. "05 January 2026") -- added alongside
    # extraction.py's day-first _DATE_FORMATS entries for the selectable
    # OCR field extraction feature's date fields (effective_date/expiry_date).
    r"|\d{1,2}\s+(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\.?,?\s+\d{4})\b",
    re.IGNORECASE,
)
_AMOUNT_RE = re.compile(r"(?:total\s*(?:due|amount)?|amount\s*due|balance\s*due)\s*[:\-]?\s*\$?\s*([\d,]+\.\d{2})", re.IGNORECASE)
_ID_RE = re.compile(r"\b([A-Z]{2,5}-?\d{4,12})\b")

# _DATE_RE matches whatever a real invoice happens to print (US-style
# slashes, dashes, dots, 2- or 4-digit years, "May 1, 2026"), but
# dms-core's metadata layer only accepts ISO dates for a `date` field
# (see DocumentMetadataServiceImpl#parseDate) -- without normalizing here,
# every non-ISO invoice date silently fails to map and the field just
# stays blank. Ambiguous numeric dates are read day-first (real invoice
# found live: a Sri Lankan pharmacy receipt's "03/01/2026" was read
# month-first as March 1 instead of January 3rd -- confirmed wrong by the
# receipt's own handwritten follow-up note, "Submitted to COC 08/01/2026",
# which only makes sense a few days AFTER a January 3rd invoice date, not
# before a March 1st one. Every real document seen in this system so far
# is day-first (Sri Lanka); an unambiguous month-first date, e.g. "2026-
# 03-01" ISO or "January 3 2026", still parses correctly regardless of
# this default.
_DATE_FORMATS = (
    "%Y-%m-%d",
    "%d/%m/%Y", "%d-%m-%Y", "%d.%m.%Y",
    "%d/%m/%y", "%d-%m-%y", "%d.%m.%y",
    "%m/%d/%Y", "%m-%d-%Y", "%m.%d.%Y",
    "%m/%d/%y", "%m-%d-%y", "%m.%d.%y",
    "%B %d %Y", "%b %d %Y",
    "%d %B %Y", "%d %b %Y",
    "%Y/%m/%d",
)


def _normalize_date(raw: str) -> str:
    """Best-effort reformat of a matched date into ISO (`YYYY-MM-DD`).
    Returns `raw` unchanged if nothing parses -- the field still surfaces
    for a human to read/correct, just not one dms-core's `date` field type
    can consume automatically.
    """
    cleaned = raw.replace(",", "")
    for fmt in _DATE_FORMATS:
        try:
            return datetime.strptime(cleaned, fmt).date().isoformat()
        except ValueError:
            continue
    return raw


@dataclass(frozen=True)
class ExtractedField:
    field: str
    value: str
    confidence: float
    source: str  # "regex" | "zone:<zone_name>"
    bbox: tuple[int, int, int, int] | None = None
    # 1-indexed page a zone-derived value came from; None for a regex match
    # over concatenated text (no single page). Only populated by
    # field_for_zone_name -- added for the selectable-field-extraction
    # feature (see extract_requested_fields below), which needs a page
    # number alongside bbox for document_ocr_field_results.source_page.
    page: int | None = None


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

    date_candidate = _first_match(_DATE_RE, text, "invoice_date", source, base_confidence * 0.95)
    if date_candidate is not None:
        date_candidate = replace(date_candidate, value=_normalize_date(date_candidate.value))

    candidates = [
        _first_match(_INVOICE_NUMBER_RE, text, "invoice_number", source, base_confidence),
        _first_match(_PO_NUMBER_RE, text, "id_number", source, base_confidence),
        date_candidate,
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


def field_for_zone_name(
    zone_field: str,
    text: str,
    confidence: float,
    zone_name: str,
    bbox: tuple[int, int, int, int] | None = None,
    page: int | None = None,
) -> ExtractedField:
    """A zone template already tells us *which* field a region maps to --
    no regex guessing needed, the whole cropped region's text *is* the
    value, and the zone's own pixel box is a real bbox for it.
    """
    return ExtractedField(field=zone_field, value=text.strip(), confidence=confidence, source=f"zone:{zone_name}", bbox=bbox, page=page)


def fields_to_dict(fields: list[ExtractedField]) -> dict[str, dict]:
    """The output contract's `fields` object is a dict keyed by field
    name, not the list this module builds internally -- this is the one
    place that conversion happens, so pipeline.py doesn't have to.
    """
    return {
        f.field: {"value": f.value, "confidence": f.confidence, "bbox": list(f.bbox) if f.bbox else None}
        for f in fields
    }


# =====================================================================
# Selectable OCR field extraction (My Documents upload page): the caller
# now names exactly which fields it wants ("requested_fields"), each with
# the field catalog's own data_type/options (dms-core owns that catalog,
# so it sends this metadata rather than ocr-worker querying for it) --
# nothing outside this list is ever attempted or returned. This is a
# parallel path to extract_structured_fields/fields_to_dict above, which
# stays untouched and keeps serving the legacy "requested_fields omitted"
# caller (scripts/dev/smoke_test_ocr.py, the RabbitMQ consumer path).
# =====================================================================

_REFERENCE_NUMBER_RE = re.compile(r"\b([A-Z]{2,6}[-/][A-Z0-9]{2,4}[-/][A-Z0-9]{2,10}|[A-Z]{2,6}[-/][A-Z0-9]{3,12})\b")

# Pattern-rule fallback for `currency`: real invoice found live with no
# "Currency:" label anywhere on the page at all -- LKR only ever appeared
# as a "(LKR)" column-header annotation and inline before amounts ("LKR
# 4,100,000.00"), never as a labeled field. A fixed ISO 4217 whitelist
# (not a generic "any 3 uppercase letters" pattern) avoids false-positives
# on common invoice acronyms that are also 3 letters (VAT, PVT, LTD).
_CURRENCY_CODES = (
    "USD", "EUR", "GBP", "LKR", "INR", "AUD", "CAD", "JPY", "CNY", "SGD",
    "AED", "SAR", "PKR", "BDT", "NPR", "ZAR", "NZD", "CHF", "HKD", "MYR",
    "THB", "PHP", "IDR", "VND", "KRW", "EGP", "QAR", "KWD", "BHD", "OMR",
)
_CURRENCY_CODE_RE = re.compile(r"\b(" + "|".join(_CURRENCY_CODES) + r")\b")

# Fallback for `vendor_supplier_name` when no "Vendor:"/"Supplier:" label
# exists at all -- real invoice found live: the vendor was only ever
# presented as a plain letterhead ("Oak Integrated Systems (Pvt) Ltd"
# directly under the "TAX INVOICE" title, followed by its address and
# contact line), with no label anywhere on the page to anchor a proximity
# match to.
_DOCUMENT_TITLE_LINES = {
    "invoice", "tax invoice", "sales invoice", "commercial invoice",
    "proforma invoice", "receipt", "payment receipt", "purchase order",
    "credit note", "debit note", "quotation", "quote", "statement",
}

# Short, explicit label variants per field -- matches this module's own
# "best-effort, not a general NLP extractor" ethos (see the module
# docstring above), not a generic heuristic. A field with no entry here
# falls back to its own key with underscores turned into spaces (e.g.
# "review_due_date" -> "review due date").
_LABEL_ALIASES: dict[str, list[str]] = {
    "author": ["author", "prepared by", "written by", "authored by"],
    "department": ["department", "dept", "division", "business unit"],
    "reference_number": [
        "reference number", "reference no", "ref no", "ref. no", "ref#", "ref #",
        "document ref", "document reference", "reference",
    ],
    "classification_level": ["classification level", "classification", "sensitivity"],
    "retention_class": ["retention class", "retention schedule", "retention"],
    "effective_date": ["effective date", "effective from", "effective"],
    "expiry_date": ["expiry date", "expiration date", "expires on", "valid until", "expires"],
    "description": ["description", "summary"],
    "tags": ["tags"],
    "keywords": ["keywords", "key words"],
    # Invoice-shaped fields (own field_key.replace("_", " ") alone worked
    # for the exact label "Invoice Number:", but a real document just as
    # often uses a synonym ("Inv #", "Reference No" on a PO) -- see the
    # field-isolation/synonym-matching bug report this table was added to
    # fix. Ordered most-specific-first on purpose: "total amount" must be
    # tried before the bare "amount" is required for "amount" specifically
    # (see the reasoning below), because "amount" alone matches inside a
    # table's "Net Amount"/"Unit Price" column header -- the first (and
    # therefore wrong) occurrence on a typical invoice, well before the
    # real total.
    "invoice_number": ["invoice number", "invoice no", "inv no", "inv#", "inv #", "invoice#"],
    "invoice_date": ["invoice date"],
    "purchase_order_number": ["purchase order number", "po number", "po no", "po#", "order number"],
    "currency": ["currency"],
    "vendor_supplier_name": ["vendor / supplier", "vendor/supplier", "vendor", "supplier", "billed by", "seller"],
    "cost_centre": ["cost centre", "cost center"],
    # "total" (bare) is deliberately LAST: a hospital/receipt-style
    # document routinely labels its grand total as just "TOTAL", with no
    # "Amount"/"Due" qualifier at all (verified live: a real channel
    # receipt's "TOTAL : 4900.00" line) -- but tried only after every more
    # specific alias above has already failed, since a document with
    # several "Total ___" lines (Total Discount, Subtotal, Grand Total)
    # should resolve via one of those first. "\btotal\b" does not match
    # inside "Subtotal" (no word boundary between "Sub" and "total" -- it's
    # one unbroken token), so that common false-positive is already safe.
    # "net total" added after a real pharmacy receipt found live: its
    # table had an "AMOUNT" *column header* (the per-line-item price),
    # and since "amount" sat earlier in this list than the generic "total"
    # fallback, it matched that column header first and grabbed the FIRST
    # line item's amount instead of the real "NET TOTAL" a few rows below
    # -- more specific total-labels must always be tried before "amount"
    # itself, which is really a last-resort fallback, not a preference.
    "amount": ["total amount", "total due", "grand total", "net total", "amount due", "net amount due", "amount", "total"],
}

# Extra clip-only stop labels: real header labels that commonly sit right
# next to an extractable field's own label/value on the same OCR'd line
# (e.g. "Vendor / Supplier: ... Ship To / Department: ...") but aren't
# themselves one of this app's metadata fields, so they'd never get an
# entry in _LABEL_ALIASES above. Only used to bound _clip_at_next_label --
# never matched as a field's own alias.
_EXTRA_STOP_LABELS: list[str] = ["ship to", "bill to", "sold to"]

# Obvious classification synonyms -> this app's seeded classification_level
# option values (see V30__seed_metadata_field_catalogue_and_document_types.sql).
# Deliberately small and explicit, same reasoning as _LABEL_ALIASES -- never
# a fuzzy/semantic match, and a synonym is only ever used if its target is
# actually present in the caller-supplied `options` (never invents a value
# outside that list).
_CLASSIFICATION_SYNONYMS: dict[str, str] = {
    "top secret": "confidential",
    "secret": "confidential",
    "private": "confidential",
    "sensitive": "confidential",
    "shared": "public",
    "open": "public",
    "unclassified": "public",
}

_STOPWORDS = frozenset(
    "the a an and or of to in on for with by from at as is are was were be been being this that "
    "these those it its into over under between within your our their his her they them we you "
    "not no yes will shall may can have has had".split()
)


@dataclass(frozen=True)
class FieldSpec:
    """One entry of the `requested_fields` request payload -- see api.py's
    `RequestedField`. Kept as a plain dataclass here (rather than importing
    api.py's pydantic model) so extraction.py/pipeline.py have no pydantic
    dependency of their own.
    """

    field_key: str
    data_type: str
    options: list[str] | None = None


@dataclass(frozen=True)
class RequestedFieldResult:
    """One row of what document_ocr_field_results stores (and, when
    status != 'not_found', of what the HTTP response's sparse `fields`
    dict also carries -- see requested_fields_to_output_dict). `value` is
    a plain string for every field EXCEPT a multi_select field
    (tags/keywords), where it's a `list[str]`.
    """

    field_key: str
    value: str | list[str] | None
    confidence: float | None
    status: str  # "extracted" | "not_found" | "low_confidence"
    page: int | None = None
    bbox: tuple[int, int, int, int] | None = None


# Every label this module knows about, across all fields -- used only to
# tell "this is where the NEXT field's label starts" apart from "this is
# still part of the current field's value" (see _clip_at_next_label).
# Longest first so a compound label like "total amount" is recognized as
# itself rather than accidentally matched via a shorter one first.
_ALL_KNOWN_LABELS: list[str] = sorted(
    {alias for aliases in _LABEL_ALIASES.values() for alias in aliases} | set(_EXTRA_STOP_LABELS),
    key=len,
    reverse=True,
)


def _clip_at_next_label(value: str, own_aliases: list[str]) -> str:
    """A label and its value routinely share one OCR'd "line" with the
    NEXT field's label crammed right after it, with no real line break in
    the underlying PDF/OCR block -- verified live against a real invoice
    whose header ("Invoice Number: Invoice Date: PO Number: Currency:
    INV-2026-004821 09/15/2026 ... Vendor / Supplier: ... Cost Centre:
    CC-1042") came back as a single native-text block with no `\\n`
    between cells. Without this, "Invoice Number"'s value runs straight
    through every other field's label AND value to the end of that block.
    Clips `value` at the earliest point any OTHER known field's label
    starts (own_aliases excluded, so a field's own alias reappearing
    inside its own value -- e.g. "Reference: see reference above" --
    isn't mistaken for the next field).
    """
    own = {a.lower() for a in own_aliases}
    earliest = None
    for label in _ALL_KNOWN_LABELS:
        if label.lower() in own:
            continue
        match = re.search(rf"\b{re.escape(label)}\b\s*[:\-]", value, re.IGNORECASE)
        if match and (earliest is None or match.start() < earliest):
            earliest = match.start()
    return value[:earliest].strip() if earliest is not None else value


def _label_pattern(alias: str) -> re.Pattern[str]:
    """A single bare word (e.g. "vendor", "supplier", "amount") is common
    enough in ordinary prose or a Terms and Conditions clause that it must
    be followed by a real label marker (":"/"-") OR be the entire rest of
    the line to count as a label at all -- verified live against a real
    purchase order whose vendor_supplier_name picked up a legal
    boilerplate clause ("...as per the Supplier's proforma invoice, and
    shall not be deviated...") on page 6, because a bare "supplier"
    embedded mid-sentence (followed by neither a colon nor the end of the
    line) matched just as readily as a genuine, isolated "Supplier" label
    would have. The "or end of line" half matters just as much as the
    colon check: a real single-word label is routinely its own whole
    line/table-cell with the colon rendered as a completely separate span
    (verified live: a real purchase order's "Currency" label sat alone on
    its own line, with " : USD" a separate line entirely) -- requiring a
    colon unconditionally would have broken that case, rejecting a
    perfectly genuine label for looking too bare.

    A multi-word phrase (e.g. "total amount", "invoice number") is
    unlikely to occur by coincidence in flowing prose either way, so it
    keeps matching regardless of what follows it.

    The label text itself is matched with OCR-confusable tolerance (see
    _fuzzy_literal) rather than an exact literal -- real bug found live: a
    thermal-printer pharmacy receipt's "INVOICE NO" came back from
    PaddleOCR as "1NVO1CE NO" (both "I"s misread as "1"), which an exact
    match could never recognize, silently losing invoice_number entirely
    on an otherwise perfectly legible receipt.
    """
    require_marker = " " not in alias.strip()
    marker = r"\s*(?:[:\-]|$)" if require_marker else r"\s*[:\-]?"
    return re.compile(rf"\b{_fuzzy_literal(alias)}\b{marker}\s*(.*)$", re.IGNORECASE)


# Character shapes real dot-matrix/thermal-receipt OCR most commonly
# confuses for one another -- kept deliberately small (only genuinely
# similar-looking pairs) to avoid loosening label matching into false
# positives on unrelated text.
_OCR_CONFUSABLE_CHARS: dict[str, str] = {
    "i": "il1",
    "l": "il1",
    "o": "o0",
    "s": "s5",
    "b": "b8",
    "z": "z2",
}


def _fuzzy_literal(alias: str) -> str:
    """Builds a regex fragment matching `alias` with each OCR-confusable
    character (see _OCR_CONFUSABLE_CHARS) replaced by a character class
    covering every shape it's commonly misread as, instead of a plain
    `re.escape`d literal.
    """
    parts = []
    for ch in alias:
        lower = ch.lower()
        if ch == " ":
            parts.append(r"\s+")
        elif lower in _OCR_CONFUSABLE_CHARS:
            parts.append(f"[{_OCR_CONFUSABLE_CHARS[lower]}]")
        else:
            parts.append(re.escape(ch))
    return "".join(parts)


def _strip_table_cell_markers(text: str) -> str:
    """Docling's own .docx export renders a table row as markdown pipe
    syntax (e.g. "| DOCUMENT REF              | ISMS-DOC-A08-28-1  |") --
    when a label match's capture group lands mid-row, the next cell's "|"
    delimiter (and the padding around it) comes along for free, since it
    isn't whitespace _label_proximity_value's own .strip() would remove.
    Real bug found live: a policy document's reference_number came back as
    "| ISMS-DOC-A08-28-1  |" instead of the clean value.
    """
    stripped = re.sub(r"^\|\s*", "", text.strip())
    stripped = re.sub(r"\s*\|$", "", stripped)
    return stripped.strip()


def _label_proximity_value(text: str, aliases: list[str]) -> str | None:
    """Finds a label like "Author:" and returns whatever's on the rest of
    that same line, or -- if the label is alone on its own line -- the
    next non-empty line below it. Returns None if no alias appears at all.
    The returned value is clipped at the next field's label if one is
    crammed onto the same OCR'd line (see _clip_at_next_label).

    This is the flattened-TEXT fallback, blind to real page position --
    prefer _spatial_label_value when real per-line bbox data is available
    (see its own docstring for why: this function can't tell "the next
    field's label happens to sit on the very next visual line" apart from
    "this really is the label's own next line," which the real geometry
    resolves for free).
    """
    lines = [line.strip() for line in text.splitlines()]
    for alias in aliases:
        pattern = _label_pattern(alias)
        for i, line in enumerate(lines):
            match = pattern.search(line)
            if not match:
                continue
            value = _strip_table_cell_markers(match.group(1).strip())
            if value:
                return _clip_at_next_label(value, aliases)
            for next_line in lines[i + 1 :]:
                if next_line and _has_real_content(next_line):
                    cleaned = _strip_table_cell_markers(_strip_leading_marker(next_line))
                    return _clip_at_next_label(cleaned, aliases)
            return None
    return None


LineBlock = tuple[str, tuple[int, int, int, int]]  # (text, (x0, y0, x1, y1)) in page-pixel space, at OCR_RENDER_DPI


def _looks_like_a_label(text: str) -> bool:
    """True if `text` contains one of this module's own known field
    labels -- used to stop spatial matching from picking ANOTHER field's
    label as if it were this field's value. Real invoices often put two
    label/value columns side by side on the same visual row, sometimes
    behind a leading connector (e.g. "Vendor / Supplier:" ... "Ship To /
    Department:" -- the label itself is "Department:", not at the very
    start of that span) -- the second is a different field's label, not
    the first field's value, even though it's the nearest thing to the
    right of the first. Deliberately a substring search (not anchored to
    the start of `text`), not just a stricter startswith check.
    """
    for label in _ALL_KNOWN_LABELS:
        if re.search(rf"\b{re.escape(label)}\b\s*[:\-]", text, re.IGNORECASE):
            return True
    return False


def _ranges_overlap(a0: int, a1: int, b0: int, b1: int, min_overlap_ratio: float = 0.5) -> bool:
    """True if [a0,a1) and [b0,b1) overlap by at least `min_overlap_ratio`
    of the SHORTER range's length -- used on the y-axis to decide "same
    visual row" and on the x-axis to decide "same column".
    """
    overlap = min(a1, b1) - max(a0, b0)
    if overlap <= 0:
        return False
    shorter = min(a1 - a0, b1 - b0)
    return shorter > 0 and overlap / shorter >= min_overlap_ratio


def _has_real_content(text: str) -> bool:
    """True if `text` contains at least one letter or digit. A bare
    punctuation span -- most often a lone ":" -- is not a real value
    candidate no matter how close it sits to the label. Real bug found
    live: a PDF that renders a label's colon as its OWN separate text run
    (e.g. "Invoice No" / " :" / "IN/2025/01448" as three separate same-row
    spans, the colon genuinely nearer to the label than the real value)
    was landing on that colon-only span as invoice_number's "value" --
    exactly as close to the label as a real one, but not one.
    """
    return any(ch.isalnum() for ch in text)


def _currency_pattern_fallback(text: str) -> str | None:
    """Last-resort pattern-rule match for `currency` when no "Currency:"
    label exists anywhere on the page (see _CURRENCY_CODE_RE's own
    comment) -- takes the first recognized ISO code found anywhere in the
    flattened text, which is the same "first mention wins" behavior a
    human skimming the page would use, and matches this module's existing
    priority order (label match, then pattern rule, before giving up).
    """
    match = _CURRENCY_CODE_RE.search(text)
    return match.group(1) if match else None


def _looks_like_contact_or_address_line(text: str) -> bool:
    """True for a letterhead's address/phone/email/website line, so
    _vendor_letterhead_fallback can skip past them to the actual company
    name. Deliberately simple, pattern-based heuristics (no NLP): an
    email or "www." (contact line), a "T:"/"Tel"/"Fax"/"E:"/"W:" prefix
    (contact line), or 2+ commas (an address is the only line type on a
    real letterhead that lists several comma-separated segments -- a
    company name essentially never does).
    """
    lowered = text.strip().lower()
    if "@" in text or "www." in lowered:
        return True
    if re.match(r"^(t|tel|fax|e|w|ph|phone)\s*[:.]", lowered):
        return True
    return text.count(",") >= 2


def _vendor_letterhead_fallback(
    lines_by_page: dict[int, list[LineBlock]] | None,
) -> tuple[str, tuple[int, int, int, int]] | None:
    """Last-resort fallback for `vendor_supplier_name` when no "Vendor:"/
    "Supplier:"/"Seller:" label exists anywhere on the page -- real
    invoice found live: the vendor was only ever presented as a plain
    letterhead (company name directly under the "TAX INVOICE" title,
    followed by its address and contact line), never behind a label a
    proximity match could anchor to.

    Walks page 1's lines in reading order, skipping the document-type
    title itself and any address/contact lines, and returns the first
    remaining line with real content (with its real bbox, so the review
    UI can still highlight where the value came from). Stops (returns
    None, not a guess) as soon as it reaches a line that's itself a known
    field label -- that means the letterhead block has ended and we've
    reached the invoice's label/value section, so anything after that
    point is a different field's value, not the vendor's name.
    """
    if not lines_by_page:
        return None
    lines = lines_by_page.get(1)
    if not lines:
        return None
    for raw_text, bbox in lines:
        candidate = raw_text.strip()
        if not candidate:
            continue
        if candidate.lower() in _DOCUMENT_TITLE_LINES:
            continue
        if _looks_like_a_label(candidate):
            return None
        if not _has_real_content(candidate) or _looks_like_contact_or_address_line(candidate):
            continue
        return candidate, bbox
    return None


def _strip_leading_marker(text: str) -> str:
    """A value candidate found via same-row/below spatial matching
    sometimes bundles a leftover label marker at its own start -- verified
    live: a real purchase order renders "Currency" and its value as two
    separate spans, but the SECOND span is literally " : USD" (the colon
    attached to the value, not the label -- unlike the "Invoice No" / " :"
    / "IN/2025/01448" case, where the colon is its own third span entirely
    and gets excluded by _has_real_content instead). Strips one leading
    ":"/"-" (and surrounding whitespace) so the returned value is clean.
    """
    return re.sub(r"^[:\-]\s*", "", text.strip()).strip()


def _spatial_value_on_page(lines: list[LineBlock], aliases: list[str]) -> tuple[str | None, tuple[int, int, int, int] | None]:
    """Finds a label among REAL per-visual-line text+bbox (see layout.py's
    native_text_lines_for_page / ocr_engine.py's PaddleOCR line results,
    each already one entry per detected line -- NOT the flattened,
    paragraph-merged text _label_proximity_value works over) and returns
    whichever OTHER line is its value, by real page position: (1)
    whatever's left on the label's own line after the label text itself
    (an ordinary "Label: Value" span), else (2) the nearest OTHER line on
    the same visual row, to its right, that doesn't itself look like a
    different field's label, else (3) the nearest line in roughly the
    same column, below it, that also doesn't look like a label.

    Verified live against a real invoice whose flattened text merged an
    entire "Invoice Number: Invoice Date: PO Number: Currency: ... Vendor
    / Supplier: ... Cost Centre: ..." header into one contaminated blob
    (Docling grouped 4 real visual rows into a single paragraph TextItem):
    this recovers invoice_number/invoice_date/po_number/currency (label
    left, value right, same row, separate spans) AND
    vendor_supplier_name/department (label above, value stacked below)
    correctly from the exact same document, because the real per-line
    bboxes it works from were never merged in the first place.
    """
    for alias in aliases:
        pattern = _label_pattern(alias)
        for i, (text, bbox) in enumerate(lines):
            match = pattern.search(text)
            if not match:
                continue
            same_line_rest = match.group(1).strip()
            if same_line_rest and _has_real_content(same_line_rest):
                return _clip_at_next_label(same_line_rest, aliases), bbox

            x0, y0, x1, y1 = bbox
            same_row = [
                (other_text, other_bbox)
                for j, (other_text, other_bbox) in enumerate(lines)
                if j != i
                # Compared against this label's own START (x0), not its
                # END (x1): two genuinely separate spans on the same row
                # can sit as little as ~1px apart (verified live: a real
                # purchase order's "Currency" label and its " : USD" value
                # span had well under 1pt of gap between them) -- using x1
                # as the threshold made that comparison brittle enough to
                # occasionally fail outright, falling through to the
                # "below" search and risking a sibling label positioned
                # below in the same column instead.
                and other_bbox[0] > x0
                and _ranges_overlap(y0, y1, other_bbox[1], other_bbox[3])
                and not _looks_like_a_label(other_text)
                and _has_real_content(other_text)
            ]
            if same_row:
                other_text, other_bbox = min(same_row, key=lambda item: item[1][0])
                return _strip_leading_marker(other_text), other_bbox

            row_height = y1 - y0
            below = [
                (other_text, other_bbox)
                for j, (other_text, other_bbox) in enumerate(lines)
                if j != i
                and other_bbox[1] > y1
                and other_bbox[1] - y1 <= max(row_height * 4, 40)
                and _ranges_overlap(x0, x1, other_bbox[0], other_bbox[2], min_overlap_ratio=0.2)
                and not _looks_like_a_label(other_text)
                and _has_real_content(other_text)
            ]
            if below:
                other_text, other_bbox = min(below, key=lambda item: item[1][1])
                return _strip_leading_marker(other_text), other_bbox

            return None, None
    return None, None


def _spatial_label_value(
    lines_by_page: dict[int, list[LineBlock]] | None, aliases: list[str]
) -> tuple[str | None, int | None, tuple[int, int, int, int] | None]:
    """Page-ordered wrapper over _spatial_value_on_page -- `lines_by_page`
    is 1-indexed (matching this whole module's page numbering elsewhere),
    absent/empty entirely when line-level data isn't available for this
    document (e.g. a zone/rubber-band crop).
    """
    if not lines_by_page:
        return None, None, None
    for page in sorted(lines_by_page):
        value, bbox = _spatial_value_on_page(lines_by_page[page], aliases)
        if value:
            return value, page, bbox
    return None, None, None


def _find_value(
    text: str, lines_by_page: dict[int, list[LineBlock]] | None, aliases: list[str]
) -> tuple[str | None, int | None, tuple[int, int, int, int] | None]:
    """Tries real per-line spatial matching first (_spatial_label_value --
    far more precise when page position data is available), falling back
    to the flattened-text heuristic (_label_proximity_value) only when
    line-level data isn't available at all or the spatial pass found
    nothing on it.
    """
    value, page, bbox = _spatial_label_value(lines_by_page, aliases)
    if value:
        return value, page, bbox
    return _label_proximity_value(text, aliases), None, None


def _match_enum(candidate: str | None, options: list[str], field_key: str) -> str | None:
    """Case/synonym-insensitive match of `candidate` against the caller's
    own allowed `options` -- NEVER returns a value outside that list.
    """
    if not candidate or not options:
        return None
    lowered = candidate.strip().lower()
    for option in options:
        if lowered == option.lower():
            return option
    if field_key == "classification_level":
        canonical = _CLASSIFICATION_SYNONYMS.get(lowered)
        if canonical:
            for option in options:
                if option.lower() == canonical:
                    return option
    # last resort: the option's own value/word appears somewhere in the
    # candidate phrase (e.g. candidate="CONFIDENTIAL - internal use only").
    for option in options:
        if option.lower() in lowered:
            return option
    return None


def _extract_date_candidate(
    text: str, lines_by_page: dict[int, list[LineBlock]] | None, aliases: list[str]
) -> tuple[str | None, int | None, tuple[int, int, int, int] | None]:
    candidate, page, bbox = _find_value(text, lines_by_page, aliases)
    if candidate:
        match = _DATE_RE.search(candidate)
        if match:
            return (match.group(1) if match.groups() else match.group(0)), page, bbox
    # Pattern-rule fallback: no label match, but a date pattern appears
    # somewhere in the text -- lower confidence, applied by the caller, and
    # no single position (matched against concatenated text).
    match = _DATE_RE.search(text)
    return ((match.group(1) if match.groups() else match.group(0)) if match else None), None, None


def _keywords_from_text(text: str, limit: int = 8) -> list[str]:
    words = re.findall(r"[A-Za-z][A-Za-z\-]{3,}", text.lower())
    counts = Counter(w for w in words if w not in _STOPWORDS)
    seen: list[str] = []
    for word, _count in counts.most_common(limit * 3):
        if word not in seen:
            seen.append(word)
        if len(seen) >= limit:
            break
    return seen


def _summary_from_text(text: str, max_len: int = 200) -> str | None:
    stripped = text.strip()
    if not stripped:
        return None
    sentence_end = re.search(r"[.!?]\s", stripped)
    if sentence_end and sentence_end.end() <= max_len:
        return stripped[: sentence_end.end()].strip()
    return stripped[:max_len].strip()


def _extract_language(text: str) -> tuple[str | None, float | None]:
    if detect_langs is None or not text or len(text.strip()) < 8:
        return None, None
    try:
        candidates = detect_langs(text)
    except LangDetectException:
        return None, None
    if not candidates:
        return None, None
    top = candidates[0]
    return top.lang, float(top.prob)


def _extract_field_value(
    spec: FieldSpec, text: str, lines_by_page: dict[int, list[LineBlock]] | None = None
) -> tuple[str | list[str] | None, float | None, bool, int | None, tuple[int, int, int, int] | None]:
    """Dispatches ONE requested field (no zone match) through, in order:
    spatial label matching (when lines_by_page is available -- see
    _find_value) -> flattened-text label proximity -> pattern rules ->
    free-text fallback, plus the field-specific handling the spec calls
    for. Returns (value, confidence, forced_low_confidence, page, bbox) --
    `forced_low_confidence` is True only for a free-text-fallback value
    (description/tags/keywords with no label match), which must be marked
    low_confidence regardless of any confidence number. `page`/`bbox` are
    only ever non-None when the value came from real per-line spatial
    matching -- never from the flattened-text fallback, a pattern-rule
    match, or the free-text fallback (none of those have one single page
    position to report).
    """
    field_key, data_type, options = spec.field_key, spec.data_type, spec.options or []
    aliases = _LABEL_ALIASES.get(field_key, [field_key.replace("_", " ")])

    if field_key == "language":
        lang, prob = _extract_language(text)
        return lang, prob, False, None, None

    if data_type == "dropdown" and options:
        candidate, page, bbox = _find_value(text, lines_by_page, aliases)
        matched = _match_enum(candidate, options, field_key)
        if matched:
            return matched, 0.85, False, page, bbox
        if field_key == "currency":
            # Pattern-rule fallback: real invoice found live with no
            # "Currency:" label anywhere on the page at all (see
            # _currency_pattern_fallback's own comment). Still only ever
            # returns one of the caller's own configured options --
            # _match_enum never returns a value outside that list.
            fallback_matched = _match_enum(_currency_pattern_fallback(text), options, field_key)
            if fallback_matched:
                return fallback_matched, 0.55, False, None, None
        return None, None, False, None, None

    if data_type in ("date", "datetime"):
        raw_date, page, bbox = _extract_date_candidate(text, lines_by_page, aliases)
        if raw_date is None:
            return None, None, False, None, None
        from_label = page is not None or _label_proximity_value(text, aliases) is not None
        return _normalize_date(raw_date), (0.9 if from_label else 0.65), False, page, bbox

    if field_key == "reference_number":
        # Label match first, THEN the pattern-rule regex fallback -- matches
        # this module's documented priority order (see
        # extract_requested_fields's own docstring). Real bug found live:
        # with the pattern rule tried FIRST and unconditionally, a policy
        # document whose actual reference ("DOCUMENT REF: ISMS-DOC-A08-28-1")
        # sat in a table further down the page lost to an unrelated, earlier
        # "ISO/IEC" mention in the body text that happened to also look
        # reference-number-shaped -- a bare whole-text regex scan has no way
        # to prefer the value a real label points at over an incidental
        # lookalike, so it must never run before the label match gets a
        # chance.
        candidate, page, bbox = _find_value(text, lines_by_page, aliases)
        if candidate:
            return candidate.strip(), 0.85, False, page, bbox
        match = _REFERENCE_NUMBER_RE.search(text)
        return (match.group(1).strip(), 0.65, False, None, None) if match else (None, None, False, None, None)

    if data_type in ("long_text", "multi_select"):
        candidate, page, bbox = _find_value(text, lines_by_page, aliases)
        if candidate:
            if data_type == "multi_select":
                value = _split_list_value(candidate)
                if value:
                    return value, 0.75, False, None, None  # a split list has no single bbox
            else:
                value = candidate.strip()
                if value:
                    return value, 0.75, False, page, bbox
        # Free-text fallback: generated FROM the document, not found via a
        # label -- always low_confidence per spec, whatever the number below.
        value = _keywords_from_text(text) if data_type == "multi_select" else _summary_from_text(text)
        return (value, 0.5, True, None, None) if value else (None, None, False, None, None)

    if field_key == "vendor_supplier_name":
        candidate, page, bbox = _find_value(text, lines_by_page, aliases)
        if candidate:
            return candidate.strip(), 0.8, False, page, bbox
        # Letterhead fallback: real invoice found live with no "Vendor:"/
        # "Supplier:" label anywhere on the page (see
        # _vendor_letterhead_fallback's own comment) -- lower confidence
        # than a real label match, since it's a position-based guess.
        fallback = _vendor_letterhead_fallback(lines_by_page)
        if fallback:
            name, bbox = fallback
            # forced low_confidence, like the free-text fallback above --
            # this is a position-based guess, not a labeled match, and
            # must always be flagged for human review regardless of the
            # caller's confidence threshold.
            return name, 0.6, True, 1, bbox
        return None, None, False, None, None

    # Generic single-line text field (author, department, and anything else
    # with no more specific handling above).
    candidate, page, bbox = _find_value(text, lines_by_page, aliases)
    return (candidate.strip(), 0.8, False, page, bbox) if candidate else (None, None, False, None, None)


def _split_list_value(raw: str, limit: int = 8) -> list[str]:
    parts = re.split(r"[,;/]", raw)
    seen: list[str] = []
    for part in parts:
        cleaned = part.strip().lower()
        if cleaned and cleaned not in seen:
            seen.append(cleaned)
        if len(seen) >= limit:
            break
    return seen


def _finalize_result(
    spec: FieldSpec, value, confidence: float | None, page: int | None, bbox, threshold: float, forced_low_confidence: bool = False
) -> RequestedFieldResult:
    is_empty = value is None or (isinstance(value, list) and not value) or (isinstance(value, str) and not value.strip())
    if is_empty:
        return RequestedFieldResult(field_key=spec.field_key, value=None, confidence=None, status="not_found")
    status = "low_confidence" if forced_low_confidence or confidence is None or confidence < threshold else "extracted"
    return RequestedFieldResult(field_key=spec.field_key, value=value, confidence=confidence, status=status, page=page, bbox=bbox)


def _apply_effective_expiry_validation(results: list[RequestedFieldResult]) -> list[RequestedFieldResult]:
    """OS-FR-005 field-specific handling: Expiry Date must be >= Effective
    Date. A violation never invents/drops either value (both are real OCR
    findings) -- it forces BOTH to status="low_confidence" so a human
    reviews the pair, rather than silently accepting an inconsistent
    Effective/Expiry combination. This remediation choice isn't prescribed
    by the spec (only that validation must happen), so it's deliberately
    the least destructive option: keep both values, just don't call either
    one "extracted".
    """
    by_key = {r.field_key: i for i, r in enumerate(results)}
    eff_idx, exp_idx = by_key.get("effective_date"), by_key.get("expiry_date")
    if eff_idx is None or exp_idx is None:
        return results
    eff, exp = results[eff_idx], results[exp_idx]
    if eff.status == "not_found" or exp.status == "not_found" or not eff.value or not exp.value:
        return results
    try:
        eff_date = datetime.strptime(eff.value, "%Y-%m-%d").date()
        exp_date = datetime.strptime(exp.value, "%Y-%m-%d").date()
    except ValueError:
        return results
    if exp_date < eff_date:
        results[eff_idx] = replace(eff, status="low_confidence")
        results[exp_idx] = replace(exp, status="low_confidence")
    return results


def extract_requested_fields(
    *,
    specs: list[FieldSpec],
    text: str,
    zone_fields: dict[str, ExtractedField],
    lines_by_page: dict[int, list[LineBlock]] | None = None,
    field_confidence_threshold: float = 0.6,
) -> list[RequestedFieldResult]:
    """The selectable-field entry point: attempts ONLY `specs` (never a
    field outside that list, even one a zone template or the legacy regex
    pass would otherwise have found), in priority order per field: (1)
    `zone_fields` (a zone/field template match -- computed by pipeline.py
    exactly as before, passed in here), (2) real per-line spatial matching
    when `lines_by_page` is available (see _spatial_label_value -- far
    more precise than (3), since it works from real page positions rather
    than a flattened, paragraph-merged string), (3) flattened-text label
    proximity, (4) pattern rules, (5) free-text fallback -- see
    _extract_field_value for (2)-(5). Always returns exactly one result
    per spec, including a `not_found` one, so document_ocr_field_results
    has a complete record of every field that was actually asked for.
    """
    results: list[RequestedFieldResult] = []
    for spec in specs:
        zone_hit = zone_fields.get(spec.field_key)
        if zone_hit is not None:
            results.append(
                _finalize_result(spec, zone_hit.value, zone_hit.confidence, zone_hit.page, zone_hit.bbox, field_confidence_threshold)
            )
            continue
        value, confidence, forced_low_confidence, page, bbox = _extract_field_value(spec, text, lines_by_page)
        results.append(_finalize_result(spec, value, confidence, page, bbox, field_confidence_threshold, forced_low_confidence))

    return _apply_effective_expiry_validation(results)


def requested_fields_to_output_dict(results: list[RequestedFieldResult]) -> dict[str, dict]:
    """The sparse HTTP-response `fields` shape (same `{value, confidence,
    bbox}` shape the legacy fields_to_dict already produces, so dms-core's
    existing DocumentMetadataServiceImpl#applyOcrFields mapping keeps
    working unchanged) -- a `not_found` result is omitted entirely, same
    as a non-matching regex simply not appearing today. A multi_select
    field's `value` (a real Python list) is JSON-encoded to a string here,
    since this dict's `value` must stay a plain scalar for the existing
    Java-side `Map<String,Object>`/`instanceof String` check to keep
    working -- the Java side must `ObjectMapper`-decode that JSON string
    back into a list for a multi_select field specifically.
    """
    output: dict[str, dict] = {}
    for result in results:
        if result.status == "not_found" or result.value is None:
            continue
        value = json.dumps(result.value) if isinstance(result.value, list) else result.value
        output[result.field_key] = {"value": value, "confidence": result.confidence, "bbox": list(result.bbox) if result.bbox else None}
    return output
