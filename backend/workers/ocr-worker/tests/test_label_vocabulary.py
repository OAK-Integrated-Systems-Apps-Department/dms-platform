from __future__ import annotations

import dataclasses
import os
import re
import sys
import uuid
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "src"))

from conftest import seed_label_aliases, seed_metadata_field  # noqa: E402
from label_vocabulary_seed import (  # noqa: E402
    SEED_LABEL_ALIASES,
    SEED_ROWS,
    SEED_VOCABULARY,
    field_id_for,
    seed_alias_rows,
    seed_field_rows,
    seed_vocabulary,
)
from ocr_worker.extraction import (  # noqa: E402
    BUILTIN_AMBIGUOUS_TAIL_LABELS,
    BUILTIN_STOP_LABELS,
    FieldLabelHints,
    FieldSpec,
    LabelAliasRow,
    LabelFieldRow,
    build_label_vocabulary,
    extract_requested_fields,
    field_for_zone_name,
    requested_fields_to_output_dict,
)
from ocr_worker.models import DocumentType  # noqa: E402
from ocr_worker.repository import OcrRepository  # noqa: E402
from test_extraction import _REAL_PO_LINES_PAGE_1  # noqa: E402

_PO_FLAT_TEXT = "Number Date Currency Value : 4400051211 : 03.03.2026 : USD :    3,611.20"


# ----------------------------------------------------------------------
# V41 <-> test seed parity: the migration that seeds every existing
# tenant's hints and the copy every extraction test runs against must be
# the same list, in the same order, or the tests stop proving anything
# about production behavior.
# ----------------------------------------------------------------------

_V41_NAME = "V41__seed_ocr_label_aliases_from_legacy_vocabulary.sql"


def _migrations_dir() -> tuple[Path | None, bool]:
    """(directory, explicitly_configured). Repo-relative by default
    (backend/dms-core/src/main/resources/db/migration/).
    DMS_CORE_MIGRATIONS_DIR overrides it for a container that only mounts
    ocr-worker itself (e.g. `-v <that dir>:/dms-core-migrations:ro -e
    DMS_CORE_MIGRATIONS_DIR=/dms-core-migrations`). The directory is None
    when it can't exist -- ocr-worker mounted alone at /app, where there is
    no backend/ directory above it at all."""
    override = os.environ.get("DMS_CORE_MIGRATIONS_DIR")
    if override:
        return Path(override), True
    parents = Path(__file__).resolve().parents
    if len(parents) < 4:
        return None, False
    return parents[3] / "dms-core" / "src" / "main" / "resources" / "db" / "migration", False


def _parse_v41(sql: str) -> tuple[list[str], list[str], list[tuple[str, str, int]]]:
    """(INSERT column list, VALUES alias column list, VALUES rows). Strict
    on purpose: V41's own header promises one ('field_key', 'alias',
    display_order) tuple per line with no comments inside the list, and any
    line that doesn't parse fails the test rather than being skipped."""
    body = "\n".join(line for line in sql.splitlines() if not line.lstrip().startswith("--"))
    insert = re.search(r"INSERT\s+INTO\s+metadata_field_label_aliases\s*\(([^)]*)\)", body, re.IGNORECASE)
    assert insert, "V41 has no INSERT INTO metadata_field_label_aliases (...)"
    values = re.search(r"\(\s*VALUES\s*(.*?)\)\s*AS\s+v\s*\(([^)]*)\)", body, re.IGNORECASE | re.DOTALL)
    assert values, "V41 has no (VALUES ...) AS v(...) list"
    rows: list[tuple[str, str, int]] = []
    for raw in values.group(1).splitlines():
        line = raw.strip().rstrip(",").strip()
        if not line:
            continue
        match = re.fullmatch(r"\(\s*'((?:[^']|'')*)'\s*,\s*'((?:[^']|'')*)'\s*,\s*(\d+)\s*\)", line)
        assert match, f"unparseable VALUES line in V41: {raw!r}"
        rows.append((match.group(1).replace("''", "'"), match.group(2).replace("''", "'"), int(match.group(3))))
    columns = [c.strip() for c in insert.group(1).split(",")]
    alias_columns = [c.strip() for c in values.group(2).split(",")]
    return columns, alias_columns, rows


def test_v41_seed_matches_the_test_seed_exactly():
    directory, configured = _migrations_dir()
    # Skip ONLY when dms-core's migrations genuinely aren't part of this
    # checkout (ocr-worker built/tested on its own, e.g. its Docker build
    # context) and nobody pointed at them -- there's nothing to compare
    # against. A monorepo checkout whose migrations directory exists but has
    # no V41, or an explicit DMS_CORE_MIGRATIONS_DIR without it, is drift and
    # fails loudly.
    if not configured and (directory is None or not directory.is_dir()):
        pytest.skip(
            "dms-core's migrations aren't reachable from this checkout (ocr-worker tested on its own); "
            "set DMS_CORE_MIGRATIONS_DIR to run the V41 seed-parity check"
        )
    path = directory / _V41_NAME
    assert path.is_file(), (
        f"{path} not found -- the seed-parity check needs dms-core's V41 migration "
        "(mount it and set DMS_CORE_MIGRATIONS_DIR when running outside the repo checkout)"
    )
    columns, alias_columns, rows = _parse_v41(path.read_text(encoding="utf-8"))

    assert alias_columns == ["field_key", "alias_text", "display_order"]
    # Catalog-level rows only: V41 never sets document_type_id, so every
    # seeded hint is global -- exactly what seed_alias_rows() models.
    assert "document_type_id" not in columns
    assert rows == list(SEED_ROWS)
    assert len(rows) == 65


