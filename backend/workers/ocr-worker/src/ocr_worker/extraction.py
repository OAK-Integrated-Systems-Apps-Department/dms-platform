from __future__ import annotations

import json
import re
from collections import Counter
from collections.abc import Hashable, Iterable, Mapping, Sequence
from dataclasses import dataclass, replace
from datetime import datetime
from types import MappingProxyType
from typing import NamedTuple

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
# stays blank. Ambiguous numeric dates are read month-first (US
# convention) before falling back to day-first.
_DATE_FORMATS = (
    "%Y-%m-%d",
    "%m/%d/%Y", "%m-%d-%Y", "%m.%d.%Y",
    "%m/%d/%y", "%m-%d-%y", "%m.%d.%y",
    "%d/%m/%Y", "%d-%m-%Y", "%d.%m.%Y",
    "%d/%m/%y", "%d-%m-%y", "%d.%m.%y",
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

# Which printed labels ("Invoice No", "PO Number", "Prepared by", ...) each
# requested field is looked for under. This used to be a hardcoded
# `_LABEL_ALIASES` dict right here, keyed by field_key -- which meant a
# tenant-defined field (anything outside the ~17 keys that dict happened to
# know) could only ever be found under its own key with underscores turned
# into spaces, and adding one synonym seen on a real document meant a code
# change and a redeploy of this worker. That vocabulary now lives in
# dms-core's metadata_field_label_aliases table (V40) as tenant-editable
# "label hints", seeded from the old dict verbatim by V41 (plus "number" for
# purchase_order_number, which replaces the hardcoded bare-"Number" branch
# _extract_field_value used to have -- see build_label_vocabulary's
# standalone rule), and repository.py's get_label_vocabulary reads it once
# per document. Everything that decides WHICH hints a field gets (type-scope
# precedence, de-duplication, the field-name fallback, the stop-label set,
# the standalone rule) is pure and lives in build_label_vocabulary below, so
# it stays unit-testable without a database; the matching functions further
# down only ever receive its result. The matching algorithm itself is
# unchanged -- only where the aliases come from. The priority reasoning that
# used to be commented inline on the dict (e.g. "total amount" must be tried
# before the bare "amount", which otherwise matches a line-items table's
# "Net Amount" header first) is now carried by each hint's display_order --
# see V41's own header and tests/label_vocabulary_seed.py, which mirrors it.

# Built-in clip-only stop labels: real header labels that commonly sit right
# next to an extractable field's own label/value on the same OCR'd line
# (e.g. "Vendor / Supplier: ... Ship To / Department: ...") but aren't
# themselves one of this app's metadata fields, so no tenant's label hints
# would ever contain them. They're structural boundaries of a document
# rather than field vocabulary, which is why they stay in code instead of
# moving to metadata_field_label_aliases with everything else. Only ever used
# to bound _clip_at_next_label/_looks_like_a_label (via
# LabelVocabulary.known_labels) and as part of the standalone rule's
# reference set -- never matched as a field's own hint.
BUILTIN_STOP_LABELS: tuple[str, ...] = ("ship to", "bill to", "sold to")

# Built-in reference-only labels for build_label_vocabulary's standalone
# rule (rule 4): never matched as any field's hint and never a stop label
# either -- only counted when deciding whether a one-word hint is too
# ambiguous to trust as an ordinary label. Without them, whether a bare
# "Number" hint gets the whole-line-only protection would depend on which
# OTHER fields a tenant's catalogue happens to contain: it's the seeded
# invoice_number/reference_number hints ("invoice number", "reference
# number") that make purchase_order_number's seeded "number" standalone, so
# a tenant without those two fields (a hand-made catalogue, a hard-deleted
# field -- its hints cascade away with it -- or an admin trimming those
# fields' hints on the field form) would silently get an unanchored "number"
# that matches the tail of an "Invoice Number:"/"Account Number:" printed on
# the document and steals its value. That's exactly the Purchase Order vs
# Invoice "Number" ambiguity type-scoped hints exist to keep safe, and the
# old hardcoded bare-"Number" branch was whole-line-only regardless of the
# catalogue -- these keep that true. Deliberately NOT part of known_labels:
# they'd start clipping values ("Supplier: Acme Ltd Account Number: 12345")
# that were never clipped before.
BUILTIN_AMBIGUOUS_TAIL_LABELS: tuple[str, ...] = ("invoice number", "order number", "account number", "reference number")

# document_ocr_field_results.match_basis values (V40's
# ck_document_ocr_field_results_match_basis): HOW a result was found -- or,
# for a not_found result, WHAT was searched for -- so the upload page can
# say "no label hints configured, searched for the field's own name" rather
# than a bare, unexplained "not found". The field-name fallback in
# particular must never be silent: a tenant field with no hints still gets
# searched for, just under its own label, and the result says so. A
# not_found result only ever claims a label search that really ran: the
# language field is never label-searched (langdetect over the whole text is
# the only thing tried), so its not_found records "generated", not the
# "field_name"/"label_hint" of hints nobody looked for.
MATCH_BASIS_LABEL_HINT = "label_hint"  # through a configured label hint (not_found: hints configured, no value found under any)
MATCH_BASIS_FIELD_NAME = "field_name"  # no hints configured -- through the field's own label (not_found: searched for it)
MATCH_BASIS_ZONE = "zone"  # a zone template region supplied the value
MATCH_BASIS_PATTERN = "pattern"  # a value-shape regex alone (bare date pattern, reference-number pattern)
MATCH_BASIS_GENERATED = "generated"  # derived from the whole text (summary/keywords fallback, language detection)

# Obvious classification synonyms -> this app's seeded classification_level
# option values (see V30__seed_metadata_field_catalogue_and_document_types.sql).
# Deliberately small and explicit, same reasoning as the label hints above --
# never a fuzzy/semantic match, and a synonym is only ever used if its target
# is actually present in the caller-supplied `options` (never invents a value
# outside that list). Value-shape logic, not label vocabulary, so it stays in
# code rather than moving to metadata_field_label_aliases.
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
    # One of the MATCH_BASIS_* values above -- how this value was found, or
    # for a not_found result, what was searched for. None only for a result
    # built outside extract_requested_fields.
    match_basis: str | None = None


@dataclass(frozen=True)
class FieldLabelHints:
    """The resolved label hints for ONE requested field -- see
    build_label_vocabulary for how they're chosen. `primary` hints go
    through _label_pattern's normal rules, in priority order. `standalone`
    hints are the ambiguous one-word ones (e.g. "number", which is also the
    tail of "Invoice Number"/"Reference Number") that are only trusted as a
    label's ENTIRE line (whole_line_only=True), and only after every primary
    hint has found nothing -- see _find_field_value. `basis` is what a match
    through either tier records as its match_basis: "label_hint" when the
    tenant configured hints for this field, "field_name" when it didn't and
    these tiers just hold the field's own label.
    """

    field_key: str
    primary: tuple[str, ...]
    standalone: tuple[str, ...] = ()
    basis: str = MATCH_BASIS_LABEL_HINT

    @property
    def all_hints(self) -> tuple[str, ...]:
        """Both tiers together, primary first -- a convenience for callers
        (and tests) that want the field's whole hint list. NOT the
        own-alias exclusion set for _clip_at_next_label: _find_field_value
        searches one tier at a time and each tier excludes only its own
        hints, exactly as the old separate alias-list/bare-"number" calls
        did -- so a primary label like "PO Number:" crammed after a value
        the bare "Number" found still clips it. A standalone hint is never
        a stop label to begin with (see build_label_vocabulary's rule 3).
        """
        return self.primary + self.standalone


@dataclass(frozen=True)
class LabelVocabulary:
    """Immutable, per-document label vocabulary, built by
    build_label_vocabulary (never by hand outside tests). `fields` maps each
    requested field_key to its resolved hints. `known_labels` is every label
    ANY of this tenant's fields could be printed under on this document type
    (its primary hints -- a standalone hint is too ambiguous to mark
    anything, see build_label_vocabulary's rule 3), plus
    BUILTIN_STOP_LABELS -- used only to tell "this is where the NEXT
    field's label starts" apart from "this is still part of the current
    field's value" (_clip_at_next_label/_looks_like_a_label). Longest first
    so a compound label like "total amount" is recognized as itself rather
    than accidentally matched via a shorter one first.
    """

    fields: Mapping[str, FieldLabelHints]
    known_labels: tuple[str, ...]

    def hints_for(self, field_key: str, label: str | None = None) -> FieldLabelHints:
        """`field_key`'s resolved hints, or -- for a key this vocabulary was
        never built for (pipeline.py always builds it for exactly the
        requested keys, so in practice only a hand-built test vocabulary
        gets here) -- the same field-name fallback build_label_vocabulary
        itself applies: `label` if given, else the key with underscores
        turned into spaces, split into tiers by the standalone rule against
        known_labels plus BUILTIN_AMBIGUOUS_TAIL_LABELS (the closest
        reference set available without the builder's per-field alias
        lists).
        """
        hints = self.fields.get(field_key)
        if hints is not None:
            return hints
        tails = _standalone_tails([*self.known_labels, *BUILTIN_AMBIGUOUS_TAIL_LABELS])
        return _classify_hints(field_key, [_field_name_hint(field_key, label)], MATCH_BASIS_FIELD_NAME, tails)


class LabelFieldRow(NamedTuple):
    """One non-deleted metadata_fields row of the tenant's catalog, as
    build_label_vocabulary needs it -- repository.py's get_label_vocabulary
    builds these from its query; tests build them directly.
    """

    field_id: Hashable
    field_key: str
    label: str | None


class LabelAliasRow(NamedTuple):
    """One metadata_field_label_aliases row. `document_type_id` None means
    catalog-level ("global": applies wherever the field is mapped);
    otherwise the hint only applies to documents of that document type.
    """

    field_id: Hashable
    document_type_id: Hashable | None
    alias_text: str
    display_order: int


def _field_name_hint(field_key: str, label: str | None) -> str:
    """The field-name fallback for a field with no applicable hints: its
    own catalog label (e.g. "PO Date" -- NOT NULL in the database), or, if
    the key isn't in the catalog at all or the label is blank, the key with
    underscores turned into spaces -- which is exactly what the old
    hardcoded dict fell back to for a key it didn't know.
    """
    if label and label.strip():
        return label.strip()
    return field_key.replace("_", " ")


def _dedupe_casefold(labels: Iterable[str]) -> list[str]:
    """Case-insensitive de-duplication keeping the FIRST occurrence (and
    its original casing) -- so a type-scoped hint that repeats a global one
    keeps the type-scoped hint's higher priority position.
    """
    seen: set[str] = set()
    result: list[str] = []
    for label in labels:
        key = label.lower()
        if key not in seen:
            seen.add(key)
            result.append(label)
    return result


def _standalone_tails(reference_labels: Iterable[str]) -> frozenset[str]:
    """The last whitespace-separated token (lowercased) of every MULTI-word
    label in `reference_labels` -- a one-word hint that is one of these is
    ambiguous by construction (see build_label_vocabulary's rule 4).
    """
    tails: set[str] = set()
    for label in reference_labels:
        tokens = label.split()
        if len(tokens) > 1:
            tails.add(tokens[-1].lower())
    return frozenset(tails)


def _classify_hints(field_key: str, hints: Sequence[str], basis: str, tails: frozenset[str]) -> FieldLabelHints:
    def is_standalone(hint: str) -> bool:
        return len(hint.split()) == 1 and hint.lower() in tails

    return FieldLabelHints(
        field_key=field_key,
        primary=tuple(h for h in hints if not is_standalone(h)),
        standalone=tuple(h for h in hints if is_standalone(h)),
        basis=basis,
    )


def build_label_vocabulary(
    *,
    document_type_id: Hashable | None,
    field_rows: Iterable[LabelFieldRow],
    alias_rows: Iterable[LabelAliasRow],
    requested_keys: Iterable[str],
) -> LabelVocabulary:
    """Resolves one tenant's label hints for one document -- pure, no I/O
    (repository.py's get_label_vocabulary does the single query and hands
    the rows here). `field_rows` is the tenant's whole non-deleted catalog
    (any status), `alias_rows` every hint row of those fields. Rules:

    1. Per requested field_key: hints scoped to THIS document type (by
       display_order), then the field's global hints (document_type_id
       NULL, by display_order), case-insensitively de-duplicated keeping
       the first. Hints scoped to any OTHER document type are ignored
       entirely -- here, in known_labels and in the standalone rule alike.
       That scoping is what lets a bare "Number" mean the PO number on a
       Purchase Order without making it ambiguous on an Invoice.
    2. No applicable hints -> the field-name fallback (_field_name_hint),
       basis "field_name"; otherwise basis "label_hint".
    3. known_labels = every global hint of every tenant field, plus this
       document type's type-scoped hints, plus BUILTIN_STOP_LABELS --
       lowercased (every pattern that uses them is case-insensitive
       anyway), de-duplicated, longest first, ties alphabetical for
       determinism -- EXCEPT a hint rule 4 makes standalone for its field
       (unless some other field has it as a primary hint). A standalone
       hint is by construction the tail of a more specific label, and a
       stop label is matched anywhere on a word boundary (see
       _clip_at_next_label), so the seeded "number" as a stop label would
       clip every other field's value
       at any "Phone Number:"/"VAT Number:"/"number-plate" inside it -- the
       very tail-matching the standalone tier exists to avoid, and not
       something the old stop set ever did (the bare "Number" was never in
       it). Field-name fallbacks are NOT added either: before this moved to
       the database the stop set was exactly the configured aliases plus the
       built-in extras, and a field's bare catalog label (e.g. "Date") was
       never a stop label. Over the V41 seed this is exactly the old stop
       set.
    4. Standalone rule: a one-word hint of field K is standalone iff it is
       the last token of some MULTI-word hint of a DIFFERENT field (global
       or this type's), or of a BUILTIN_STOP_LABELS /
       BUILTIN_AMBIGUOUS_TAIL_LABELS entry. Over the V41 seed this flags
       exactly one hint -- "number" on purchase_order_number, the tail of
       "invoice number"/"reference number" -- i.e. exactly the old
       hardcoded branch, while every other field's hints stay primary as
       before; the built-in reference labels keep "number" standalone even
       for a tenant whose catalogue lacks those two fields. A field's OWN
       multi-word hints never make its own one-word hint standalone
       ("supplier" stays primary next to vendor_supplier_name's own "vendor
       / supplier"). Applies to field-name fallbacks too, deliberately: a
       custom field whose only label is one word that ends another field's
       label ("Supplier", "Date", "Total") is matched only as its label's
       whole line, where the old key search matched it on a word boundary
       and would take "Vendor / Supplier:"'s, "Invoice Date:"'s or "Grand
       Total:"'s value as its own. Such a field's not_found result says
       match_basis "field_name", i.e. "configure a label hint" (e.g. "Grand
       Total"), rather than silently claiming another field's value.

    The result is exactly the tenant's database state: no code-level
    default hints for well-known keys, by design -- a field created (or
    hard-deleted and recreated) after V41 ran starts with none and gets the
    field-name fallback until an admin adds some, and the stop set only
    holds labels of fields the tenant actually has.
    """
    fields_by_id = {row.field_id: row for row in field_rows}
    label_by_key = {row.field_key: row.label for row in fields_by_id.values()}

    type_scoped: dict[str, list[LabelAliasRow]] = {}
    global_scope: dict[str, list[LabelAliasRow]] = {}
    for row in alias_rows:
        field = fields_by_id.get(row.field_id)
        text = (row.alias_text or "").strip()
        if field is None or not text:
            continue
        if row.document_type_id is None:
            bucket = global_scope
        elif document_type_id is not None and row.document_type_id == document_type_id:
            bucket = type_scoped
        else:
            continue  # rule 1: another document type's hint never applies here
        bucket.setdefault(field.field_key, []).append(row._replace(alias_text=text))

    def in_priority_order(rows: list[LabelAliasRow]) -> list[str]:
        return [r.alias_text for r in sorted(rows, key=lambda r: (r.display_order, r.alias_text.lower()))]

    applicable: dict[str, list[str]] = {
        key: _dedupe_casefold(in_priority_order(type_scoped.get(key, [])) + in_priority_order(global_scope.get(key, [])))
        for key in set(type_scoped) | set(global_scope)
    }

    # Rule 4's reference set for field K is every OTHER field's applicable
    # hints plus the built-in reference labels, and only the tails of its
    # multi-word labels matter -- so count how many fields contribute each
    # tail and discount K's own contribution, rather than rebuilding the
    # set once per field (rule 3 needs every tenant field classified, not
    # just the requested ones).
    builtin_tails = _standalone_tails([*BUILTIN_STOP_LABELS, *BUILTIN_AMBIGUOUS_TAIL_LABELS])
    own_tails = {key: _standalone_tails(labels) for key, labels in applicable.items()}
    tail_owners = Counter(tail for tails in own_tails.values() for tail in tails)

    def reference_tails(key: str) -> frozenset[str]:
        mine = own_tails.get(key, frozenset())
        return builtin_tails | {tail for tail, owners in tail_owners.items() if owners > (1 if tail in mine else 0)}

    classified = {
        key: _classify_hints(key, labels, MATCH_BASIS_LABEL_HINT, reference_tails(key)) for key, labels in applicable.items()
    }

    known = {hint.lower() for hints in classified.values() for hint in hints.primary} | set(BUILTIN_STOP_LABELS)
    known_labels = tuple(sorted(known, key=lambda label: (-len(label), label)))

    fields: dict[str, FieldLabelHints] = {}
    for key in dict.fromkeys(requested_keys):
        hints = classified.get(key)
        if hints is None:
            fallback = [_field_name_hint(key, label_by_key.get(key))]
            hints = _classify_hints(key, fallback, MATCH_BASIS_FIELD_NAME, reference_tails(key))
        fields[key] = hints

    return LabelVocabulary(fields=MappingProxyType(fields), known_labels=known_labels)


def _clip_at_next_label(value: str, own_aliases: Sequence[str], known_labels: Sequence[str]) -> str:
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
    isn't mistaken for the next field). `known_labels` is the document's
    LabelVocabulary.known_labels.
    """
    own = {a.lower() for a in own_aliases}
    earliest = None
    for label in known_labels:
        if label.lower() in own:
            continue
        match = re.search(rf"\b{re.escape(label)}\b\s*[:\-]", value, re.IGNORECASE)
        if match and (earliest is None or match.start() < earliest):
            earliest = match.start()
    return value[:earliest].strip() if earliest is not None else value


def _label_pattern(alias: str, whole_line_only: bool = False) -> re.Pattern[str]:
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

    `whole_line_only` additionally requires nothing but optional leading
    whitespace precede the alias -- i.e. the alias must be the label's
    ENTIRE own line/cell, not just its tail. Only used for an alias so
    generic (e.g. "number") that it's also the tail of several other,
    more specific, already-known labels ("Invoice Number:", "Order
    Number:", "Account Number:") -- without this, `\b{alias}\b` (a word
    boundary, not a line-start anchor) would just as happily match those
    other fields' own labels and steal their value. See
    _find_field_value's standalone tier for the only caller, and
    build_label_vocabulary's rule 4 for which hints end up in it.
    """
    require_marker = " " not in alias.strip()
    marker = r"\s*(?:[:\-]|$)" if require_marker else r"\s*[:\-]?"
    start = r"^\s*" if whole_line_only else r"\b"
    return re.compile(rf"{start}{re.escape(alias)}\b{marker}\s*(.*)$", re.IGNORECASE)


def _label_proximity_value(
    text: str,
    aliases: Sequence[str],
    known_labels: Sequence[str],
    whole_line_only: bool = False,
    table_header_texts: set[str] | None = None,
) -> str | None:
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

    `table_header_texts` (see pipeline.py's _run_full_document) is the set
    of lowercased cell text Docling's real table-structure model marked as
    a column header, ACROSS the whole document -- a line-items table's
    cells are merged into this same flattened text (see
    _merge_native_text_and_tables), each cell its own line, so its header
    row is otherwise indistinguishable here from a genuine document-level
    field label. A line matching one of these is never treated as a label
    occurrence for any field.

    `known_labels` is the document's LabelVocabulary.known_labels (the
    stop set for _clip_at_next_label).
    """
    lines = [line.strip() for line in text.splitlines()]
    headers = table_header_texts or set()
    for alias in aliases:
        pattern = _label_pattern(alias, whole_line_only)
        for i, line in enumerate(lines):
            if line.lower() in headers:
                continue
            match = pattern.search(line)
            if not match:
                continue
            value = match.group(1).strip()
            if value:
                return _clip_at_next_label(value, aliases, known_labels)
            for next_line in lines[i + 1 :]:
                if next_line and _has_real_content(next_line):
                    return _clip_at_next_label(_strip_leading_marker(next_line), aliases, known_labels)
            return None
    return None


LineBlock = tuple[str, tuple[int, int, int, int]]  # (text, (x0, y0, x1, y1)) in page-pixel space, at OCR_RENDER_DPI


def _looks_like_a_label(text: str, known_labels: Sequence[str]) -> bool:
    """True if `text` contains one of the document's known field labels
    (LabelVocabulary.known_labels) -- used to stop spatial matching from
    picking ANOTHER field's label as if it were this field's value. Real
    invoices often put two label/value columns side by side on the same
    visual row, sometimes behind a leading connector (e.g. "Vendor /
    Supplier:" ... "Ship To / Department:" -- the label itself is
    "Department:", not at the very start of that span) -- the second is a
    different field's label, not the first field's value, even though it's
    the nearest thing to the right of the first. Deliberately a substring
    search (not anchored to the start of `text`), not just a stricter
    startswith check.
    """
    for label in known_labels:
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


def _spatial_value_on_page(
    lines: list[LineBlock],
    aliases: Sequence[str],
    known_labels: Sequence[str],
    whole_line_only: bool = False,
    table_header_texts: set[str] | None = None,
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

    `table_header_texts` (see _label_proximity_value's own doc comment on
    the same param) excludes a line from being treated as a label
    occurrence at all when Docling's real table-structure model already
    identified that exact text as one of a table's own column headers --
    e.g. a line-items table's "Description" header cell, which would
    otherwise be indistinguishable here from a genuine document-level
    "Description" field label and return the ADJACENT header cell
    ("Size") as if it were that field's value.

    `known_labels`: see _label_proximity_value's own doc comment on the
    same param.
    """
    headers = table_header_texts or set()
    for alias in aliases:
        pattern = _label_pattern(alias, whole_line_only)
        for i, (text, bbox) in enumerate(lines):
            if text.strip().lower() in headers:
                continue
            match = pattern.search(text)
            if not match:
                continue
            same_line_rest = match.group(1).strip()
            if same_line_rest and _has_real_content(same_line_rest):
                return _clip_at_next_label(same_line_rest, aliases, known_labels), bbox

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
                and not _looks_like_a_label(other_text, known_labels)
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
                and not _looks_like_a_label(other_text, known_labels)
                and _has_real_content(other_text)
            ]
            if below:
                other_text, other_bbox = min(below, key=lambda item: item[1][1])
                return _strip_leading_marker(other_text), other_bbox

            return None, None
    return None, None


