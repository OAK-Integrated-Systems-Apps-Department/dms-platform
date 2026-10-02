from __future__ import annotations

import json
import logging
import re
import statistics
import unicodedata
from collections import Counter
from dataclasses import dataclass, replace
from datetime import datetime
from difflib import SequenceMatcher
from typing import Callable

from . import field_confidence
from . import field_confidence as fc
from .layout import normalize_bbox_for_page
# The money grammar this deployment actually needs -- `Rs.`/`Rs`/`LKR`,
# the `/=` and `/-` terminators, comma decimals, accounting parentheses --
# already lives here and is already used by line_items.py and
# validation.py. extraction.py had its own, narrower ideas about what a
# number looks like and never consulted it.
from .patterns import detect_currency, parse_amount

logger = logging.getLogger(__name__)

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


# Schema v2 `bbox_norm` used to be computed by a private helper here.
# It now lives in layout.py beside `PageGeometry.normalize`, because the
# DB writers in repository.py need the same conversion -- see
# `normalize_bbox_for_page`'s own comment for the bug a second copy of it
# would have re-created.


def fields_to_dict(fields: list[ExtractedField], geometry_by_page: dict | None = None) -> dict[str, dict]:
    """The output contract's `fields` object is a dict keyed by field
    name, not the list this module builds internally -- this is the one
    place that conversion happens, so pipeline.py doesn't have to.
    """
    return {
        f.field: {
            "value": f.value,
            "confidence": f.confidence,
            "bbox": list(f.bbox) if f.bbox else None,
            "bbox_norm": normalize_bbox_for_page(geometry_by_page, f.page, f.bbox),
        }
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

# The currency vocabulary moved to `patterns.detect_currency`, which this
# module now calls (see `_currency_pattern_fallback`). The ISO whitelist
# that used to live here -- deliberately a fixed list rather than "any 3
# uppercase letters", so VAT/PVT/LTD never matched -- went with it, along
# with the `Rs`/`₨`/`/=` forms it was missing.

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
    # Found live on real Sri Lankan receipts, each of which printed its
    # document title ABOVE the letterhead and so won the "first usable
    # line" race against the vendor's own name.
    "cash memo", "bill", "consultation receipt", "channel receipt",
    "channel receipt (walk in)", "sales order", "delivery note",
}