# ----------------------------------------------------------------------
# build_label_vocabulary -- the pure resolution rules (spec rules 1-4).
# ----------------------------------------------------------------------


def test_rule1_orders_by_display_order_type_scoped_first_and_dedupes_case_insensitively():
    fields = [LabelFieldRow("f-po", "po_number", "Purchase Order Number")]
    aliases = [
        # deliberately shuffled input -- display_order, not row order, decides
        LabelAliasRow("f-po", None, "order number", 20),
        LabelAliasRow("f-po", "type-po", "Customer PO", 30),
        LabelAliasRow("f-po", None, "PO Number", 10),
        LabelAliasRow("f-po", "type-po", "PO No", 10),
        LabelAliasRow("f-po", "type-po", "po number", 20),  # repeats a global hint: type-scoped position wins
    ]
    vocab = build_label_vocabulary(document_type_id="type-po", field_rows=fields, alias_rows=aliases, requested_keys=["po_number"])

    hints = vocab.fields["po_number"]
    assert hints.primary == ("PO No", "po number", "Customer PO", "order number")
    assert hints.basis == "label_hint"


def test_rule1_ignores_hints_scoped_to_another_document_type_entirely():
    fields = [LabelFieldRow("f-po", "po_number", "Purchase Order Number"), LabelFieldRow("f-inv", "invoice_number", "Invoice Number")]
    aliases = [
        LabelAliasRow("f-po", "type-po", "Number", 10),
        LabelAliasRow("f-inv", "type-po", "Reference Number", 10),
        LabelAliasRow("f-po", None, "PO Number", 10),
    ]
    vocab = build_label_vocabulary(
        document_type_id="type-invoice", field_rows=fields, alias_rows=aliases, requested_keys=["po_number", "invoice_number"]
    )

    assert vocab.fields["po_number"].all_hints == ("PO Number",)
    assert "number" not in vocab.known_labels and "reference number" not in vocab.known_labels
    # invoice_number's only hint was for the other type -> field-name fallback
    assert vocab.fields["invoice_number"].all_hints == ("Invoice Number",)
    assert vocab.fields["invoice_number"].basis == "field_name"


def test_rule2_field_with_no_hints_falls_back_to_its_catalog_label_with_field_name_basis():
    fields = [LabelFieldRow("f-1", "po_date", "PO Date"), LabelFieldRow("f-2", "blank_label", "   ")]
    vocab = build_label_vocabulary(
        document_type_id="t", field_rows=fields, alias_rows=[], requested_keys=["po_date", "blank_label", "not_in_catalog"]
    )

    assert vocab.fields["po_date"] == FieldLabelHints("po_date", primary=("PO Date",), standalone=(), basis="field_name")
    assert vocab.fields["blank_label"].all_hints == ("blank label",)
    assert vocab.fields["not_in_catalog"].all_hints == ("not in catalog",)
    assert vocab.fields["not_in_catalog"].basis == "field_name"


def test_rule3_known_labels_are_all_global_hints_plus_this_types_hints_plus_builtins_only():
    fields = [
        LabelFieldRow("f-inv", "invoice_number", "Invoice Number"),
        LabelFieldRow("f-po", "po_number", "PO Number"),
        LabelFieldRow("f-other", "unrequested", "Unrequested Field"),
        LabelFieldRow("f-bare", "bare_field", "Bare Label"),
    ]
    aliases = [
        LabelAliasRow("f-inv", None, "Invoice No", 10),
        LabelAliasRow("f-inv", None, "invoice no", 20),  # case-insensitive duplicate
        LabelAliasRow("f-other", None, "Ledger Code", 10),  # a field nobody requested is still a stop label
        LabelAliasRow("f-po", "type-po", "Order Ref", 10),  # this document type's hint
        LabelAliasRow("f-po", "type-inv", "Customer Order", 10),  # another type's hint -- never a stop label here
    ]
    vocab = build_label_vocabulary(
        document_type_id="type-po",
        field_rows=fields,
        alias_rows=aliases,
        requested_keys=["invoice_number", "po_number", "bare_field"],
    )

    # lowercased, de-duplicated, longest first, ties alphabetical; the
    # field-name fallback "Bare Label" is NOT a stop label.
    assert vocab.known_labels == ("ledger code", "invoice no", "order ref", "bill to", "ship to", "sold to")