def _spatial_label_value(
    lines_by_page: dict[int, list[LineBlock]] | None,
    aliases: Sequence[str],
    known_labels: Sequence[str],
    whole_line_only: bool = False,
    table_header_texts: set[str] | None = None,
) -> tuple[str | None, int | None, tuple[int, int, int, int] | None]:
    """Page-ordered wrapper over _spatial_value_on_page -- `lines_by_page`
    is 1-indexed (matching this whole module's page numbering elsewhere),
    absent/empty entirely when line-level data isn't available for this
    document (e.g. a zone/rubber-band crop).
    """
    if not lines_by_page:
        return None, None, None
    for page in sorted(lines_by_page):
        value, bbox = _spatial_value_on_page(lines_by_page[page], aliases, known_labels, whole_line_only, table_header_texts)
        if value:
            return value, page, bbox
    return None, None, None


def _find_value(
    text: str,
    lines_by_page: dict[int, list[LineBlock]] | None,
    aliases: Sequence[str],
    known_labels: Sequence[str],
    whole_line_only: bool = False,
    table_header_texts: set[str] | None = None,
) -> tuple[str | None, int | None, tuple[int, int, int, int] | None]:
    """Tries real per-line spatial matching first (_spatial_label_value --
    far more precise when page position data is available), falling back
    to the flattened-text heuristic (_label_proximity_value) only when
    line-level data isn't available at all or the spatial pass found
    nothing on it.
    """
    value, page, bbox = _spatial_label_value(lines_by_page, aliases, known_labels, whole_line_only, table_header_texts)
    if value:
        return value, page, bbox
    return _label_proximity_value(text, aliases, known_labels, whole_line_only, table_header_texts), None, None