# Words that mark a line as an organisation's NAME rather than a stray
# glyph, an address or a document title. Not a whitelist -- a company with
# none of these is still perfectly valid and reaches the same fallback by
# position; this only decides which of several plausible lines wins.
#
# Every entry is here because a real document in eval/corpus/real/ needed
# it: "(PVT) LTD" and "Hospital" (Santa Dora), "PLC" (Asiri), "Pharmacy"
# (Healthguard), "Eye Care" (George Gooneratne), "Ayurveda"
# (Edirisinghe).
_COMPANY_MARKERS: tuple[str, ...] = (
    "pvt ltd", "(pvt) ltd", "pvt. ltd", "private limited", "plc",
    "limited", "ltd", "inc", "incorporated", "corp", "corporation",
    "company", "& co", "holdings", "enterprises", "group",
    "hospital", "hospitals", "clinic", "medical", "medicare", "pharmacy",
    "ayurveda", "laboratories", "labs", "eye care", "health",
    "stores", "supplies", "services", "solutions", "systems", "technologies",
    "centre", "center", "institute", "academy", "school", "bank",
)

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
    # Each addition past the first six is a label a real document in
    # eval/corpus/real/ actually prints. Deliberately NOT added: a bare
    # "no" or "no." -- the same pages carry "No. 208, Galle Road",
    # "No ITEM" and "No Refunds / No Cancellations", and a wrong invoice
    # number is worse than none.
    "invoice_number": [
        "invoice number", "invoice no", "inv no", "inv#", "inv #", "invoice#",
        "bill no", "bill number", "receipt no", "receipt number",
        "memo no", "pi no", "proforma no",
        "document no", "doc no",
    ],
    # Deliberately NOT in `invoice_number` above: "order no" and "order
    # number" name a DIFFERENT identifier, and they cost a real document
    # when they were there. A cash memo prints "Order No.." near its
    # footer with the number written by hand beside it, and the alias
    # matched that label and returned the next thing on the row --
    # "0452LDeveryDate22703/2025" -- as the invoice number, replacing an
    # honest `not_found`. A wrong invoice number is worse than none, which
    # is the whole reason this list is conservative.
    #
    # `purchase_order_number` is where an order number belongs, and it
    # already claims these.
    "invoice_date": ["invoice date"],
    "purchase_order_number": ["purchase order number", "po number", "po no", "po#", "order number"],
    "currency": ["currency"],
    # -- Invoice fields (Phase 3d, production prompt 3.8) --
    # `amount` above is the seeded type's headline figure and already
    # claims "total"/"grand total"/"amount due". These are the *component*
    # figures a real invoice breaks that headline into, and the reason
    # 3.9's `subtotal + tax = total` rule had nothing to check: none of
    # its three inputs were ever extracted.
    #
    # `subtotal` deliberately does NOT list a bare "total" -- it would
    # match the grand total on every invoice that writes "Total" once, and
    # a subtotal that is really the total makes the arithmetic rule fire
    # on correct documents.
    "subtotal": [
        "subtotal", "sub total", "sub-total", "net amount", "net value",
        "amount before tax", "taxable amount", "taxable value",
    ],
    "tax_amount": [
        "tax amount", "total tax", "vat amount", "gst amount", "sales tax",
        "tax", "vat", "gst",
    ],
    "tax_rate": [
        "tax rate", "vat rate", "gst rate", "tax %", "vat %", "gst %", "rate of tax",
    ],
    "total": [
        "grand total", "total amount due", "invoice total", "total payable",
        "balance due", "total due", "total",
    ],
    # A tax registration number, not a person's tax id. Distinct from
    # `reference_number` because a document routinely carries both and
    # they are never interchangeable.
    "vendor_tax_id": [
        "vat registration", "vat reg no", "vat number", "vat no", "vat id",
        "gst number", "gstin", "tax registration number", "tax reg no",
        "tin", "abn", "ein",
    ],
    "due_date": [
        "due date", "payment due", "payment due date", "due on", "pay by",
    ],
    "payment_terms": [
        "payment terms", "terms of payment", "payment term", "terms",
    ],
    "vendor_supplier_name": ["vendor / supplier", "vendor/supplier", "vendor", "supplier", "billed by", "seller"],
    "cost_centre": ["cost centre", "cost center"],
    # Ordered by how specifically each phrase names "the figure this
    # invoice is settled at", most specific first. Four tiers:
    #
    #  1. The document's own GRAND TOTAL -- what the invoice is for.
    #  2. Net/gross figures: after discount and tax, before payment.
    #  3. What is still OWED, reached only when the document names no
    #     total at all.
    #  4. Bare "total", then bare "amount", as last resorts.
    #
    # Tiers 1 and 3 are in this order because the ground truth says so,
    # not on principle. `cc90b3ca` prints INVOICE TOTAL 220,000.00, PAID
    # AMOUNT 120,000.00 and DUE AMOUNT 100,000.00, and its reviewed label
    # is **220,000.00** -- so `amount` is what the invoice is FOR, not
    # what is left to pay on it. That is a business decision this list now
    # encodes; if it is ever revisited, this is the line to move, and
    # `8b75ddd3` (where the two figures coincide at 188,500.00) will not
    # notice either way, so it cannot be used to confirm the choice.
    #
    # Everything in tiers 1-3 exists because a real document in
    # `eval/corpus/real/` labels its total that way and nothing earlier in
    # this list matched it. "net amount" (Healthguard), "invoice total"
    # and "due amount" (the OAK template), "total fee" (Asiri) were all
    # absent, so the bare "amount" at the end matched first -- against
    # "PAID AMOUNT", "Gross Amount", "Change Amount" and an "Amount"
    # COLUMN HEADER, returning a line item or a paid-to-date figure as the
    # invoice total on four separate documents.
    #
    # Deliberately NOT here: "sub total" and "subtotal" (that is the
    # pre-tax figure, and `_LABEL_ALIASES["subtotal"]` claims it so
    # 3.9's `subtotal + tax = total` rule has both sides to check), and a
    # bare "fee" (it would match "DOCTOR'S FEE" and "HOSPITAL FEE" line
    # items on every channelling receipt in the corpus).
    #
    # "\btotal\b" does not match inside "Subtotal" (no word boundary
    # between "Sub" and "total" -- it is one unbroken token), so that
    # false positive is already safe.
    "amount": [
        # 1. the grand total -- what the invoice is for
        "grand total", "invoice total", "total payable", "amount payable",
        "total amount",
        # 2. net / gross -- after discount and tax
        "net total", "net amount", "net value", "total fee",
        "gross amount", "gross total",
        # 3. what is still owed -- only if no total above was named
        "total amount due", "amount due", "due amount", "balance due",
        "total due",
        # 4. last resorts
        "total", "amount",
    ],
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
    # Whether the document type marks this field mandatory. Feeds
    # `requires_review`'s first arm (production prompt 3.10). Defaults
    # False so every existing construction site -- tests, the dev-trigger
    # endpoint, eval -- keeps working and simply contributes nothing to
    # the review decision, which is the safe direction.
    is_mandatory: bool = False
    # Whether OCR is expected to read this field OFF the document, as
    # opposed to it being something this organisation assigns at filing
    # time (`metadata_fields.is_ocr_extractable`, seeded by V44).
    #
    # A field with this false is skipped entirely: no value is produced,
    # no row is written, and `requires_review`'s mandatory-missing arm
    # ignores it. That last part is the point. `classification_level` is
    # mandatory for INVOICE and no supplier invoice carries one, so before
    # this flag it was reported missing on 20 of 21 real documents and put
    # every one of them in the review queue.
    #
    # Defaults True so an existing construction site that does not know
    # about the flag keeps behaving exactly as it did. That is the opposite
    # default to `is_mandatory` above, and deliberately so: there the safe
    # direction is "contributes nothing to the review decision", here it is
    # "keeps extracting what it used to".
    ocr_extractable: bool = True


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
    # "extracted" | "low_confidence" | "not_found" | "defaulted"
    #              | "not_extractable"
    #
    # `defaulted`  -- no signal on the page; a tenant default was applied.
    #                 A real value a consumer may use, but not something
    #                 OCR read, so it is never counted as OCR accuracy and
    #                 never a reason to review.
    # `not_extractable` -- this field is not something a document states
    #                 (`FieldSpec.ocr_extractable`); it was never attempted.
    status: str
    # The string as it was read off the page, before any normalisation
    # (production prompt 3.8). Equal to `value` for every field that is
    # not normalized; differs for dates, where `value` is ISO and this is
    # what the document actually printed -- which is what a reviewer needs
    # to judge a day-first/month-first ambiguity. None for a multi_select,
    # whose value is a list with no single source string.
    raw_value: str | None = None
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
    label exists anywhere on the page -- the first recognized signal in
    the flattened text, which is the same "first mention wins" behavior a
    human skimming the page would use, and matches this module's existing
    priority order (label match, then pattern rule, before giving up).

    Delegates to `patterns.detect_currency`, which knows `Rs`/`Rs.`, the
    `₨` sign and the `/=` terminator as well as the ISO codes. This
    function used to carry its own ISO-codes-only regex, so on three real
    documents a printed currency was read at confidence 1.00 and then
    discarded: two Santa Dora receipts whose "Amount Rs" column header
    OCR returned glued as "AmountRs", and a cash memo with a clean "Rs."
    column header. `patterns.py` already had the whole vocabulary and
    this module simply never imported it.
    """
    return detect_currency(text)


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

    # Collect every plausible line first, instead of returning the first
    # one. "First in reading order" is what this did, and on a
    # photographed receipt reading order opens with whatever fragment the
    # detector found in the margin: measured on the real corpus it
    # returned "492", "la Dore", "E" and "h202/90" as vendor names, while
    # "Santa Dora Hospital", "MEDIHELP" and "Edirisinghe Ayurveda" sat a
    # line or two below.
    candidates: list[tuple[str, tuple[int, int, int, int]]] = []
    for raw_text, bbox in lines:
        candidate = raw_text.strip()
        if not candidate:
            continue
        if candidate.lower() in _DOCUMENT_TITLE_LINES:
            # A document title ENDS the letterhead -- but only once the
            # letterhead has actually started. Both orders occur in the
            # real corpus: a cash memo prints "Cash Memo" above its own
            # name (so breaking here would return nothing), while a
            # payment receipt prints "Payment Receipt" below the logo and
            # then repeats the company as a branch sub-title
            # ("Medihelp Hospital - Piliyandala"), which outscored the
            # logo itself on markers until this stopped the search.
            if candidates:
                break
            continue
        if _looks_like_a_label(candidate):
            # The letterhead block has ended and the label/value section
            # has begun; anything past here is a different field's value.
            break
        if not _has_real_content(candidate) or _looks_like_contact_or_address_line(candidate):
            continue
        if not _could_be_an_organisation_name(candidate):
            continue
        candidates.append((candidate, bbox))

    if not candidates:
        return None
    # A line carrying a company marker beats one without, whatever the
    # reading order; among equals the first still wins, which is the old
    # behaviour and is right for a letterhead.
    for candidate, bbox in candidates:
        if _has_company_marker(candidate):
            return candidate, bbox
    return candidates[0]


def _has_company_marker(text: str) -> bool:
    """True when the line carries a company marker AND something else.

    The "and something else" is load-bearing. Medihelp's letterhead sets
    its name on two lines, "MEDIHELP" above "HOSPITALS", and a bare marker
    test preferred "HOSPITALS" -- picking the generic half of a name over
    the half that identifies the company, on two real documents. A line
    that is ONLY a marker word is a fragment, not a better candidate than
    a line without one.
    """
    lowered = f" {re.sub(r'[^a-z0-9&() ]+', ' ', text.lower())} "
    lowered = re.sub(r"\s+", " ", lowered)
    matched = [m for m in _COMPANY_MARKERS if f" {m} " in lowered]
    if not matched:
        return False
    remainder = lowered
    for marker in sorted(matched, key=len, reverse=True):
        remainder = remainder.replace(f" {marker} ", " ")
    return sum(1 for ch in remainder if ch.isalpha()) >= 3


def _could_be_an_organisation_name(text: str) -> bool:
    """Reject what an organisation name cannot be.

    Each rule is one real failure from the corpus, and the point of
    writing them as rejections rather than as a positive pattern is that a
    company with an unusual name must still pass:

    - `"E"`, `"IN"`      -- a logo glyph the detector found on its own.
    - `"492"`, `"0420764"` -- a pre-printed serial number.
    - `"h202/90"`, `"1551APASO3P"` -- a perforation mark or scanner
      artefact: one unbroken token mixing letters and digits.

    The last one is the only rule that could plausibly reject a real name,
    so it is narrowed to single-token strings -- "3M Lanka (Pvt) Ltd" has
    a space in it and survives.
    """
    stripped = text.strip()
    letters = sum(1 for ch in stripped if ch.isalpha())
    if letters < 3:
        return False
    # Mostly punctuation or digits: an address fragment or a serial.
    if letters < len(stripped) * 0.5:
        return False
    if " " not in stripped and any(ch.isdigit() for ch in stripped):
        return False
    return True


_PROMINENT_NUMBER_RE = re.compile(r"^[0-9]{5,12}$")
# How much taller than the page's typical line a number has to be before
# it counts as "printed prominently". 1.5x, measured: on the real corpus
# the pre-printed serials run 1.7-2.1x the median line, and nothing else
# numeric on those pages clears 1.5x in a horizontal box.
_PROMINENT_MIN_HEIGHT_RATIO = 1.5
# A letterhead serial is near the top. Bounding it keeps a total, a phone
# number or an account number further down from qualifying.
_PROMINENT_TOP_FRACTION = 0.40


def _prominent_document_number(
    lines_by_page: dict[int, list[LineBlock]] | None,
    page_height_px: float | None,
) -> tuple[str, tuple[int, int, int, int]] | None:
    """The large pre-printed serial a receipt carries instead of a label.

    Four documents in the real corpus print their number this way: a
    7-digit figure set about twice the body size at the top of the page,
    with no "Invoice No" anywhere to anchor a label match. Only tried
    after every label alias has failed.

    Three conditions, and the third is the one that makes this safe to
    ship. Measured over the whole real set:

    | rule | correct | wrong | precision |
    |---|---|---|---|
    | prominent + near the top | 4 | 2 | 67% |
    | **+ wider than it is tall** | **4** | **0** | **100%** |

    The two false positives were both the perforation code printed down
    the page MARGIN of a continuous-stationery receipt -- read as a tall,
    narrow vertical strip, which is prominent by height and is not a line
    of text. Requiring a horizontal box removes them and costs nothing.

    Ambiguity is refused outright: two different prominent numbers mean we
    cannot tell which is the document's, and a wrong invoice number is
    worse than none.
    """
    if not lines_by_page or not page_height_px:
        return None
    lines = lines_by_page.get(1)
    if not lines:
        return None

    heights = [bbox[3] - bbox[1] for text, bbox in lines if text.strip()]
    if not heights:
        return None
    median_height = statistics.median(heights)
    if median_height <= 0:
        return None

    found: dict[str, tuple[int, int, int, int]] = {}
    for text, bbox in lines:
        candidate = text.strip()
        if not _PROMINENT_NUMBER_RE.match(candidate):
            continue
        x0, y0, x1, y1 = bbox
        if y0 > page_height_px * _PROMINENT_TOP_FRACTION:
            continue
        if (y1 - y0) < median_height * _PROMINENT_MIN_HEIGHT_RATIO:
            continue
        if (x1 - x0) <= (y1 - y0):
            continue  # a vertical strip: margin code, not a line of text
        found.setdefault(candidate, bbox)

    if len(found) != 1:
        return None
    value, bbox = next(iter(found.items()))
    return value, bbox


def _vendor_key(text: str) -> str:
    """Fold a company name to what survives OCR noise and house style.

    Everything that is not a letter or digit goes, including spaces. That
    is deliberate and it is the whole reason this works on real pages:
    Asiri's letterhead came back as `"ASIRISURGICAL HOSPITAL PLC"` -- one
    missing space -- which no string comparison forgives and which folds
    to exactly the same key as `"ASIRI SURGICAL HOSPITAL PLC"`.
    """
    return re.sub(r"[^a-z0-9]+", "", unicodedata.normalize("NFKC", text).lower())


# How close a page line has to fold to a known vendor before we call it a
# match. 0.82 on the folded forms, measured against the real corpus: it
# admits "Dora Hospital (PVT) Ltd" -> "Santa Dora Hospital (PVT) Ltd"
# (0.86, the logo's "Santa" lost to a script font) and rejects the
# closest unrelated pair in the set, "MEDIHELP" against "Medihelp
# Hospital (Pvt) Ltd" being the nearest genuine near-miss. Provisional --
# it has only ever been set against 21 documents.
_KNOWN_VENDOR_MIN_RATIO = 0.82


def _known_vendor_match(
    lines_by_page: dict[int, list[LineBlock]] | None,
    known_vendors: list[str],
) -> tuple[str, tuple[int, int, int, int]] | None:
    """The first page-1 line that matches a vendor this tenant has already
    confirmed, returned as the CONFIRMED spelling.

    Returning the stored spelling rather than the OCR text is the point.
    A letterhead set in a script face comes back in fragments -- the real
    corpus has "la Dore" and "anta" where the page says "Santa Dora" --
    and no amount of cleaning turns the fragments back into the name. What
    does work is recognising that this is a vendor we have seen before and
    writing down what we already know it is called.

    The list is built from values a human confirmed (accepted or corrected
    in the review screen), never from OCR output, so this cannot teach
    itself its own mistakes.
    """
    if not known_vendors or not lines_by_page:
        return None
    lines = lines_by_page.get(1)
    if not lines:
        return None

    by_key = {}
    for vendor in known_vendors:
        key = _vendor_key(vendor)
        if len(key) >= 6:  # too short to match on without inviting collisions
            by_key.setdefault(key, vendor)
    if not by_key:
        return None

    best: tuple[float, str, tuple[int, int, int, int]] | None = None
    tied_with: set[str] = set()
    for raw_text, bbox in lines[:_VENDOR_SEARCH_LINES]:
        candidate_key = _vendor_key(raw_text)
        if len(candidate_key) < 6:
            continue
        for key, vendor in by_key.items():
            ratio = _vendor_similarity(candidate_key, key)
            if ratio < _KNOWN_VENDOR_MIN_RATIO:
                continue
            if best is None or ratio > best[0]:
                best = (ratio, vendor, bbox)
                tied_with = {vendor}
            elif ratio == best[0]:
                tied_with.add(vendor)
    if best is None:
        return None
    if len(tied_with) > 1:
        # The same text matched two different confirmed vendors equally
        # well, so it cannot identify either. Real case: an Asiri
        # letterhead sets its name stacked, and OCR returned the word
        # "HOSPITAL" on a line of its own -- which is a substring of both
        # "MEDIHELP HOSPITAL (PVT) LTD" and "Santa Dora Hospital (PVT)
        # Ltd". It was returned as the vendor, marked `extracted`, and was
        # a different company entirely. A confident wrong vendor is worse
        # than falling through to the letterhead fallback.
        logger.debug(
            "known-vendor match refused: %d vendors tie at %.3f (%s)",
            len(tied_with), best[0], ", ".join(sorted(tied_with)),
        )
        return None
    return best[1], best[2]


# Words that identify no company on their own, folded the same way
# `_vendor_key` folds a name. A fragment that is only one of these cannot
# support a containment match however long it is.
_GENERIC_VENDOR_KEYS = frozenset(_vendor_key(m) for m in _COMPANY_MARKERS)


def _vendor_similarity(candidate_key: str, known_key: str) -> float:
    """How strongly a page line identifies a known vendor, in [0, 1].

    Containment counts as a full match, because that is the case the list
    exists for: a script letterhead loses its logo word and comes back as
    "Dora Hospital (PVT) Ltd" for "Santa Dora Hospital (PVT) Ltd", and no
    cleaning recovers the missing word.

    **Unless the fragment identifies nothing.** An Asiri letterhead sets
    its name stacked and OCR returned the word "HOSPITAL" alone -- a
    substring of two different confirmed vendors, which was returned as
    one of them, marked `extracted`, and was a different company. A
    generic marker is exactly the fragment that carries no identity, and
    `_COMPANY_MARKERS` already enumerates them for the letterhead scorer.

    Length is deliberately NOT the test. It was tried: requiring the
    fragment to be 70% of the name rejected "hospital" (36%) and also
    rejected "MEDIHELP" (36%) on three real receipts, trading three
    correct matches for one wrong one. "medihelp" is short and
    distinctive; "hospital" is short and generic, and the difference is
    the word, not the length.
    """
    if candidate_key == known_key:
        return 1.0
    shorter, longer = sorted((candidate_key, known_key), key=len)
    if shorter in longer and shorter not in _GENERIC_VENDOR_KEYS:
        return 1.0
    return SequenceMatcher(None, candidate_key, known_key).ratio()


# A letterhead lives at the top of page 1. Bounding the search stops a
# known vendor's name mentioned in the body ("payable to ...", a bank
# line) from being read as the issuer.
_VENDOR_SEARCH_LINES = 15


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


# How far below a label the "value is stacked underneath" search will
# reach, as a fraction of page height.
#
# This used to be a bare `40` pixels. 40px is ~2.5% of an A4 page at the
# ~137 DPI the renderer actually produces (842pt x 1.90 = 1600px), so
# this reproduces today's behavior exactly -- but it keeps meaning the
# same thing when Phase 2 raises the render DPI to 300, where a literal
# 40px would silently shrink to ~1.1% of the page and start missing
# values that are currently found.
_BELOW_SEARCH_PAGE_FRACTION = 0.025

# Used only when the caller has no page geometry to offer (a .docx, or a
# test constructing lines by hand). Same number as before, so nothing
# that relied on it changes.
_BELOW_SEARCH_FALLBACK_PX = 40


def _spatial_value_on_page(
    lines: list[LineBlock],
    aliases: list[str],
    page_height_px: float | None = None,
    accepts: "Callable[[str], bool] | None" = None,
) -> tuple[str | None, tuple[int, int, int, int] | None]:
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
    accepts = accepts or (lambda _text: True)
    for alias in aliases:
        pattern = _label_pattern(alias)
        for i, (text, bbox) in enumerate(lines):
            match = pattern.search(text)
            if not match:
                continue
            same_line_rest = match.group(1).strip()
            if same_line_rest and _has_real_content(same_line_rest):
                clipped = _clip_at_next_label(same_line_rest, aliases)
                if accepts(clipped):
                    return clipped, bbox

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
                # The candidate must be able to BE this field's value.
                # Without this the nearest line wins outright, and on a
                # real receipt the nearest line to the right of "Total"
                # was a doctor's rubber stamp overprinted across the
                # total row -- so `amount` came back
                # "Dr. Poorna Weerasinghe" while "3,050.00" sat further
                # right on the same row, read at 0.98 confidence. Three
                # documents in the real set failed exactly that way.
                and accepts(_strip_leading_marker(other_text))
            ]
            if same_row:
                other_text, other_bbox = min(same_row, key=lambda item: item[1][0])
                return _strip_leading_marker(other_text), other_bbox

            row_height = y1 - y0
            below_limit = (
                page_height_px * _BELOW_SEARCH_PAGE_FRACTION
                if page_height_px
                else _BELOW_SEARCH_FALLBACK_PX
            )
            below = [
                (other_text, other_bbox)
                for j, (other_text, other_bbox) in enumerate(lines)
                if j != i
                and other_bbox[1] > y1
                and other_bbox[1] - y1 <= max(row_height * 4, below_limit)
                and _ranges_overlap(x0, x1, other_bbox[0], other_bbox[2], min_overlap_ratio=0.2)
                and not _looks_like_a_label(other_text)
                and _has_real_content(other_text)
                and accepts(_strip_leading_marker(other_text))
            ]
            if below:
                other_text, other_bbox = min(below, key=lambda item: item[1][1])
                return _strip_leading_marker(other_text), other_bbox

            # This label had no usable value. Keep looking rather than
            # giving up on the field: with a type gate in play, "the
            # label matched but everything near it was the wrong kind of
            # thing" is now an ordinary outcome, and a later alias (or a
            # later occurrence of the same one) may still be the real
            # one. Before the gate existed every match produced SOME
            # string, so stopping here cost nothing; now it would throw
            # the field away on the first near-miss.
            continue
    return None, None


def _spatial_label_value(
    lines_by_page: dict[int, list[LineBlock]] | None,
    aliases: list[str],
    page_heights_px: dict[int, float] | None = None,
    accepts: "Callable[[str], bool] | None" = None,
) -> tuple[str | None, int | None, tuple[int, int, int, int] | None]:
    """Page-ordered wrapper over _spatial_value_on_page -- `lines_by_page`
    is 1-indexed (matching this whole module's page numbering elsewhere),
    absent/empty entirely when line-level data isn't available for this
    document (e.g. a zone/rubber-band crop).
    """
    if not lines_by_page:
        return None, None, None
    for page in sorted(lines_by_page):
        value, bbox = _spatial_value_on_page(
            lines_by_page[page], aliases, (page_heights_px or {}).get(page), accepts
        )
        if value:
            return value, page, bbox
    return None, None, None


def _find_value(
    text: str,
    lines_by_page: dict[int, list[LineBlock]] | None,
    aliases: list[str],
    page_heights_px: dict[int, float] | None = None,
    accepts: "Callable[[str], bool] | None" = None,
) -> tuple[str | None, int | None, tuple[int, int, int, int] | None]:
    """Tries real per-line spatial matching first (_spatial_label_value --
    far more precise when page position data is available), falling back
    to the flattened-text heuristic (_label_proximity_value) only when
    line-level data isn't available at all or the spatial pass found
    nothing on it.

    `accepts` filters candidates to those that could actually BE this
    field's value (see `_acceptor_for`). It is applied to the flattened
    fallback too: a gate that only guards the precise path would let the
    imprecise one through unchecked, which is backwards.
    """
    value, page, bbox = _spatial_label_value(lines_by_page, aliases, page_heights_px, accepts)
    if value:
        return value, page, bbox
    flattened = _label_proximity_value(text, aliases)
    if flattened is not None and accepts is not None and not accepts(flattened):
        return None, None, None
    return flattened, None, None


def _acceptor_for(data_type: str) -> "Callable[[str], bool] | None":
    """What a value of this type is allowed to look like, or None for
    "anything" (the previous behaviour, kept for every free-text field).

    Only `decimal` is gated today. A date already has its own candidate
    extractor (`_extract_date_candidate`) which parses before accepting,
    and gating free text would reject legitimate values -- a vendor name
    or a description has no shape to check against.
    """
    if data_type != "decimal":
        return None

    def accepts_decimal(candidate: str) -> bool:
        return parse_decimal_candidate(candidate).ok

    return accepts_decimal


# A trailing annotation printed after the figure itself: a bracketed
# currency or unit, optionally followed by a short code. Real example,
# Asiri Surgical Hospital: "TOTAL FEE : 4,100.00 (LKR) CS" -- the value
# is 4,100.00 and "(LKR) CS" is the hospital's own payment-mode marker.
_TRAILING_ANNOTATION_RE = re.compile(r"\s*\([^()]*\)\s*[A-Za-z]{0,4}\s*$")


def parse_decimal_candidate(candidate: str):
    """`patterns.parse_amount`, retried once without a trailing annotation.

    `parse_amount` is the module that already knows what money looks like
    in this deployment -- `Rs.`/`LKR` prefixes, `/=` terminators, comma
    decimals, accounting parentheses -- and extraction.py had its own,
    narrower ideas while never calling it. This is the one place that
    decides whether a string can be a `decimal` field, so the gate in
    `_acceptor_for` and the normalisation in `_finalize_result` cannot
    disagree about it.

    The retry is deliberately only attempted when the strict parse has
    already failed, which is what keeps it from eating an accounting
    negative: "(1,234.50)" parses first time, so the annotation stripper
    -- which would otherwise reduce it to nothing -- never sees it.
    """
    parsed = parse_amount(candidate)
    if parsed.ok or not candidate:
        return parsed
    trimmed = _TRAILING_ANNOTATION_RE.sub("", candidate).strip()
    if trimmed and trimmed != candidate.strip() and any(ch.isdigit() for ch in trimmed):
        retried = parse_amount(trimmed)
        if retried.ok:
            return retried
    return parsed


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
    text: str,
    lines_by_page: dict[int, list[LineBlock]] | None,
    aliases: list[str],
    page_heights_px: dict[int, float] | None = None,
) -> tuple[str | None, int | None, tuple[int, int, int, int] | None]:
    candidate, page, bbox = _find_value(text, lines_by_page, aliases, page_heights_px)
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
    spec: FieldSpec,
    text: str,
    lines_by_page: dict[int, list[LineBlock]] | None = None,
    page_heights_px: dict[int, float] | None = None,
    default_currency: str = "",
    known_vendors: list[str] | None = None,
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
        candidate, page, bbox = _find_value(text, lines_by_page, aliases, page_heights_px)
        matched = _match_enum(candidate, options, field_key)
        if matched:
            return matched, fc.TIER_PATTERN, False, page, bbox
        if field_key == "currency":
            # Pattern-rule fallback: real invoice found live with no
            # "Currency:" label anywhere on the page at all (see
            # _currency_pattern_fallback's own comment). Still only ever
            # returns one of the caller's own configured options --
            # _match_enum never returns a value outside that list.
            fallback_matched = _match_enum(_currency_pattern_fallback(text), options, field_key)
            if fallback_matched:
                # CALIBRATED, not asserted. This was a forced
                # low_confidence on the reasoning that a currency read out
                # of running text with no label is a guess. Measured on
                # the real printed set it is **8 of 8 correct**, and
                # keeping it below the line had a cost: `currency` is
                # mandatory for INVOICE, so every one of those 8 documents
                # was reported as missing a mandatory field and sent to
                # the review queue for a value that was right.
                #
                # A pattern tier, therefore, and subject to the ordinary
                # threshold like every other. 8 documents is a thin basis
                # -- re-measure when the corpus grows, and note that the
                # vendor letterhead fallback below was measured the same
                # way, came back 15/18 (83%), and is STILL forced low: the
                # rule is the measurement, not a preference for
                # promotion.
                return fallback_matched, fc.TIER_PATTERN, False, None, None
            # Nothing on the page names a currency -- not a code, not a
            # symbol, not `Rs`, not a `/=` terminator. Six real receipts in
            # the corpus are exactly this: a Sri Lankan hospital prints
            # "Total 3,000.00" and the unit is understood from context.
            #
            # The tenant default fills it in, and says so: `defaulted` is
            # not `extracted`, so this never counts as OCR accuracy and
            # never sends the document to review. Still routed through
            # `_match_enum`, so a configured default that is not one of
            # this field's allowed options produces nothing rather than an
            # invalid value.
            default_matched = _match_enum(default_currency, options, field_key)
            if default_matched:
                return default_matched, fc.TIER_DEFAULTED, False, None, None
        return None, None, False, None, None

    if data_type in ("date", "datetime"):
        raw_date, page, bbox = _extract_date_candidate(text, lines_by_page, aliases, page_heights_px)
        if raw_date is None:
            return None, None, False, None, None
        from_label = page is not None or _label_proximity_value(text, aliases) is not None
        # Returns the RAW matched string. Normalisation moved into
        # `_finalize_result`, which is the only place that holds both
        # forms at once and can therefore record `raw_value` alongside the
        # ISO value (production prompt 3.8: "store the raw OCR string
        # alongside every normalized value"). Normalising here threw the
        # original away, so `2026-08-10` gave a reviewer no way to see
        # that the page actually said `10/08/2026` -- which is exactly
        # what they need in order to judge a day-first/month-first
        # ambiguity.
        return raw_date, (fc.TIER_SPATIAL if from_label else fc.TIER_PATTERN), False, page, bbox

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
        candidate, page, bbox = _find_value(text, lines_by_page, aliases, page_heights_px)
        if candidate:
            return candidate.strip(), fc.TIER_PATTERN, False, page, bbox
        match = _REFERENCE_NUMBER_RE.search(text)
        return (match.group(1).strip(), fc.TIER_PATTERN, False, None, None) if match else (None, None, False, None, None)

    if data_type in ("long_text", "multi_select"):
        candidate, page, bbox = _find_value(text, lines_by_page, aliases, page_heights_px)
        if candidate:
            if data_type == "multi_select":
                value = _split_list_value(candidate)
                if value:
                    return value, fc.TIER_SPATIAL, False, None, None  # a split list has no single bbox
            else:
                value = candidate.strip()
                if value:
                    return value, fc.TIER_SPATIAL, False, page, bbox
        # Free-text fallback: generated FROM the document, not found via a
        # label -- always low_confidence per spec, whatever the number below.
        value = _keywords_from_text(text) if data_type == "multi_select" else _summary_from_text(text)
        return (value, fc.TIER_FALLBACK, True, None, None) if value else (None, None, False, None, None)

    if field_key == "vendor_supplier_name":
        candidate, page, bbox = _find_value(text, lines_by_page, aliases, page_heights_px)
        if candidate:
            return candidate.strip(), fc.TIER_SPATIAL, False, page, bbox
        # A vendor this tenant has already confirmed, recognised on the
        # page. Above the letterhead fallback because it is evidence
        # rather than position: a human has previously said "this page's
        # issuer is called exactly this".
        known = _known_vendor_match(lines_by_page, known_vendors or [])
        if known:
            name, bbox = known
            return name, fc.TIER_PATTERN, False, 1, bbox
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
            return name, fc.TIER_FALLBACK, True, 1, bbox
        return None, None, False, None, None

    if field_key == "invoice_number":
        candidate, page, bbox = _find_value(text, lines_by_page, aliases, page_heights_px)
        if candidate:
            return candidate.strip(), fc.TIER_SPATIAL, False, page, bbox
        # No label anywhere: four real receipts print the number large at
        # the top of the page and nothing else. See
        # `_prominent_document_number` for why this is safe to reach.
        prominent = _prominent_document_number(
            lines_by_page, (page_heights_px or {}).get(1)
        )
        if prominent:
            value, bbox = prominent
            return value, fc.TIER_PATTERN, False, 1, bbox
        return None, None, False, None, None

    # Generic single-line text field (author, department, amount, and
    # anything else with no more specific handling above).
    #
    # `amount` reaches here: there has never been a `decimal` branch, so a
    # money field was resolved by the same unchecked "nearest line to the
    # right of the label" rule as a free-text one. `_acceptor_for` is what
    # makes that rule type-aware.
    candidate, page, bbox = _find_value(
        text, lines_by_page, aliases, page_heights_px, _acceptor_for(data_type)
    )
    return (candidate.strip(), fc.TIER_SPATIAL, False, page, bbox) if candidate else (None, None, False, None, None)


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
    spec: FieldSpec, value, tier_or_confidence, page: int | None, bbox, threshold: float,
    forced_low_confidence: bool = False, line_confidences: dict | None = None,
) -> RequestedFieldResult:
    is_empty = value is None or (isinstance(value, list) and not value) or (isinstance(value, str) and not value.strip())
    if is_empty:
        return RequestedFieldResult(field_key=spec.field_key, value=None, confidence=None, status="not_found")

    # A tenant default, not a reading. It carries no OCR evidence, so it
    # gets no confidence rather than a made-up one -- a number here would
    # be indistinguishable from a real low-confidence match, which is
    # exactly the confusion `status` exists to prevent.
    if tier_or_confidence == field_confidence.TIER_DEFAULTED:
        return RequestedFieldResult(
            field_key=spec.field_key, value=value, raw_value=None,
            confidence=None, status="defaulted",
        )

    # The raw string as it was read off the page, kept alongside the
    # normalized value (production prompt 3.8). Normalisation happens
    # HERE rather than in the per-field resolver so that both forms exist
    # in one place; doing it upstream discarded the original.
    #
    # For most fields the two are identical -- nothing is normalized --
    # and `raw_value` is still populated rather than left null, so a
    # consumer never has to guess whether "null" means "same as value" or
    # "we lost it".
    raw_value = value if isinstance(value, str) else None
    if isinstance(value, str) and spec.data_type in ("date", "datetime"):
        value = _normalize_date(value)
    elif isinstance(value, str) and spec.data_type == "decimal":
        # A `decimal` field must come back as a number, not as the page's
        # rendering of one. Two real OAK invoices returned the right
        # figure as "LKR 188,500.00" and "LKR 220,000.00" -- correct to
        # the last digit and still unusable, because the currency belongs
        # in the `currency` field and dms-core's `parseNumber` is handed a
        # string with letters in it. The printed form is not lost: it is
        # `raw_value`, captured two lines above.
        parsed = parse_decimal_candidate(value)
        if parsed.ok:
            value = str(parsed.value)
            # "1.234" is either 1234 with a European thousands separator
            # or 1.234 with a decimal point, and the token does not say
            # which. `parse_amount` returns the commoner reading and flags
            # it rather than guessing silently; a wrong guess is a 1000x
            # error on an invoice total, so the value is kept and the
            # claim of confidence is not.
            forced_low_confidence = forced_low_confidence or parsed.ambiguous

    # Production prompt 3.10: the score is COMPUTED, not asserted.
    # `tier_or_confidence` is a tier name from the resolver above; a zone
    # hit passes its own float straight through, because a zone template
    # match already carries the engine's confidence for the cropped
    # region and there is no better number to multiply it by.
    if isinstance(tier_or_confidence, str):
        confidence = field_confidence.compute(
            ocr_confidence=field_confidence.mean_ocr_confidence_for_bbox(
                (line_confidences or {}).get(page), bbox
            ),
            tier=tier_or_confidence,
        )
    else:
        confidence = tier_or_confidence

    status = "low_confidence" if forced_low_confidence or confidence is None or confidence < threshold else "extracted"
    return RequestedFieldResult(
        field_key=spec.field_key, value=value, raw_value=raw_value,
        confidence=confidence, status=status, page=page, bbox=bbox,
    )


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
    # 1-indexed page number -> that page's pixel height, so the
    # "value stacked below the label" search radius stays a constant
    # fraction of the page rather than a fixed pixel count that would
    # silently shrink when the render DPI rises.
    page_heights_px: dict[int, float] | None = None,
    field_confidence_threshold: float = 0.6,
    # 1-indexed page -> [(line bbox, that line's OCR confidence)], so a
    # field's score can carry the recognition confidence of the tokens it
    # actually came from (production prompt 3.10). Absent for a native-text
    # page, where there is no character-level uncertainty and the OCR term
    # is 1.0 by definition.
    line_confidences: dict[int, list] | None = None,
    # The tenant's currency when the page names none at all. Empty means
    # "no default" -- the honest setting for a tenant that genuinely bills
    # in more than one. See `Settings.ocr_default_currency`.
    default_currency: str = "",
    # Vendor names this tenant has already CONFIRMED -- accepted or
    # corrected by a human in the review screen, never OCR output, so the
    # list cannot teach itself its own mistakes. See `_known_vendor_match`.
    known_vendors: list[str] | None = None,
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
        if not spec.ocr_extractable:
            # Not attempted at all -- not even a zone template, which would
            # otherwise be a way for a field this flag excludes to come
            # back anyway. `status="not_extractable"` rather than
            # `not_found`, because the two mean different things to a
            # reviewer and to `requires_review`: "we looked and it is not
            # there" versus "this is not something a document tells us".
            results.append(RequestedFieldResult(
                field_key=spec.field_key, value=None, confidence=None,
                status="not_extractable",
            ))
            continue
        zone_hit = zone_fields.get(spec.field_key)
        if zone_hit is not None:
            results.append(
                _finalize_result(spec, zone_hit.value, zone_hit.confidence, zone_hit.page, zone_hit.bbox, field_confidence_threshold)
            )
            continue
        value, tier, forced_low_confidence, page, bbox = _extract_field_value(
            spec, text, lines_by_page, page_heights_px, default_currency, known_vendors
        )
        results.append(_finalize_result(
            spec, value, tier, page, bbox, field_confidence_threshold,
            forced_low_confidence, line_confidences,
        ))

    return _apply_effective_expiry_validation(results)


def requested_fields_to_output_dict(
    results: list[RequestedFieldResult], geometry_by_page: dict | None = None
) -> dict[str, dict]:
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
        output[result.field_key] = {
            "value": value,
            "confidence": result.confidence,
            "bbox": list(result.bbox) if result.bbox else None,
            "bbox_norm": normalize_bbox_for_page(geometry_by_page, result.page, result.bbox),
        }
    return output