def test_rule3_seed_known_labels_are_exactly_the_old_stop_set():
    """The old module-level _ALL_KNOWN_LABELS was every dict alias plus the
    three extra stop labels. The one hint V41 adds on top of the old dict
    ("number") is standalone for purchase_order_number, and a standalone
    hint is never a stop label -- so the seeded stop set is exactly the old
    one, and no other field's value is clipped at a "Phone Number:"."""
    old_dict_aliases = {
        alias for key, aliases in SEED_LABEL_ALIASES for alias in aliases if not (key == "purchase_order_number" and alias == "number")
    }
    assert set(SEED_VOCABULARY.known_labels) == old_dict_aliases | set(BUILTIN_STOP_LABELS)
    assert "number" not in SEED_VOCABULARY.known_labels
    lengths = [len(label) for label in SEED_VOCABULARY.known_labels]
    assert lengths == sorted(lengths, reverse=True)


def test_rule3_a_standalone_hint_is_a_stop_label_only_if_another_field_has_it_as_a_primary_hint():
    fields = [LabelFieldRow("f-sup", "vendor", "Vendor"), LabelFieldRow("f-cust", "main_supplier", "Main Supplier")]
    aliases = [
        LabelAliasRow("f-sup", None, "vendor / supplier", 10),
        LabelAliasRow("f-sup", None, "supplier", 20),  # primary: the tail of its OWN multi-word hint only
        LabelAliasRow("f-cust", None, "supplier", 10),  # standalone here: the tail of the other field's hint
    ]
    vocab = build_label_vocabulary(document_type_id=None, field_rows=fields, alias_rows=aliases, requested_keys=["main_supplier"])
    assert vocab.fields["main_supplier"].standalone == ("supplier",)
    assert "supplier" in vocab.known_labels  # still a real label -- the vendor field matches it normally

    without_vendors_own = [row for row in aliases if not (row.field_id == "f-sup" and row.alias_text == "supplier")]
    vocab = build_label_vocabulary(document_type_id=None, field_rows=fields, alias_rows=without_vendors_own, requested_keys=[])
    assert "supplier" not in vocab.known_labels
    assert "vendor / supplier" in vocab.known_labels


def test_rule4_over_the_seed_exactly_one_hint_is_standalone_and_every_other_field_is_unchanged():
    standalone = {(key, hint) for key, hints in SEED_VOCABULARY.fields.items() for hint in hints.standalone}
    assert standalone == {("purchase_order_number", "number")}

    for key, aliases in SEED_LABEL_ALIASES:
        expected_primary = tuple(a for a in aliases if not (key == "purchase_order_number" and a == "number"))
        assert SEED_VOCABULARY.fields[key].primary == expected_primary, key
        assert SEED_VOCABULARY.fields[key].basis == "label_hint"


def test_rule4_one_word_hint_is_primary_unless_a_different_field_ends_a_label_with_it():
    """A field's OWN multi-word hints ending in the same word never make its
    one-word hint ambiguous -- only a DIFFERENT field's multi-word hint does."""
    fields = [LabelFieldRow("f-gl", "gl_code", "GL Code"), LabelFieldRow("f-sub", "sub_ledger", "Sub Ledger")]
    own_only = [LabelAliasRow("f-gl", None, "general ledger", 10), LabelAliasRow("f-gl", None, "ledger", 20)]
    vocab = build_label_vocabulary(document_type_id=None, field_rows=fields, alias_rows=own_only, requested_keys=["gl_code"])
    assert vocab.fields["gl_code"].primary == ("general ledger", "ledger")
    assert vocab.fields["gl_code"].standalone == ()

    with_other = [*own_only, LabelAliasRow("f-sub", None, "sub ledger", 10)]
    vocab = build_label_vocabulary(document_type_id=None, field_rows=fields, alias_rows=with_other, requested_keys=["gl_code"])
    assert vocab.fields["gl_code"].standalone == ("ledger",)


def test_rule4_number_stays_standalone_even_when_no_other_field_has_a_number_hint():
    """The built-in reference labels keep the bare "Number" whole-line-only
    whatever the tenant's catalogue holds: a tenant with purchase_order_number
    but no invoice_number/reference_number field (hand-made catalogue,
    hard-deleted field, trimmed hints) must not start taking an "Invoice
    Number:"/"Account Number:" value as its PO number -- the old hardcoded
    branch never did."""
    assert {label.split()[-1] for label in BUILTIN_AMBIGUOUS_TAIL_LABELS} == {"number"}
    po_field = LabelFieldRow(field_id_for("purchase_order_number"), "purchase_order_number", "Purchase Order Number")
    po_only = [row for row in seed_alias_rows() if row.field_id == po_field.field_id]
    vocab = build_label_vocabulary(
        document_type_id=None, field_rows=[po_field], alias_rows=po_only, requested_keys=["purchase_order_number"]
    )
    hints = vocab.fields["purchase_order_number"]
    assert hints.standalone == ("number",)
    assert hints.primary == ("purchase order number", "po number", "po no", "po#", "order number")
    assert "number" not in vocab.known_labels

    spec = FieldSpec("purchase_order_number", "text")
    for stolen in ("Invoice Number: INV-2024-001", "Account Number: 0012-3344"):
        result = _one(spec, stolen, vocabulary=vocab)
        assert (result.value, result.status, result.match_basis) == (None, "not_found", "label_hint"), stolen
    assert _one(spec, "Number\n4400051211", vocabulary=vocab).value == "4400051211"