def _find_field_value(
    text: str,
    lines_by_page: dict[int, list[LineBlock]] | None,
    hints: FieldLabelHints,
    known_labels: Sequence[str],
    table_header_texts: set[str] | None = None,
) -> tuple[str | None, int | None, tuple[int, int, int, int] | None]:
    """_find_value over one field's resolved hints, in two tiers: every
    primary hint first (normal _label_pattern rules, spatial then
    flattened-text), and only if that found nothing, the standalone hints
    with whole_line_only=True. This is the generic form of what used to be
    purchase_order_number's own hardcoded dispatch: a real purchase order's
    header table labels the PO number with just "Number" as its whole label
    cell (relying on a separate "PURCHASE ORDER" box title elsewhere on the
    page for a human reader's context), but an unanchored "number" would
    just as happily match the TAIL of "Invoice Number:"/"Order
    Number:"/"Account Number:" and steal another field's value. Tried only
    after every primary hint has already found nothing, and whole-line-only,
    a standalone hint can never preempt a more specific label. Which hints
    are standalone is decided once, per document, by
    build_label_vocabulary's rule 4 -- not by field_key here.

    Each tier's own hints are its _clip_at_next_label own-alias exclusion,
    exactly as the old separate calls had it (the dict's alias list, then
    just ["number"]): a value the bare "Number" found is still clipped at a
    "PO Number:" crammed after it on the same line.
    """
    value, page, bbox = _find_value(text, lines_by_page, hints.primary, known_labels, table_header_texts=table_header_texts)
    if value or not hints.standalone:
        return value, page, bbox
    return _find_value(
        text, lines_by_page, hints.standalone, known_labels, whole_line_only=True, table_header_texts=table_header_texts
    )


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
    hints: FieldLabelHints,
    known_labels: Sequence[str],
    table_header_texts: set[str] | None = None,
) -> tuple[str | None, int | None, tuple[int, int, int, int] | None, bool]:
    """Returns (raw_date, page, bbox, via_label) -- `via_label` is True only
    when the date came out of a label hint's own value, False when it came
    from the bare date-pattern fallback below (match_basis "pattern" --
    deliberately independent of the caller's confidence-only `from_label`
    check, which can be True even when the label's value held no date).
    """
    candidate, page, bbox = _find_field_value(text, lines_by_page, hints, known_labels, table_header_texts)
    if candidate:
        match = _DATE_RE.search(candidate)
        if match:
            return (match.group(1) if match.groups() else match.group(0)), page, bbox, True
    # Pattern-rule fallback: no label match, but a date pattern appears
    # somewhere in the text -- lower confidence, applied by the caller, and
    # no single position (matched against concatenated text).
    match = _DATE_RE.search(text)
    return ((match.group(1) if match.groups() else match.group(0)) if match else None), None, None, False


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
    hints: FieldLabelHints,
    known_labels: Sequence[str],
    lines_by_page: dict[int, list[LineBlock]] | None = None,
    table_header_texts: set[str] | None = None,
) -> tuple[str | list[str] | None, float | None, bool, int | None, tuple[int, int, int, int] | None, str | None]:
    """Dispatches ONE requested field (no zone match) through, in order:
    spatial label matching (when lines_by_page is available -- see
    _find_value) -> flattened-text label proximity -> pattern rules ->
    free-text fallback, plus the field-specific handling the spec calls
    for. Returns (value, confidence, forced_low_confidence, page, bbox,
    match_basis) -- `forced_low_confidence` is True only for a
    free-text-fallback value (description/tags/keywords with no label
    match), which must be marked low_confidence regardless of any
    confidence number. `page`/`bbox` are only ever non-None when the value
    came from real per-line spatial matching -- never from the
    flattened-text fallback, a pattern-rule match, or the free-text
    fallback (none of those have one single page position to report).
    `match_basis` says which of those produced the value (hints.basis for
    anything found through a label hint) -- or, when there's no value, what
    was actually searched: hints.basis wherever the field's label hints
    were tried (every path but language), MATCH_BASIS_GENERATED for
    language, whose langdetect pass never looks at a label at all (a
    "Language" hint an admin adds is not used by it either), so its
    not_found never claims a label search that didn't happen.

    There's no field_key-specific branch for purchase_order_number any
    more: its bare "Number" fallback is now the standalone tier every field
    gets through _find_field_value, so it takes the generic text-field path
    below (same 0.8 that branch returned).
    """
    field_key, data_type, options = spec.field_key, spec.data_type, spec.options or []
    not_found = (None, None, False, None, None, hints.basis)

    if field_key == "language":
        lang, prob = _extract_language(text)
        return lang, prob, False, None, None, MATCH_BASIS_GENERATED

    if data_type == "dropdown" and options:
        candidate, page, bbox = _find_field_value(text, lines_by_page, hints, known_labels, table_header_texts)
        matched = _match_enum(candidate, options, field_key)
        return (matched, 0.85, False, page, bbox, hints.basis) if matched else not_found

    if data_type in ("date", "datetime"):
        raw_date, page, bbox, via_label = _extract_date_candidate(text, lines_by_page, hints, known_labels, table_header_texts)
        if raw_date is None:
            return not_found
        # Same tiering as the candidate search itself (primary, then
        # standalone whole-line), flattened text only -- exactly what the
        # old single-alias-list check did for a field with no standalone hints.
        from_label = page is not None or _find_field_value(text, None, hints, known_labels, table_header_texts)[0] is not None
        basis = hints.basis if via_label else MATCH_BASIS_PATTERN
        return _normalize_date(raw_date), (0.9 if from_label else 0.65), False, page, bbox, basis

    if field_key == "reference_number":
        match = _REFERENCE_NUMBER_RE.search(text)
        if match:
            return match.group(1).strip(), 0.85, False, None, None, MATCH_BASIS_PATTERN
        candidate, page, bbox = _find_field_value(text, lines_by_page, hints, known_labels, table_header_texts)
        return (candidate.strip(), 0.7, False, page, bbox, hints.basis) if candidate else not_found

    if data_type in ("long_text", "multi_select"):
        candidate, page, bbox = _find_field_value(text, lines_by_page, hints, known_labels, table_header_texts)
        if candidate:
            if data_type == "multi_select":
                value = _split_list_value(candidate)
                if value:
                    return value, 0.75, False, None, None, hints.basis  # a split list has no single bbox
            else:
                value = candidate.strip()
                if value:
                    return value, 0.75, False, page, bbox, hints.basis
        # Free-text fallback: generated FROM the document, not found via a
        # label -- always low_confidence per spec, whatever the number below.
        value = _keywords_from_text(text) if data_type == "multi_select" else _summary_from_text(text)
        return (value, 0.5, True, None, None, MATCH_BASIS_GENERATED) if value else not_found

    # Generic single-line text field (author, department,
    # purchase_order_number, and anything else with no more specific
    # handling above).
    candidate, page, bbox = _find_field_value(text, lines_by_page, hints, known_labels, table_header_texts)
    return (candidate.strip(), 0.8, False, page, bbox, hints.basis) if candidate else not_found


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
    spec: FieldSpec,
    value,
    confidence: float | None,
    page: int | None,
    bbox,
    threshold: float,
    forced_low_confidence: bool = False,
    *,
    match_basis: str | None = None,
) -> RequestedFieldResult:
    """`match_basis` is recorded whatever the status: how a value was
    found, or for an empty one WHAT was searched (configured hints vs the
    field's own name, see _extract_field_value), so a not_found row still
    explains itself.
    """
    is_empty = value is None or (isinstance(value, list) and not value) or (isinstance(value, str) and not value.strip())
    if is_empty:
        return RequestedFieldResult(field_key=spec.field_key, value=None, confidence=None, status="not_found", match_basis=match_basis)
    status = "low_confidence" if forced_low_confidence or confidence is None or confidence < threshold else "extracted"
    return RequestedFieldResult(
        field_key=spec.field_key, value=value, confidence=confidence, status=status, page=page, bbox=bbox, match_basis=match_basis
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
    vocabulary: LabelVocabulary,
    lines_by_page: dict[int, list[LineBlock]] | None = None,
    table_header_texts: set[str] | None = None,
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

    `table_header_texts` (see pipeline.py's _run_full_document) is the
    lowercased text of every cell Docling's real table-structure model
    marked as a column header, across the whole document -- passed through
    to (2) and (3) so a line-items table's own header row is never
    mistaken for a document-level field's label (see
    _spatial_value_on_page/_label_proximity_value's own doc comments).

    `vocabulary` (required -- see build_label_vocabulary, loaded per
    document by pipeline.py via repository.py's get_label_vocabulary) says
    which labels each spec is searched under and which labels mark the
    start of ANOTHER field. Every result carries a match_basis (see the
    MATCH_BASIS_* constants): "zone" for (1); hints.basis ("label_hint" or
    "field_name") for (2)/(3); "pattern" for (4); "generated" for (5) and
    language detection; and, for a not_found result, what was searched for
    -- hints.basis again, "generated" for language (never label-searched),
    or "zone" when an empty zone crop was the only thing tried (a zone hit
    never falls through to label matching).
    """
    known_labels = vocabulary.known_labels
    results: list[RequestedFieldResult] = []
    for spec in specs:
        hints = vocabulary.hints_for(spec.field_key)
        zone_hit = zone_fields.get(spec.field_key)
        if zone_hit is not None:
            results.append(
                _finalize_result(
                    spec,
                    zone_hit.value,
                    zone_hit.confidence,
                    zone_hit.page,
                    zone_hit.bbox,
                    field_confidence_threshold,
                    match_basis=MATCH_BASIS_ZONE,
                )
            )
            continue
        value, confidence, forced_low_confidence, page, bbox, match_basis = _extract_field_value(
            spec, text, hints, known_labels, lines_by_page, table_header_texts
        )
        results.append(
            _finalize_result(
                spec,
                value,
                confidence,
                page,
                bbox,
                field_confidence_threshold,
                forced_low_confidence,
                match_basis=match_basis,
            )
        )

    return _apply_effective_expiry_validation(results)


def requested_fields_to_output_dict(results: list[RequestedFieldResult]) -> dict[str, dict]:
    """The sparse HTTP-response `fields` shape (same `{value, confidence,
    bbox}` shape the legacy fields_to_dict already produces, plus `status`,
    so dms-core's DocumentMetadataServiceImpl#applyOcrFields can gate a
    `low_confidence` result instead of writing it as if trustworthy -- a
    `not_found` result is still omitted entirely, same as a non-matching
    regex simply not appearing today. A multi_select field's `value` (a
    real Python list) is JSON-encoded to a string here, since this dict's
    `value` must stay a plain scalar for the existing Java-side
    `Map<String,Object>`/`instanceof String` check to keep working -- the
    Java side must `ObjectMapper`-decode that JSON string back into a list
    for a multi_select field specifically. `match_basis` rides along for any
    caller reading the response directly; dms-core's own OCR-status view
    reads it from document_ocr_field_results instead (where not_found rows,
    omitted here, carry it too).
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
            "status": result.status,
            "match_basis": result.match_basis,
        }
    return output
