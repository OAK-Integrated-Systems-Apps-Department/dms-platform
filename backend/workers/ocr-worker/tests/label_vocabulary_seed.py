"""The single test-side copy of dms-core's V41 label-hint seed
(backend/dms-core/src/main/resources/db/migration/
V41__seed_ocr_label_aliases_from_legacy_vocabulary.sql) plus V30's field
catalogue, and a helper that turns them into the LabelVocabulary a real
seeded tenant's document gets.

This is ocr-worker's old hardcoded `_LABEL_ALIASES` dict verbatim (plus
"number" appended last to purchase_order_number, which replaced that field's
own hardcoded bare-"Number" branch -- see extraction.py's
build_label_vocabulary rule 4). Every extraction test that used to rely on
the hardcoded dict is fed this instead, so each existing assertion still
checks exactly the behavior it always did, and test_label_vocabulary.py
parses V41 itself and compares it against SEED_ROWS below -- the seed and
the tests can never silently drift apart.
"""
from __future__ import annotations

import sys
import uuid
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "src"))

from ocr_worker.extraction import LabelAliasRow, LabelFieldRow, LabelVocabulary, build_label_vocabulary  # noqa: E402

# field_key -> hints in priority order (display_order = (index + 1) * 10).
# Ordered most-specific-first on purpose, and that order still matters now
# that it's display_order rather than list position:
# - Invoice-shaped fields: the field's own key alone worked for the exact
#   label "Invoice Number:", but a real document just as often uses a
#   synonym ("Inv #", "Reference No" on a PO) -- the field-isolation/
#   synonym-matching bug report the old dict was added to fix.
# - amount: "total amount" must be tried before the bare "amount", because
#   "amount" alone matches inside a table's "Net Amount"/"Unit Price" column
#   header -- the first (and therefore wrong) occurrence on a typical
#   invoice, well before the real total. "total net item value excl tax"/
#   "net item value" and the bare "value" cover a real purchase order whose
#   ONLY total-amount label anywhere says "Value" (its header info-box:
#   "Number / Date / Currency / Value", the last beside a separate line
#   reading " : 3,611.20") and, on its final page, "Total net item value
#   excl tax" next to "3,611.20  USD" on the same row -- neither wording
#   contains "amount" at all. The bare "value" requires a marker/end-of-line
#   per _label_pattern (same protection "currency"/"vendor"/"supplier" rely
#   on), so it can't run away with a "face value" phrase in ordinary prose.
# - purchase_order_number: "number" last -- the only seeded hint the
#   standalone rule flags (it's the tail of "invoice number"/"reference
#   number"), so it's tried whole-line-only after every other hint misses.
SEED_LABEL_ALIASES: tuple[tuple[str, tuple[str, ...]], ...] = (
    ("author", ("author", "prepared by", "written by", "authored by")),
    ("department", ("department", "dept", "division", "business unit")),
    ("reference_number", ("reference number", "reference no", "ref no", "ref. no", "ref#", "ref #", "reference")),
    ("classification_level", ("classification level", "classification", "sensitivity")),
    ("retention_class", ("retention class", "retention schedule", "retention")),
    ("effective_date", ("effective date", "effective from", "effective")),
    ("expiry_date", ("expiry date", "expiration date", "expires on", "valid until", "expires")),
    ("description", ("description", "summary")),
    ("tags", ("tags",)),
    ("keywords", ("keywords", "key words")),
    ("invoice_number", ("invoice number", "invoice no", "inv no", "inv#", "inv #", "invoice#")),
    ("invoice_date", ("invoice date",)),
    ("purchase_order_number", ("purchase order number", "po number", "po no", "po#", "order number", "number")),
    ("currency", ("currency",)),
    ("vendor_supplier_name", ("vendor / supplier", "vendor/supplier", "vendor", "supplier", "billed by", "seller")),
    ("cost_centre", ("cost centre", "cost center")),
    (
        "amount",
        (
            "total net item value excl tax",
            "net item value",
            "total amount",
            "total due",
            "grand total",
            "amount due",
            "net amount due",
            "amount",
            "value",
        ),
    ),
)

# (field_key, alias_text, display_order) -- exactly what V41's VALUES list holds.
SEED_ROWS: tuple[tuple[str, str, int], ...] = tuple(
    (field_key, alias, (index + 1) * 10) for field_key, aliases in SEED_LABEL_ALIASES for index, alias in enumerate(aliases)
)

# V30__seed_metadata_field_catalogue_and_document_types.sql's starter
# catalogue (field_key, label) -- the labels are the field-name fallback for
# the fields V41 gives no hints (e.g. review_due_date -> "Review Due Date").
V30_CATALOG_FIELDS: tuple[tuple[str, str], ...] = (
    ("classification_level", "Classification Level"),
    ("department", "Department"),
    ("tags", "Tags"),
    ("retention_class", "Retention Class"),
    ("description", "Description"),
    ("author", "Author"),
    ("keywords", "Keywords"),
    ("language", "Language"),
    ("reference_number", "Reference Number"),
    ("effective_date", "Effective Date"),
    ("expiry_date", "Expiry Date"),
    ("review_due_date", "Review Due Date"),
    ("invoice_number", "Invoice Number"),
    ("invoice_date", "Invoice Date"),
    ("amount", "Amount"),
    ("currency", "Currency"),
    ("vendor_supplier_name", "Vendor / Supplier Name"),
    ("purchase_order_number", "Purchase Order Number"),
    ("contract_party", "Contract Party"),
    ("contract_value", "Contract Value"),
    ("contract_start_date", "Contract Start Date"),
    ("contract_end_date", "Contract End Date"),
    ("employee_id", "Employee ID"),
    ("project_code", "Project Code"),
    ("cost_centre", "Cost Centre"),
)

_FIELD_ID_NAMESPACE = uuid.UUID("6f1d8a52-3c1e-4b8e-9a53-0d6b2f6c9e11")


def field_id_for(field_key: str) -> uuid.UUID:
    """Deterministic field_id for a catalogue field, so tests can attach
    extra (e.g. type-scoped) alias rows to a seeded field by key."""
    return uuid.uuid5(_FIELD_ID_NAMESPACE, field_key)


def seed_field_rows() -> list[LabelFieldRow]:
    return [LabelFieldRow(field_id_for(key), key, label) for key, label in V30_CATALOG_FIELDS]


def seed_alias_rows() -> list[LabelAliasRow]:
    """Every V41 row, as the global (document_type_id NULL) hints it inserts."""
    return [LabelAliasRow(field_id_for(key), None, alias, order) for key, alias, order in SEED_ROWS]


def seed_vocabulary(
    requested_keys=None,
    *,
    document_type_id=None,
    extra_field_rows=(),
    extra_alias_rows=(),
) -> LabelVocabulary:
    """The vocabulary a V30+V41-seeded tenant's document gets, optionally
    with extra catalogue fields/hint rows layered on (e.g. a custom field or
    type-scoped hints). `requested_keys` defaults to every catalogue key
    plus any extra field's key."""
    field_rows = [*seed_field_rows(), *extra_field_rows]
    if requested_keys is None:
        requested_keys = [row.field_key for row in field_rows]
    return build_label_vocabulary(
        document_type_id=document_type_id,
        field_rows=field_rows,
        alias_rows=[*seed_alias_rows(), *extra_alias_rows],
        requested_keys=requested_keys,
    )


# The plain seeded-tenant vocabulary most extraction tests use.
SEED_VOCABULARY: LabelVocabulary = seed_vocabulary()