def test_rule4_own_multi_word_hint_never_makes_a_hint_standalone():
    hints = SEED_VOCABULARY.fields["vendor_supplier_name"]  # "supplier" is the tail of its own "vendor / supplier"
    assert "supplier" in hints.primary
    assert hints.standalone == ()


def test_rule4_builtin_stop_labels_count_as_reference_labels():
    fields = [LabelFieldRow("f-1", "recipient", "Recipient")]
    aliases = [LabelAliasRow("f-1", None, "to", 10), LabelAliasRow("f-1", None, "recipient", 20)]
    vocab = build_label_vocabulary(document_type_id=None, field_rows=fields, alias_rows=aliases, requested_keys=["recipient"])
    assert vocab.fields["recipient"].standalone == ("to",)  # tail of "ship to"/"bill to"/"sold to"
    assert vocab.fields["recipient"].primary == ("recipient",)


def test_rule4_applies_to_the_field_name_fallback_too():
    custom = LabelFieldRow("f-custom", "doc_number", "Number")
    vocab = seed_vocabulary(["doc_number"], extra_field_rows=[custom])
    assert vocab.fields["doc_number"] == FieldLabelHints("doc_number", primary=(), standalone=("Number",), basis="field_name")


def test_rule4_another_types_hints_never_make_a_hint_standalone():
    fields = [LabelFieldRow("f-a", "contract_ref", "Contract Ref"), LabelFieldRow("f-b", "project", "Project")]
    aliases = [LabelAliasRow("f-a", "type-contract", "contract reference", 10), LabelAliasRow("f-b", None, "reference", 10)]
    vocab = build_label_vocabulary(document_type_id="type-po", field_rows=fields, alias_rows=aliases, requested_keys=["project"])
    assert vocab.fields["project"].primary == ("reference",)

    vocab = build_label_vocabulary(document_type_id="type-contract", field_rows=fields, alias_rows=aliases, requested_keys=["project"])
    assert vocab.fields["project"].standalone == ("reference",)


def test_alias_rows_for_fields_outside_the_catalog_rows_are_ignored():
    fields = [LabelFieldRow("f-1", "author", "Author")]
    aliases = [LabelAliasRow("f-1", None, "author", 10), LabelAliasRow("f-deleted", None, "old label", 10)]
    vocab = build_label_vocabulary(document_type_id=None, field_rows=fields, alias_rows=aliases, requested_keys=["author"])
    assert "old label" not in vocab.known_labels


def test_vocabulary_is_immutable():
    with pytest.raises(TypeError):
        SEED_VOCABULARY.fields["author"] = FieldLabelHints("author", primary=("x",))  # type: ignore[index]
    with pytest.raises(dataclasses.FrozenInstanceError):
        SEED_VOCABULARY.fields["author"].primary = ("x",)  # type: ignore[misc]


def test_hints_for_an_unbuilt_key_uses_the_field_name_fallback():
    vocab = seed_vocabulary(["author"])
    assert vocab.hints_for("review_due_date").all_hints == ("review due date",)
    assert vocab.hints_for("review_due_date", "Review Due Date").primary == ("Review Due Date",)
    assert vocab.hints_for("review_due_date").basis == "field_name"


# ----------------------------------------------------------------------
# match_basis -- every path, so the upload page can always say how a value
# was found (or what was searched for when it wasn't).
# ----------------------------------------------------------------------


def _one(spec: FieldSpec, text: str, vocabulary=SEED_VOCABULARY, **kwargs):
    return extract_requested_fields(specs=[spec], text=text, zone_fields={}, vocabulary=vocabulary, **kwargs)[0]


def test_match_basis_zone():
    zone_field = field_for_zone_name("author", "Jane From Zone", 0.99, "author_zone", bbox=(1, 2, 3, 4), page=1)
    results = extract_requested_fields(
        specs=[FieldSpec("author", "text")], text="Author: Jane Doe", zone_fields={"author": zone_field}, vocabulary=SEED_VOCABULARY
    )
    assert results[0].match_basis == "zone"
    assert requested_fields_to_output_dict(results)["author"]["match_basis"] == "zone"


def test_match_basis_zone_for_an_empty_zone_crop():
    zone_field = field_for_zone_name("author", "   ", 0.99, "author_zone")
    results = extract_requested_fields(
        specs=[FieldSpec("author", "text")], text="Author: Jane Doe", zone_fields={"author": zone_field}, vocabulary=SEED_VOCABULARY
    )
    assert results[0].status == "not_found"
    assert results[0].match_basis == "zone"  # the zone was the only thing tried


def test_match_basis_label_hint_for_text_dropdown_date_long_text_and_multi_select():
    assert _one(FieldSpec("author", "text"), "Prepared by: Jane Doe").match_basis == "label_hint"
    currency = _one(FieldSpec("currency", "dropdown", ["USD", "EUR"]), "Currency: USD")
    assert (currency.value, currency.match_basis) == ("USD", "label_hint")
    assert _one(FieldSpec("effective_date", "date"), "Effective Date: 2026-01-05").match_basis == "label_hint"
    assert _one(FieldSpec("description", "long_text"), "Summary: A short policy note.").match_basis == "label_hint"
    tags = _one(FieldSpec("tags", "multi_select"), "Tags: hr, policy")
    assert (tags.value, tags.match_basis) == (["hr", "policy"], "label_hint")


def test_match_basis_field_name_when_the_field_has_no_hints():
    custom = LabelFieldRow("f-po-date", "po_date", "PO Date")
    vocab = seed_vocabulary(["po_date"], extra_field_rows=[custom])
    result = _one(FieldSpec("po_date", "date"), "PO Date: 2026-01-05", vocabulary=vocab)
    assert (result.value, result.status, result.match_basis) == ("2026-01-05", "extracted", "field_name")


def test_match_basis_pattern_for_bare_date_fallback_and_reference_number_regex():
    date = _one(FieldSpec("effective_date", "date"), "Signed and agreed on 2026-01-05 by both parties.")
    assert (date.value, date.confidence, date.match_basis) == ("2026-01-05", 0.65, "pattern")

    ref = _one(FieldSpec("reference_number", "text"), "Reference Number: REF-2026-8841")
    assert (ref.value, ref.match_basis) == ("REF-2026-8841", "pattern")


def test_match_basis_pattern_when_the_label_is_present_but_its_value_holds_no_date():
    """The label matched (so confidence stays at the label-derived 0.9,
    unchanged), but the date itself came from the bare-pattern fallback --
    match_basis reports where the VALUE came from."""
    result = _one(FieldSpec("effective_date", "date"), "Effective Date: TBD\nCountersigned 2026-02-01")
    assert (result.value, result.confidence, result.match_basis) == ("2026-02-01", 0.9, "pattern")


def test_match_basis_generated_for_free_text_fallbacks_and_language():
    assert _one(FieldSpec("description", "long_text"), "some free-form text about a policy matter").match_basis == "generated"
    assert _one(FieldSpec("tags", "multi_select"), "policy hr governance rules").match_basis == "generated"
    text = "This agreement is entered into by and between the parties for the purpose of establishing terms."
    assert _one(FieldSpec("language", "dropdown"), text).match_basis == "generated"


def test_match_basis_not_found_records_what_was_searched():
    prose = "This warranty is provided without any representation as to value and is not transferable."
    configured = _one(FieldSpec("amount", "decimal"), prose)
    assert (configured.status, configured.match_basis) == ("not_found", "label_hint")

    custom = LabelFieldRow("f-po-date", "po_date", "PO Date")
    vocab = seed_vocabulary(["po_date"], extra_field_rows=[custom])
    unconfigured = _one(FieldSpec("po_date", "text"), "nothing relevant here", vocabulary=vocab)
    assert (unconfigured.status, unconfigured.match_basis) == ("not_found", "field_name")


def test_match_basis_not_found_for_language_never_claims_a_label_search():
    """language is V30-catalogued with no V41 hints, but it's never
    label-searched at all -- langdetect over the whole text is the only
    thing tried -- so its not_found must not say "searched for the field
    name" (field_name), and a "Language" hint an admin adds changes nothing."""
    assert SEED_VOCABULARY.fields["language"].basis == "field_name"
    result = _one(FieldSpec("language", "dropdown"), "hi")
    assert (result.status, result.match_basis) == ("not_found", "generated")

    with_hint = seed_vocabulary(["language"], extra_alias_rows=[LabelAliasRow(field_id_for("language"), None, "Language", 10)])
    result = _one(FieldSpec("language", "dropdown"), "hi", vocabulary=with_hint)
    assert (result.status, result.match_basis) == ("not_found", "generated")


def test_match_basis_not_found_for_a_dropdown_whose_label_value_is_not_an_option_is_what_was_searched():
    """The label matched but "AUD" isn't one of the caller's options: still
    not_found, recording what was searched (the configured hints) -- the
    matcher has no separate "label found, value unusable" outcome on any
    path (a label with nothing after it is the same)."""
    result = _one(FieldSpec("currency", "dropdown", ["USD", "EUR"]), "Currency: AUD")
    assert (result.value, result.status, result.match_basis) == (None, "not_found", "label_hint")


def test_output_dict_carries_match_basis_but_still_omits_not_found():
    results = extract_requested_fields(
        specs=[FieldSpec("author", "text"), FieldSpec("department", "text")],
        text="Author: Jane Doe",
        zone_fields={},
        vocabulary=SEED_VOCABULARY,
    )
    output = requested_fields_to_output_dict(results)
    assert output == {"author": {"value": "Jane Doe", "confidence": 0.8, "bbox": None, "status": "extracted", "match_basis": "label_hint"}}


def test_effective_expiry_validation_keeps_match_basis():
    results = extract_requested_fields(
        specs=[FieldSpec("effective_date", "date"), FieldSpec("expiry_date", "date")],
        text="Effective Date: 2026-06-01\nExpiry Date: 2026-01-01",
        zone_fields={},
        vocabulary=SEED_VOCABULARY,
    )
    assert [(r.status, r.match_basis) for r in results] == [("low_confidence", "label_hint"), ("low_confidence", "label_hint")]


# ----------------------------------------------------------------------
# Behaviour the move to the database must not change (regressions a
# differential run against the old hardcoded module found), and the one
# deliberate change it does make (one-word field-name fallbacks).
# ----------------------------------------------------------------------


@pytest.mark.parametrize(
    ("spec", "text", "expected"),
    [
        # the old stop set never held the bare "number"; a seeded tenant's must not either
        (FieldSpec("vendor_supplier_name", "text"), "Supplier: Acme Ltd Phone Number: 011-2345678", "Acme Ltd Phone Number: 011-2345678"),
        (FieldSpec("vendor_supplier_name", "text"), "Supplier: Acme Ltd VAT Number: GB123456", "Acme Ltd VAT Number: GB123456"),
        (FieldSpec("author", "text"), "Prepared by: J. Smith, Phone Number: 555-0100", "J. Smith, Phone Number: 555-0100"),
        (FieldSpec("reference_number", "text"), "Reference: Policy number: 12", "Policy number: 12"),
        (
            FieldSpec("description", "long_text"),
            "Summary: Number-plate recognition study for the car park.",
            "Number-plate recognition study for the car park.",
        ),
        (FieldSpec("tags", "multi_select"), "Tags: finance, number-crunching, audit", ["finance", "number-crunching", "audit"]),
        (FieldSpec("effective_date", "date"), "Signed 2025-12-31\nEffective Date: Number-3 amendment, 2026-02-01", "2026-02-01"),
    ],
)
def test_a_bare_number_inside_a_value_never_clips_it(spec, text, expected):
    result = _one(spec, text)
    assert (result.value, result.status, result.match_basis) == (expected, "extracted", "label_hint")


def test_a_bare_number_span_is_not_mistaken_for_another_fields_label_spatially():
    lines = {1: [("Author:", (0, 0, 50, 10)), ("Number: 7", (60, 0, 120, 10))]}
    result = _one(FieldSpec("author", "text"), "x", lines_by_page=lines)
    assert (result.value, result.page) == ("Number: 7", 1)


@pytest.mark.parametrize(
    "text",
    [
        "Number\n4400051211 PO Number: 77\nPurchase Order Number",  # highest-priority primary label present, value-less
        "Number: 4400051211 PO Number:",
    ],
)
def test_a_value_found_by_the_standalone_tier_is_still_clipped_at_the_fields_own_primary_label(text):
    """Each tier excludes only its OWN hints from the clip, exactly as the
    old alias-list and bare-["number"] calls did."""
    result = _one(FieldSpec("purchase_order_number", "text"), text)
    assert (result.value, result.match_basis) == ("4400051211", "label_hint")


def test_one_word_field_name_fallback_that_ends_another_label_matches_only_as_a_whole_line():
    """Pinned on purpose (rule 4 applies to field-name fallbacks): a custom
    field labelled just "Supplier" no longer takes the value of the seeded
    vendor field's "Vendor / Supplier:" label (the old key search matched it
    on a word boundary) -- only a line that IS its label. Its not_found says
    field_name, i.e. "configure a hint"; a multi-word hint is primary."""
    custom = LabelFieldRow("f-supplier", "supplier", "Supplier")
    vocab = seed_vocabulary(["supplier", "vendor_supplier_name"], extra_field_rows=[custom])
    assert vocab.fields["supplier"] == FieldLabelHints("supplier", primary=(), standalone=("Supplier",), basis="field_name")

    stolen = extract_requested_fields(
        specs=[FieldSpec("supplier", "text"), FieldSpec("vendor_supplier_name", "text")],
        text="Vendor / Supplier: R-PAC Printcare",
        zone_fields={},
        vocabulary=vocab,
    )
    assert [(r.value, r.match_basis) for r in stolen] == [(None, "field_name"), ("R-PAC Printcare", "label_hint")]

    own_line = _one(FieldSpec("supplier", "text"), "Supplier: Acme", vocabulary=vocab)
    assert (own_line.value, own_line.match_basis) == ("Acme", "field_name")

    hinted = seed_vocabulary(["supplier"], extra_field_rows=[custom], extra_alias_rows=[LabelAliasRow("f-supplier", None, "main supplier", 10)])
    assert _one(FieldSpec("supplier", "text"), "Main Supplier: Acme", vocabulary=hinted).value == "Acme"


# ----------------------------------------------------------------------
# Type scoping and the purchase-order acceptance case.
# ----------------------------------------------------------------------


def test_type_scoped_hint_is_tried_before_the_fields_global_hints():
    type_scoped = [LabelAliasRow(field_id_for("amount"), "type-invoice", "amount payable", 10)]
    text = "Total Amount: 200.00\nAmount Payable: 150.00"

    global_only = _one(FieldSpec("amount", "decimal"), text, vocabulary=seed_vocabulary(["amount"], document_type_id="type-invoice"))
    assert global_only.value == "200.00"

    scoped = seed_vocabulary(["amount"], document_type_id="type-invoice", extra_alias_rows=type_scoped)
    assert scoped.fields["amount"].primary[0] == "amount payable"
    assert _one(FieldSpec("amount", "decimal"), text, vocabulary=scoped).value == "150.00"

    other_type = seed_vocabulary(["amount"], document_type_id="type-contract", extra_alias_rows=type_scoped)
    assert _one(FieldSpec("amount", "decimal"), text, vocabulary=other_type).value == "200.00"


_PO_TYPE = "type-purchase-order"
_INVOICE_TYPE = "type-invoice"
_PO_NUMBER_FIELD = LabelFieldRow("f-custom-po", "po_number", "Purchase Order Number")
_PO_NUMBER_TYPE_HINTS = [
    LabelAliasRow("f-custom-po", _PO_TYPE, hint, (index + 1) * 10)
    for index, hint in enumerate(["Purchase Order Number", "PO Number", "Number"])
]


def test_custom_po_field_with_type_scoped_hints_extracts_the_real_po_number():
    """The case this whole feature exists for: a tenant-defined field (not
    one of the old dict's keys) gets extracted with no code change, purely
    from hints its admin configured for the Purchase Order document type --
    including the bare "Number" header cell of the real PO fixture, which
    lands in the standalone tier because the seeded "invoice number" hint
    ends with it."""
    vocab = seed_vocabulary(
        ["po_number"], document_type_id=_PO_TYPE, extra_field_rows=[_PO_NUMBER_FIELD], extra_alias_rows=_PO_NUMBER_TYPE_HINTS
    )
    assert vocab.fields["po_number"].standalone == ("Number",)

    result = _one(FieldSpec("po_number", "text"), _PO_FLAT_TEXT, vocabulary=vocab, lines_by_page={1: _REAL_PO_LINES_PAGE_1})
    assert (result.value, result.status, result.match_basis) == ("4400051211", "extracted", "label_hint")


def test_custom_po_field_without_hints_is_not_found_and_says_it_searched_its_field_name():
    vocab = seed_vocabulary(["po_number"], document_type_id=_PO_TYPE, extra_field_rows=[_PO_NUMBER_FIELD])
    result = _one(FieldSpec("po_number", "text"), _PO_FLAT_TEXT, vocabulary=vocab, lines_by_page={1: _REAL_PO_LINES_PAGE_1})
    assert (result.value, result.status, result.match_basis) == (None, "not_found", "field_name")


def test_custom_po_fields_type_scoped_number_hint_does_not_apply_to_another_document_type():
    vocab = seed_vocabulary(
        ["po_number"], document_type_id=_INVOICE_TYPE, extra_field_rows=[_PO_NUMBER_FIELD], extra_alias_rows=_PO_NUMBER_TYPE_HINTS
    )
    assert "number" not in {h.lower() for h in vocab.fields["po_number"].all_hints}

    result = _one(FieldSpec("po_number", "text"), _PO_FLAT_TEXT, vocabulary=vocab, lines_by_page={1: _REAL_PO_LINES_PAGE_1})
    assert (result.status, result.match_basis) == ("not_found", "field_name")


def test_seeded_purchase_order_number_needs_the_number_hint_to_resolve_the_real_po():
    """Pins where test_extraction.py's bare-"Number" PO result now comes
    from: the vocabulary, not code. Drop the seeded "number" hint and the
    same document comes back not_found."""
    without_number = [
        row for row in seed_alias_rows() if not (row.field_id == field_id_for("purchase_order_number") and row.alias_text == "number")
    ]
    vocab = build_label_vocabulary(
        document_type_id=None, field_rows=seed_field_rows(), alias_rows=without_number, requested_keys=["purchase_order_number"]
    )
    result = _one(FieldSpec("purchase_order_number", "text"), _PO_FLAT_TEXT, vocabulary=vocab, lines_by_page={1: _REAL_PO_LINES_PAGE_1})
    assert result.status == "not_found"

    result = _one(FieldSpec("purchase_order_number", "text"), _PO_FLAT_TEXT, lines_by_page={1: _REAL_PO_LINES_PAGE_1})
    assert (result.value, result.match_basis) == ("4400051211", "label_hint")


# ----------------------------------------------------------------------
# OcrRepository.get_label_vocabulary against a real (SQLite) engine.
# ----------------------------------------------------------------------


@pytest.fixture()
def seeded_catalog(session_factory):
    tenant_id, other_tenant_id = uuid.uuid4(), uuid.uuid4()
    po_type, invoice_type, other_tenant_type = uuid.uuid4(), uuid.uuid4(), uuid.uuid4()
    with session_factory() as session:
        session.add(DocumentType(document_type_id=po_type, tenant_id=tenant_id, type_name="Purchase Order"))
        session.add(DocumentType(document_type_id=invoice_type, tenant_id=tenant_id, type_name="Invoice"))
        session.add(DocumentType(document_type_id=other_tenant_type, tenant_id=other_tenant_id, type_name="Purchase Order"))
        session.commit()

    def field(key, label, tenant=tenant_id, is_deleted=False):
        return seed_metadata_field(session_factory, tenant_id=tenant, field_key=key, label=label, is_deleted=is_deleted)

    invoice_number = field("invoice_number", "Invoice Number")
    seed_label_aliases(session_factory, tenant_id=tenant_id, field_id=invoice_number, aliases=["invoice number", "invoice no"])

    po_number = field("po_number", "Purchase Order Number")
    seed_label_aliases(session_factory, tenant_id=tenant_id, field_id=po_number, aliases=["po number"])
    seed_label_aliases(
        session_factory, tenant_id=tenant_id, field_id=po_number, aliases=["Purchase Order Number", "Number"], document_type_id=po_type
    )
    seed_label_aliases(session_factory, tenant_id=tenant_id, field_id=po_number, aliases=["Customer PO"], document_type_id=invoice_type)

    field("po_date", "PO Date")  # no hints at all

    deleted = field("legacy_ref", "Legacy Ref", is_deleted=True)
    seed_label_aliases(session_factory, tenant_id=tenant_id, field_id=deleted, aliases=["legacy reference"])

    # Another tenant with the SAME field_key: none of its rows may leak in.
    foreign = field("po_number", "Foreign PO", tenant=other_tenant_id)
    seed_label_aliases(session_factory, tenant_id=other_tenant_id, field_id=foreign, aliases=["foreign order ref"])
    seed_label_aliases(
        session_factory, tenant_id=other_tenant_id, field_id=foreign, aliases=["tenant b number"], document_type_id=other_tenant_type
    )

    return {"tenant_id": tenant_id, "po_type": po_type, "invoice_type": invoice_type}


def test_repository_builds_the_vocabulary_for_one_tenant_and_document_type(session_factory, seeded_catalog):
    with session_factory() as session:
        vocab = OcrRepository(session).get_label_vocabulary(
            tenant_id=seeded_catalog["tenant_id"],
            document_type_id=seeded_catalog["po_type"],
            field_keys=["po_number", "po_date", "invoice_number"],
        )

    po = vocab.fields["po_number"]
    assert po.primary == ("Purchase Order Number", "po number")  # type-scoped first, then global
    assert po.standalone == ("Number",)  # tail of this tenant's "invoice number"
    assert po.basis == "label_hint"
    assert vocab.fields["po_date"] == FieldLabelHints("po_date", primary=("PO Date",), standalone=(), basis="field_name")
    assert vocab.fields["invoice_number"].primary == ("invoice number", "invoice no")

    # "Number" is standalone for po_number, so it is not a stop label
    assert set(vocab.known_labels) == {"purchase order number", "invoice number", "invoice no", "po number", *BUILTIN_STOP_LABELS}


def test_repository_scopes_hints_to_the_requested_document_type(session_factory, seeded_catalog):
    with session_factory() as session:
        vocab = OcrRepository(session).get_label_vocabulary(
            tenant_id=seeded_catalog["tenant_id"], document_type_id=seeded_catalog["invoice_type"], field_keys=["po_number"]
        )

    assert vocab.fields["po_number"].all_hints == ("Customer PO", "po number")
    assert "number" not in vocab.known_labels and "purchase order number" not in vocab.known_labels
    assert "customer po" in vocab.known_labels


def test_repository_never_leaks_another_tenants_or_a_deleted_fields_hints(session_factory, seeded_catalog):
    with session_factory() as session:
        vocab = OcrRepository(session).get_label_vocabulary(
            tenant_id=seeded_catalog["tenant_id"], document_type_id=seeded_catalog["po_type"], field_keys=["po_number", "legacy_ref"]
        )

    assert not {"foreign order ref", "tenant b number", "legacy reference"} & set(vocab.known_labels)
    assert "Foreign PO" not in vocab.fields["po_number"].all_hints
    # the deleted field isn't in the catalog any more -> plain key fallback
    assert vocab.fields["legacy_ref"].all_hints == ("legacy ref",)
    assert vocab.fields["legacy_ref"].basis == "field_name"


def test_repository_for_a_tenant_with_no_catalog_falls_back_to_field_keys(session_factory):
    with session_factory() as session:
        vocab = OcrRepository(session).get_label_vocabulary(tenant_id=uuid.uuid4(), document_type_id=uuid.uuid4(), field_keys=["author"])
    assert vocab.fields["author"] == FieldLabelHints("author", primary=("author",), standalone=(), basis="field_name")
    assert vocab.known_labels == ("bill to", "ship to", "sold to")
