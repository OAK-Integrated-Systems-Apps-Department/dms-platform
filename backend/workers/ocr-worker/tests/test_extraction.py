from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "src"))

from decimal import Decimal  # noqa: E402

from ocr_worker import extraction  # noqa: E402
from ocr_worker.extraction import (  # noqa: E402
    FieldSpec,
    extract_requested_fields,
    extract_structured_fields,
    field_for_zone_name,
    fields_to_dict,
    requested_fields_to_output_dict,
)


def test_extracts_invoice_number_from_free_text():
    text = "Acme Supplies\nInvoice No: INV-20394\nDate: 2026-03-05\nTotal Due: $1,204.50"
    fields = extract_structured_fields(text, base_confidence=0.97)
    by_field = {f.field: f for f in fields}

    assert by_field["invoice_number"].value == "INV-20394"
    assert by_field["invoice_date"].value == "2026-03-05"
    assert by_field["total_amount"].value == "1,204.50"


def test_extraction_confidence_never_exceeds_ocr_confidence():
    text = "Invoice Number: ABC-1234"
    fields = extract_structured_fields(text, base_confidence=0.6)
    assert all(f.confidence <= 0.6 for f in fields)


def test_no_fields_extracted_from_empty_text():
    assert extract_structured_fields("") == []
    assert extract_structured_fields("   ") == []


def test_no_fields_extracted_when_nothing_matches():
    fields = extract_structured_fields("just some unrelated sentence with no structure")
    assert fields == []


def test_field_for_zone_name_uses_the_templates_field_mapping_directly():
    field = field_for_zone_name("invoice_number", "  INV-99  ", 0.88, "invoice_number_zone", bbox=(10, 20, 30, 40))
    assert field.field == "invoice_number"
    assert field.value == "INV-99"
    assert field.confidence == 0.88
    assert field.source == "zone:invoice_number_zone"
    assert field.bbox == (10, 20, 30, 40)


def test_fields_to_dict_matches_output_contract_shape():
    """Schema v2 adds `bbox_norm` additively (section 3.11).

    It is null here because this field has no page number -- normalizing
    needs to know which page's geometry to divide by, and a zone field
    built without one cannot say. `test_fields_to_dict_normalizes_when_
    page_geometry_is_available` covers the populated case.
    """
    field = field_for_zone_name("invoice_number", "INV-99", 0.88, "invoice_number_zone", bbox=(10, 20, 30, 40))
    result = fields_to_dict([field])
    assert result == {
        "invoice_number": {
            "value": "INV-99",
            "confidence": 0.88,
            "bbox": [10, 20, 30, 40],
            "bbox_norm": None,
        }
    }


def test_fields_to_dict_normalizes_when_page_geometry_is_available():
    from ocr_worker.layout import PageGeometry

    geometry = PageGeometry(
        page_no=0, width_pt=500.0, height_pt=1000.0, render_scale=2.0,
        width_px=1000, height_px=2000,
    )
    field = field_for_zone_name(
        "invoice_number", "INV-99", 0.88, "z", bbox=(100, 200, 300, 400), page=1
    )
    result = fields_to_dict([field], {1: geometry})
    assert result["invoice_number"]["bbox"] == [100, 200, 300, 400]
    assert result["invoice_number"]["bbox_norm"] == [0.1, 0.1, 0.3, 0.2]


def test_fields_to_dict_leaves_bbox_norm_null_for_a_page_with_no_geometry():
    """A .docx reports no geometry; its bboxes are (0,0,0,0) placeholders.

    Emitting a normalized zero box would be drawn by a review UI as a
    real highlight in the page corner, so null is the honest answer.
    """
    field = field_for_zone_name("invoice_number", "INV-99", 0.88, "z", bbox=(0, 0, 0, 0), page=1)
    assert fields_to_dict([field], {})["invoice_number"]["bbox_norm"] is None


def test_fields_to_dict_uses_null_bbox_for_regex_derived_fields():
    fields = extract_structured_fields("Invoice Number: ABC-1234", base_confidence=0.9)
    result = fields_to_dict(fields)
    assert result["invoice_number"]["bbox"] is None


def test_po_number_regex_does_not_match_po_as_a_word_prefix():
    """Real bug found against a Clockify time-tracking export: 'Power BI
    progress review' was matching _PO_NUMBER_RE's bare 'po' alternative
    (no trailing word boundary), extracting id_number="wer" from the
    middle of "Power". A real PO reference must still match."""
    fields = extract_structured_fields("Power BI progress review", base_confidence=0.9)
    by_field = {f.field: f for f in fields}
    assert "id_number" not in by_field

    fields = extract_structured_fields("PO Number: PO-4471", base_confidence=0.9)
    by_field = {f.field: f for f in fields}
    assert by_field["id_number"].value == "PO-4471"


def test_invoice_number_regex_does_not_match_inv_as_a_word_prefix():
    fields = extract_structured_fields("Inventory count: 42 units", base_confidence=0.9)
    by_field = {f.field: f for f in fields}
    assert "invoice_number" not in by_field


def test_invoice_date_is_normalized_to_iso_regardless_of_printed_format():
    """Real bug: a real invoice's date rarely comes as ISO -- dms-core's
    metadata layer only accepts ISO dates for a `date` field (see
    DocumentMetadataServiceImpl#parseDate), so a raw '01/05/2026' or
    'May 1, 2026' silently failed to map onto the Invoice Date field.
    Ambiguous numeric dates are read day-first (see _DATE_FORMATS's own
    comment), so '01/05/2026' is day=01, month=05 -- 1 May, not 5 January."""
    fields = extract_structured_fields("Invoice No: INV-1\nDate: 01/05/2026\nTotal Due: $10.00")
    assert fields[[f.field for f in fields].index("invoice_date")].value == "2026-05-01"

    fields = extract_structured_fields("Invoice No: INV-1\nDate: May 1, 2026\nTotal Due: $10.00")
    assert fields[[f.field for f in fields].index("invoice_date")].value == "2026-05-01"

    fields = extract_structured_fields("Invoice No: INV-1\nDate: 2026-03-05\nTotal Due: $10.00")
    assert fields[[f.field for f in fields].index("invoice_date")].value == "2026-03-05"


# ----------------------------------------------------------------------
# Selectable OCR field extraction (extract_requested_fields) -- My
# Documents upload page's "choose which fields OCR should extract".
# ----------------------------------------------------------------------


def test_date_field_normalizes_multiple_printed_formats_to_iso():
    cases = [
        ("Effective Date: 01/05/2026", "2026-05-01"),  # day-first: day=01, month=05
        ("Effective Date: 05 January 2026", "2026-01-05"),
        ("Effective Date: 2026-01-05", "2026-01-05"),
        ("Effective Date: May 1, 2026", "2026-05-01"),
    ]
    for text, expected in cases:
        results = extract_requested_fields(specs=[FieldSpec("effective_date", "date")], text=text, zone_fields={})
        by_field = {r.field_key: r for r in results}
        assert by_field["effective_date"].value == expected, f"input {text!r}"
        assert by_field["effective_date"].status == "extracted"


def test_expiry_before_effective_flags_both_low_confidence_without_dropping_either():
    text = "Effective Date: 2026-06-01\nExpiry Date: 2026-01-01"
    results = extract_requested_fields(
        specs=[FieldSpec("effective_date", "date"), FieldSpec("expiry_date", "date")], text=text, zone_fields={}
    )
    by_field = {r.field_key: r for r in results}
    assert by_field["effective_date"].value == "2026-06-01"
    assert by_field["expiry_date"].value == "2026-01-01"
    assert by_field["effective_date"].status == "low_confidence"
    assert by_field["expiry_date"].status == "low_confidence"


def test_expiry_after_effective_stays_extracted():
    text = "Effective Date: 2026-01-01\nExpiry Date: 2026-06-01"
    results = extract_requested_fields(
        specs=[FieldSpec("effective_date", "date"), FieldSpec("expiry_date", "date")], text=text, zone_fields={}
    )
    by_field = {r.field_key: r for r in results}
    assert by_field["effective_date"].status == "extracted"
    assert by_field["expiry_date"].status == "extracted"


def test_language_detects_english_text():
    text = "This agreement is entered into by and between the parties for the purpose of establishing terms."
    results = extract_requested_fields(specs=[FieldSpec("language", "dropdown")], text=text, zone_fields={})
    assert results[0].value == "en"
    assert results[0].status in ("extracted", "low_confidence")


def test_language_detects_non_english_text():
    text = "Este es un acuerdo entre las partes con el fin de establecer los terminos y condiciones del contrato."
    results = extract_requested_fields(specs=[FieldSpec("language", "dropdown")], text=text, zone_fields={})
    assert results[0].value == "es"


def test_language_not_found_for_empty_or_too_short_text():
    results = extract_requested_fields(specs=[FieldSpec("language", "dropdown")], text="", zone_fields={})
    assert results[0].status == "not_found"
    assert results[0].value is None

    results = extract_requested_fields(specs=[FieldSpec("language", "dropdown")], text="hi", zone_fields={})
    assert results[0].status == "not_found"


def test_classification_level_matches_case_insensitive_exact_value():
    text = "Classification Level: CONFIDENTIAL\nDepartment: Legal"
    spec = FieldSpec("classification_level", "dropdown", options=["public", "internal", "confidential", "restricted"])
    results = extract_requested_fields(specs=[spec], text=text, zone_fields={})
    assert results[0].value == "confidential"
    assert results[0].status == "extracted"


def test_classification_level_matches_via_synonym():
    text = "Classification Level: Top Secret"
    spec = FieldSpec("classification_level", "dropdown", options=["public", "internal", "confidential", "restricted"])
    results = extract_requested_fields(specs=[spec], text=text, zone_fields={})
    assert results[0].value == "confidential"


def test_classification_level_never_invents_a_value_outside_options():
    text = "Classification Level: Top Priority Urgent"
    spec = FieldSpec("classification_level", "dropdown", options=["public", "internal", "confidential", "restricted"])
    results = extract_requested_fields(specs=[spec], text=text, zone_fields={})
    assert results[0].status == "not_found"
    assert results[0].value is None


def test_retention_class_case_insensitive_match():
    text = "Retention Class: SHORT_TERM"
    spec = FieldSpec("retention_class", "dropdown", options=["short_term", "medium_term", "long_term", "permanent"])
    results = extract_requested_fields(specs=[spec], text=text, zone_fields={})
    assert results[0].value == "short_term"
    assert results[0].status == "extracted"


def test_reference_number_regex_extraction():
    text = "Reference Number: REF-2026-8841\nOther text follows."
    results = extract_requested_fields(specs=[FieldSpec("reference_number", "text")], text=text, zone_fields={})
    assert results[0].value == "REF-2026-8841"
    assert results[0].status == "extracted"


def test_reference_number_falls_back_to_label_proximity_when_no_pattern_matches():
    text = "Reference Number: see attached schedule A"
    results = extract_requested_fields(specs=[FieldSpec("reference_number", "text")], text=text, zone_fields={})
    assert results[0].value == "see attached schedule A"
    assert results[0].status == "extracted"  # 0.85 confidence >= the 0.6 default field_confidence_threshold


def test_reference_number_prefers_the_labeled_value_over_an_earlier_unrelated_pattern_match():
    """Real bug found live: a policy document's actual reference
    ("DOCUMENT REF: ISMS-DOC-A08-28-1") sat in a table further down the
    page, but the pattern-rule regex ran first and unconditionally, so it
    grabbed an earlier, unrelated "ISO/IEC 27001" mention in the body text
    instead -- a lookalike match with no label backing it at all. The
    label match must always get first refusal.
    """
    text = "The ISO/IEC 27001 standard is referenced here.\nDocument Ref: ISMS-DOC-A08-28-1"
    results = extract_requested_fields(specs=[FieldSpec("reference_number", "text")], text=text, zone_fields={})
    assert results[0].value == "ISMS-DOC-A08-28-1"


def test_label_proximity_strips_markdown_table_pipes_from_a_docx_export():
    """Real bug found live: a policy .docx's export_to_text() (Docling's
    own docx-to-text conversion) renders a table row as markdown pipe
    syntax -- "| DOCUMENT REF              | ISMS-DOC-A08-28-1  |" -- and
    the label match's capture group (everything after "DOCUMENT REF" on
    that line) came back as "| ISMS-DOC-A08-28-1  |", the neighboring
    cell's "|" delimiters and padding included, since none of that is
    whitespace a plain .strip() would remove.
    """
    text = "Secure Coding Policy\n\n| DOCUMENT CLASSIFICATION   |                    |\n| DOCUMENT REF              | ISMS-DOC-A08-28-1  |\n| VERSION                   | 1                  |"
    results = extract_requested_fields(specs=[FieldSpec("reference_number", "text")], text=text, zone_fields={})
    assert results[0].value == "ISMS-DOC-A08-28-1"


def test_only_requested_fields_are_ever_returned():
    """The single hardest requirement: a document type with no zone
    template still runs the legacy invoice-regex fallback internally on
    the raw text, but extract_requested_fields must never surface a field
    that wasn't asked for, even when the fallback would have found one.
    """
    text = "Invoice No: INV-4471\nAuthor: Jane Doe\nTotal Due: $500.00"
    results = extract_requested_fields(specs=[FieldSpec("author", "text")], text=text, zone_fields={})
    assert [r.field_key for r in results] == ["author"]
    assert results[0].value == "Jane Doe"

    output = requested_fields_to_output_dict(results)
    assert set(output.keys()) == {"author"}


def test_empty_requested_fields_list_returns_nothing():
    results = extract_requested_fields(specs=[], text="Author: Jane Doe", zone_fields={})
    assert results == []


def test_zone_match_takes_priority_over_label_proximity():
    zone_field = field_for_zone_name("author", "Jane From Zone", 0.99, "author_zone", bbox=(1, 2, 3, 4), page=1)
    text = "Author: Jane Doe"  # would otherwise match via label proximity
    results = extract_requested_fields(
        specs=[FieldSpec("author", "text")], text=text, zone_fields={"author": zone_field}
    )
    assert results[0].value == "Jane From Zone"
    assert results[0].bbox == (1, 2, 3, 4)
    assert results[0].page == 1


def test_tags_free_text_fallback_generates_lowercase_deduplicated_low_confidence_list():
    text = "Policy Policy Compliance Compliance Governance Requirements Requirements Overview"
    results = extract_requested_fields(specs=[FieldSpec("tags", "multi_select")], text=text, zone_fields={})
    assert results[0].status == "low_confidence"
    assert isinstance(results[0].value, list)
    assert len(results[0].value) == len(set(results[0].value))  # deduplicated
    assert all(v == v.lower() for v in results[0].value)


def test_requested_fields_to_output_dict_json_encodes_multi_select_values():
    import json

    results = extract_requested_fields(specs=[FieldSpec("tags", "multi_select")], text="policy hr governance rules", zone_fields={})
    output = requested_fields_to_output_dict(results)
    assert isinstance(output["tags"]["value"], str)
    assert json.loads(output["tags"]["value"]) == results[0].value


def test_invoice_number_regex_does_not_match_a_following_label_word():
    """Real bug found against a hospital receipt PDF: 'INVOICE DATE\n06-22-2026'
    matched _INVOICE_NUMBER_RE's capture group against the label word 'DATE'
    itself (no digit required in the captured token), extracting
    invoice_number='DATE' -- a real, digit-bearing invoice number
    immediately after the keyword must still match."""
    fields = extract_structured_fields("INVOICE DATE\n06-22-2026 07:19 AM", base_confidence=0.9)
    by_field = {f.field: f for f in fields}
    assert "invoice_number" not in by_field

    fields = extract_structured_fields("Invoice No: INV-4471", base_confidence=0.9)
    by_field = {f.field: f for f in fields}
    assert by_field["invoice_number"].value == "INV-4471"


def test_invoice_number_does_not_swallow_every_field_crammed_onto_one_ocr_line():
    """Real bug found against a live invoice: its header ("Invoice Number:
    Invoice Date: PO Number: Currency: INV-2026-004821 09/15/2026
    4400051211 USD Vendor / Supplier: R-PAC Printcare Lanka (Pvt) Ltd ...
    Ship To / Department: Silueta (Pvt) Ltd ... Cost Centre: CC-1042")
    came back from Docling as ONE native-text block with no '\\n' between
    cells -- a "all labels, then all values" table layout, not "label:
    value" pairs. _label_proximity_value's old "(.*)$ " capture ran to
    the end of that line, so invoice_number's value became the entire
    rest of the document's header (every other field's label AND value).
    The real, correct value can't be recovered by this text-only
    approach (there's no line break to anchor "Invoice Number"'s own
    value to), but it must never contaminate other fields -- not_found is
    the honest, safe outcome, not a wrong 250-character string.
    """
    text = (
        "Invoice Number: Invoice Date: PO Number: Currency: INV-2026-004821 09/15/2026 "
        "4400051211 USD Vendor / Supplier: R-PAC Printcare Lanka (Pvt) Ltd 21, Sri Pushparama Mv, "
        "Pahala Kadawatha Ship To / Department: Silueta (Pvt) Ltd - Procurement Dept Cost Centre: CC-1042"
    )
    results = extract_requested_fields(specs=[FieldSpec("invoice_number", "text")], text=text, zone_fields={})
    assert results[0].status == "not_found"
    assert results[0].value is None


def test_vendor_supplier_name_clips_at_the_next_known_label_on_a_crammed_line():
    """Same real invoice as above, but for a field whose label IS
    immediately followed by its actual value on that crammed line
    (unlike invoice_number above) -- vendor_supplier_name must stop at
    "Department:"/"Ship To" rather than running into them.
    """
    text = (
        "Vendor / Supplier: R-PAC Printcare Lanka (Pvt) Ltd 21, Sri Pushparama Mv, Pahala Kadawatha "
        "Ship To / Department: Silueta (Pvt) Ltd - Procurement Dept Cost Centre: CC-1042"
    )
    results = extract_requested_fields(specs=[FieldSpec("vendor_supplier_name", "text")], text=text, zone_fields={})
    assert results[0].status == "extracted"
    assert "Department" not in results[0].value
    assert "Cost Centre" not in results[0].value
    assert results[0].value.startswith("R-PAC Printcare Lanka (Pvt) Ltd")


def test_amount_prefers_total_amount_label_over_the_net_amount_table_header():
    """Same real invoice: a line-items table's own "Net Amount" column
    header contains the word "amount" and appears BEFORE the real
    "Total Amount" label -- amount's generic fallback (bare "amount")
    used to match that header first and grab the next table cell
    ("TAG BITRCT00001_A_TRW253PW18", a line-item description) instead of
    the real total. "total amount" must be tried before the bare "amount".
    """
    text = (
        "Description\nQty\nUnit Price\nNet Amount\n"
        "TAG BITRCT00001_A_TRW253PW18\n2892\n75.00/1000\n216.91\n"
        "Subtotal\n545.65\nTax (0%)\n0.00\nTotal Amount\n545.65\n"
        "Payment terms: Net 30 days"
    )
    results = extract_requested_fields(specs=[FieldSpec("amount", "decimal")], text=text, zone_fields={})
    assert results[0].value == "545.65"


# Real per-line text+bbox from the same live invoice as above (dumped via
# PyMuPDF's own get_text("dict") against the actual PDF) -- this is what
# _spatial_label_value works from instead of the flattened, paragraph-
# merged `text` string: each label and its value are genuinely separate
# lines/spans with real positions, even though Docling's own paragraph
# grouping (and therefore the flattened text) merged several of these
# rows into one blob. Coordinates are the real ones, unscaled.
_REAL_INVOICE_LINES_PAGE_1: list[tuple[str, tuple[int, int, int, int]]] = [
    ("Invoice Number:", (371, 118, 442, 130)),
    ("Invoice Date:", (386, 135, 442, 147)),
    ("PO Number:", (389, 152, 442, 164)),
    ("Currency:", (399, 169, 442, 181)),
    ("INV-2026-004821", (448, 118, 519, 130)),
    ("09/15/2026", (448, 135, 493, 147)),
    ("4400051211", (448, 152, 498, 164)),
    ("USD", (448, 169, 467, 181)),
    ("Vendor / Supplier:", (57, 188, 143, 201)),
    ("R-PAC Printcare Lanka (Pvt) Ltd", (57, 205, 201, 218)),
    ("21, Sri Pushparama Mv, Pahala Kadawatha", (57, 219, 251, 233)),
    ("Ship To / Department:", (312, 188, 416, 201)),
    ("Silueta (Pvt) Ltd - Procurement Dept", (312, 205, 473, 218)),
    ("Cost Centre: CC-1042", (312, 219, 411, 233)),
    ("Net Amount", (496, 280, 547, 292)),
    ("Total Amount", (390, 400, 448, 412)),
    ("545.65", (519, 400, 547, 412)),
]


def test_spatial_matching_recovers_invoice_number_as_the_separate_span_to_its_right():
    """The exact document from the field-isolation bug report above, but
    given its real per-line positions instead of the flattened text --
    invoice_number is now recovered correctly (not just safely not_found),
    because "Invoice Number:" and "INV-2026-004821" are two separate
    spans on the same real visual row, immediately adjacent in x.
    """
    results = extract_requested_fields(
        specs=[FieldSpec("invoice_number", "text")],
        text="Invoice Number: Invoice Date: PO Number: Currency: INV-2026-004821 09/15/2026 4400051211 USD",
        zone_fields={},
        lines_by_page={1: _REAL_INVOICE_LINES_PAGE_1},
    )
    assert results[0].value == "INV-2026-004821"
    assert results[0].status == "extracted"
    assert results[0].page == 1
    assert results[0].bbox == (448, 118, 519, 130)


def test_spatial_matching_recovers_po_number_and_currency_and_invoice_date_from_the_same_row():
    specs = [FieldSpec("purchase_order_number", "text"), FieldSpec("invoice_date", "date"), FieldSpec("currency", "dropdown", ["USD", "EUR", "GBP", "LKR"])]
    results = extract_requested_fields(
        specs=specs,
        text="Invoice Number: Invoice Date: PO Number: Currency: INV-2026-004821 09/15/2026 4400051211 USD",
        zone_fields={},
        lines_by_page={1: _REAL_INVOICE_LINES_PAGE_1},
    )
    by_field = {r.field_key: r for r in results}
    assert by_field["purchase_order_number"].value == "4400051211"
    assert by_field["invoice_date"].value == "2026-09-15"
    assert by_field["currency"].value == "USD"


def test_spatial_matching_does_not_pick_a_sibling_columns_label_as_the_value():
    """"Vendor / Supplier:" and "Ship To / Department:" sit on the exact
    same visual row, side by side -- the nearest thing to the right of
    "Vendor / Supplier:" is literally the OTHER field's label, not a
    value. It must be recognized as a label (via _looks_like_a_label) and
    skipped, falling through to the real value stacked below instead.
    """
    results = extract_requested_fields(
        specs=[FieldSpec("vendor_supplier_name", "text")],
        text="Vendor / Supplier: R-PAC Printcare Lanka (Pvt) Ltd Ship To / Department: Silueta (Pvt) Ltd",
        zone_fields={},
        lines_by_page={1: _REAL_INVOICE_LINES_PAGE_1},
    )
    assert results[0].value == "R-PAC Printcare Lanka (Pvt) Ltd"
    assert "Department" not in results[0].value
    assert "Ship To" not in results[0].value


def test_spatial_matching_prefers_total_amount_row_over_the_net_amount_header():
    """"Net Amount" (a table column header, containing the substring
    "amount") sits ABOVE "Total Amount" in reading order -- _LABEL_ALIASES
    already tries "total amount" before the bare "amount" (see the
    text-only test above), and spatial matching must still land on the
    real total ("545.65", same row) rather than anything near "Net Amount".
    """
    results = extract_requested_fields(
        specs=[FieldSpec("amount", "decimal")],
        text="Net Amount\nTotal Amount\n545.65",
        zone_fields={},
        lines_by_page={1: _REAL_INVOICE_LINES_PAGE_1},
    )
    assert results[0].value == "545.65"
    assert results[0].bbox == (519, 400, 547, 412)


def test_spatial_matching_is_skipped_when_no_line_data_is_available():
    """Zone/rubber-band crops (and any page this module has no line-level
    data for) have no lines_by_page entry at all -- extraction must still
    fall back to the flattened-text heuristic exactly as before, not
    error out or silently return nothing.
    """
    results = extract_requested_fields(
        specs=[FieldSpec("author", "text")], text="Author: Jane Doe", zone_fields={}, lines_by_page=None
    )
    assert results[0].value == "Jane Doe"


# Real per-line text+bbox from a second live invoice (dumped via PyMuPDF's
# get_text("dict") against the actual PDF) -- this one renders each
# label's colon as its OWN separate text span, positioned between the
# label and the real value on the same row ("Invoice No" / " :" /
# "IN/2025/01448" as three separate spans, not two).
_REAL_INVOICE_2_LINES_PAGE_1: list[tuple[str, tuple[int, int, int, int]]] = [
    ("Date", (328, 134, 353, 147)),
    (" :", (426, 134, 433, 147)),
    ("15/12/2025", (498, 134, 557, 147)),
    ("Invoice No", (328, 149, 383, 162)),
    (" :", (426, 149, 433, 162)),
    ("IN/2025/01448", (482, 149, 557, 162)),
    ("Customer PO NO", (328, 165, 415, 178)),
    (" :", (426, 165, 433, 178)),
    ("15/12/2025", (498, 165, 557, 178)),
]


def test_spatial_matching_skips_a_lone_colon_rendered_as_its_own_span():
    """Real bug found live: "Invoice No" / " :" / "IN/2025/01448" are
    THREE separate same-row spans (this PDF renders the label's own colon
    as its own text run) -- the colon span is nearer to the label than
    the real value, and (before this fix) nothing stopped it from being
    picked as "the value" just because it isn't itself a known label.
    """
    results = extract_requested_fields(
        specs=[FieldSpec("invoice_number", "text"), FieldSpec("purchase_order_number", "text")],
        text="Date : 15/12/2025 Invoice No : IN/2025/01448 Customer PO NO : 15/12/2025",
        zone_fields={},
        lines_by_page={1: _REAL_INVOICE_2_LINES_PAGE_1},
    )
    by_field = {r.field_key: r for r in results}
    assert by_field["invoice_number"].value == "IN/2025/01448"
    assert by_field["purchase_order_number"].value == "15/12/2025"  # verbatim -- the source document itself repeats the date here


def test_bare_single_word_alias_does_not_match_inside_ordinary_prose():
    """Real bug found live against an actual purchase order: page 6's
    Terms and Conditions said "...as per the Supplier's proforma invoice,
    and shall not be deviated and/or amended in any manner what so
    ever..." -- vendor_supplier_name's bare "supplier" alias (no colon
    required) matched "Supplier" inside "Supplier's" and returned that
    entire clause as the vendor name. A bare single-word alias must now
    require a real label marker (":"/"-") right after it.
    """
    text = (
        "This purchase order is based on the technical specifications of the product as per the "
        "Supplier's proforma invoice, and shall not be deviated and/or amended in any manner what so ever, "
        "without the prior written approval."
    )
    results = extract_requested_fields(specs=[FieldSpec("vendor_supplier_name", "text")], text=text, zone_fields={})
    assert results[0].status == "not_found"
    assert results[0].value is None


def test_bare_single_word_alias_still_matches_a_real_colon_labeled_value():
    """The fix above must not break the ordinary case: a bare word alias
    genuinely used as a label, with a real colon, still works.
    """
    results = extract_requested_fields(
        specs=[FieldSpec("vendor_supplier_name", "text")], text="Supplier: Acme Corp", zone_fields={}
    )
    assert results[0].value == "Acme Corp"


# Real per-line text+bbox from a third live document (a genuine 6-page
# Purchase Order, dumped via PyMuPDF) -- its top-right field block puts
# each bare label ("Currency", "Number") alone on its own line, with the
# colon rendered as part of the SAME span as the value on the adjacent
# line (" : USD"), not attached to the label and not its own separate
# span either (contrast with the earlier "Invoice No" / " :" /
# "IN/2025/01448" three-span case).
_REAL_PO_LINES_PAGE_1: list[tuple[str, tuple[int, int, int, int]]] = [
    ("Number", (297, 53, 348, 73)),
    ("Date", (297, 82, 331, 102)),
    ("Currency", (297, 111, 365, 131)),
    ("Value", (297, 140, 340, 159)),
    (" : 4400051211", (365, 52, 475, 72)),
    (" : 03.03.2026", (365, 81, 475, 101)),
    (" : USD", (365, 110, 416, 130)),
    (" :    3,611.20 ", (365, 139, 492, 158)),
]


def test_bare_single_word_label_alone_on_its_own_line_still_matches():
    """The fix for the prose false-positive above must not break THIS
    real, legitimate case: "Currency" is genuinely a label, entirely
    alone on its own line (nothing after it at all, not even a colon --
    the colon lives on the adjacent value line instead). It must still be
    recognized as a label and resolve to its real value.
    """
    results = extract_requested_fields(
        specs=[FieldSpec("currency", "dropdown", ["USD", "EUR", "GBP", "LKR"])],
        text="Number Date Currency Value : 4400051211 : 03.03.2026 : USD :    3,611.20",
        zone_fields={},
        lines_by_page={1: _REAL_PO_LINES_PAGE_1},
    )
    assert results[0].value == "USD"


def test_spatial_matching_strips_a_leading_colon_bundled_into_the_value_span():
    """Same document shape, a plain text field with no enum matching to
    obscure the raw value: the value span itself is literally " : REF-99"
    (colon bundled into the SAME span as the value, unlike the earlier
    three-separate-spans "Invoice No" case) -- the leading colon must be
    stripped, not returned as part of the value.
    """
    results = extract_requested_fields(
        specs=[FieldSpec("reference_number", "text")],
        text="Reference : REF-99",
        zone_fields={},
        lines_by_page={1: [("Reference", (297, 53, 348, 73)), (" : REF-99", (365, 52, 475, 72))]},
    )
    assert results[0].value == "REF-99"


def test_bare_total_is_recognized_when_no_more_specific_amount_label_exists():
    """Real bug found live against an actual hospital channel receipt
    (PaddleOCR lines, real bboxes): its grand total is labeled just
    "TOTAL" -- no "Amount"/"Due"/"Grand" qualifier at all -- which none of
    the more specific amount aliases match, so it fell through to
    not_found even though the value is right there.
    """
    results = extract_requested_fields(
        specs=[FieldSpec("amount", "decimal")],
        text="TOTAL : 4900.00",
        zone_fields={},
        lines_by_page={1: [("TOTAL", (530, 1553, 657, 1592)), (": 4900.00", (894, 1556, 1099, 1600))]},
    )
    assert results[0].value == "4900.00"


def test_bare_total_does_not_match_inside_subtotal():
    """"\\btotal\\b" must not match the "total" inside "Subtotal" -- there
    is no word boundary between "Sub" and "total" in that one unbroken
    token, so a document whose only "total"-shaped label is "Subtotal"
    (not the real grand total) must still come back not_found rather than
    grabbing the wrong figure.
    """
    results = extract_requested_fields(
        specs=[FieldSpec("amount", "decimal")],
        text="Subtotal : 100.00",
        zone_fields={},
        lines_by_page={1: [("Subtotal", (297, 53, 348, 73)), (": 100.00", (365, 52, 475, 72))]},
    )
    assert results[0].status == "not_found"


# Real per-line coordinates from an actual tax invoice with NO "Currency:"
# or "Vendor:"/"Supplier:" label anywhere on the page at all -- the vendor
# is presented only as a letterhead (company name directly under the "TAX
# INVOICE" title, followed by its address and contact line), and currency
# only ever appears as a "(LKR)" column-header annotation and inline
# before amounts. Dumped from the real PDF via PyMuPDF (x0, y0, x1, y1).
_REAL_INVOICE_3_LINES_PAGE_1: list[tuple[str, tuple[int, int, int, int]]] = [
    ("TAX INVOICE", (231, 50, 359, 75)),
    ("Oak Integrated Systems (Pvt) Ltd", (174, 77, 416, 95)),
    ("Level 26, East Tower, World Trade Center, Echelon Square, Colombo 01, Sri Lanka", (91, 97, 501, 110)),
    ("T: + 94 114 846 000| E: info@oakis.biz | W: www.oakis.biz", (149, 110, 443, 123)),
    ("Customer:", (36, 134, 90, 147)),
    ("Date", (329, 134, 353, 147)),
    (":", (426, 134, 433, 147)),
    ("15/12/2025", (498, 134, 557, 147)),
    ("Sisili Hanaro Encare (PVT) Ltd", (36, 149, 187, 162)),
    ("Invoice No", (329, 149, 383, 162)),
    (":", (426, 149, 433, 162)),
    ("IN/2025/01448", (482, 149, 557, 162)),
]

_REAL_INVOICE_3_TEXT = (
    "TAX INVOICE\nOak Integrated Systems (Pvt) Ltd\n"
    "Level 26, East Tower, World Trade Center, Echelon Square, Colombo 01, Sri Lanka\n"
    "T: + 94 114 846 000| E: info@oakis.biz | W: www.oakis.biz\n"
    "Customer:\nDate\n:\n15/12/2025\nSisili Hanaro Encare (PVT) Ltd\nInvoice No\n:\nIN/2025/01448\n"
    "Unit Price\n(LKR)\nQty\nSub Total\n(LKR)\n"
    "Total Value LKR 4,100,000.00\nAdvance 50% of total payment LKR 2,050,000.00\n"
    "Grand Total\n2,479,475.00\n"
    "LKR ACCOUNT\nUSD ACCOUNT\n0083002071\n0087095194\n"
)


def test_currency_pattern_fallback_finds_lkr_with_no_currency_label():
    """Real invoice found live: no "Currency:" label anywhere on the page
    at all, only "(LKR)" column-header annotations and "LKR 4,100,000.00"
    inline before an amount. Pattern-rule fallback must still recover it.
    """
    results = extract_requested_fields(
        specs=[FieldSpec("currency", "dropdown", options=["LKR", "USD", "EUR"])],
        text=_REAL_INVOICE_3_TEXT,
        zone_fields={},
    )
    assert results[0].value == "LKR"
    # Calibrated: this tier was forced to low_confidence on the reasoning
    # that an unlabelled currency is a guess, and measured 8/8 correct on
    # the real printed set. Keeping it below the line marked `currency` --
    # mandatory for INVOICE -- as missing on 8 documents that had it
    # right, and sent every one of them to the review queue.
    assert results[0].status == "extracted"


def test_currency_pattern_fallback_never_returns_a_value_outside_configured_options():
    """LKR is genuinely present in the text, but this tenant's currency
    dropdown only allows USD -- _match_enum must never invent a value
    outside the caller's own configured options, fallback or not.
    """
    results = extract_requested_fields(
        specs=[FieldSpec("currency", "dropdown", options=["USD"])],
        text=_REAL_INVOICE_3_TEXT,
        zone_fields={},
    )
    assert results[0].status == "not_found"


def test_vendor_letterhead_fallback_finds_company_name_skipping_title_and_address():
    """Real invoice found live: the vendor name has no "Vendor:"/
    "Supplier:" label at all -- just a letterhead (title, then company
    name, then address, then contact line). The fallback must skip the
    title and correctly land on the company name, not the address/email
    lines that come after it.
    """
    results = extract_requested_fields(
        specs=[FieldSpec("vendor_supplier_name", "text")],
        text=_REAL_INVOICE_3_TEXT,
        zone_fields={},
        lines_by_page={1: _REAL_INVOICE_3_LINES_PAGE_1},
    )
    assert results[0].value == "Oak Integrated Systems (Pvt) Ltd"
    assert results[0].status == "low_confidence"  # letterhead guess, not a labeled match
    assert results[0].page == 1
    assert results[0].bbox == (174, 77, 416, 95)


def test_vendor_supplier_name_prefers_a_real_label_over_the_letterhead_fallback():
    """When a real "Vendor:" label does exist, it must win over the
    letterhead guess -- the fallback is a last resort, not a shortcut.
    """
    results = extract_requested_fields(
        specs=[FieldSpec("vendor_supplier_name", "text")],
        text="Some Letterhead Co\nVendor: Acme Supplies Ltd",
        zone_fields={},
        lines_by_page={1: [
            ("Some Letterhead Co", (10, 10, 100, 20)),
            ("Vendor:", (10, 30, 60, 40)),
            ("Acme Supplies Ltd", (65, 30, 150, 40)),
        ]},
    )
    assert results[0].value == "Acme Supplies Ltd"


def test_vendor_letterhead_fallback_gives_up_once_it_reaches_a_label_with_no_candidate():
    """If the letterhead block is nothing but address/contact lines before
    the first real label, the fallback must return not_found rather than
    guessing at whatever text comes after the label (a different field's
    value, not the vendor's name).
    """
    results = extract_requested_fields(
        specs=[FieldSpec("vendor_supplier_name", "text")],
        text="123 Some Street, Some City, Some Country\nT: 555-1234\nInvoice No: INV-1",
        zone_fields={},
        lines_by_page={1: [
            ("123 Some Street, Some City, Some Country", (10, 10, 200, 20)),
            ("T: 555-1234", (10, 25, 100, 35)),
            ("Invoice No:", (10, 40, 80, 50)),
            ("INV-1", (85, 40, 130, 50)),
        ]},
    )
    assert results[0].status == "not_found"


# Real per-line coordinates from an actual pharmacy receipt (thermal/dot-
# matrix printer): PaddleOCR misread both "I"s in "INVOICE NO" as "1"
# ("1NVO1CE NO:8077224"), and the table has an "AMOUNT" *column header*
# (per-line-item price) with the real total sitting a few rows below,
# labeled "NET TOTAL".
_REAL_RECEIPT_LINES_PAGE_1: list[tuple[str, tuple[int, int, int, int]]] = [
    ("1NVO1CE NO:8077224", (147, 469, 372, 495)),
    ("DATE:03/01/2026", (592, 502, 758, 525)),
    ("AMOUNT", (908, 719, 1048, 745)),
    ("LOCERYL CREAM 30G", (147, 761, 449, 792)),
    ("2,334.00", (908, 761, 1048, 792)),
    ("NET TOTAL", (200, 964, 375, 989)),
    ("15,979.90", (908, 964, 1063, 989)),
]

_REAL_RECEIPT_TEXT = (
    "MEDIHELP HOSPITAL (PVT) LTD\nINVOICE\n1NVO1CE NO:8077224\nDATE:03/01/2026\n"
    "PRODUCT\nPRICE\nQTY\nAMOUNT\nLOCERYL CREAM 30G\n3,334.00\n1\n2,334.00\n"
    "NET TOTAL\n15,979.90\n"
)


def test_invoice_number_recovers_from_ocr_i_to_1_misread():
    """Real bug found live: a thermal-printer receipt's "INVOICE NO" was
    OCR'd as "1NVO1CE NO" (both "I"s misread as "1"), which an exact label
    match could never recognize -- invoice_number came back completely
    blank on an otherwise perfectly legible receipt.
    """
    results = extract_requested_fields(
        specs=[FieldSpec("invoice_number", "text")],
        text=_REAL_RECEIPT_TEXT,
        zone_fields={},
        lines_by_page={1: _REAL_RECEIPT_LINES_PAGE_1},
    )
    assert results[0].value == "8077224"


def test_amount_prefers_net_total_over_an_amount_column_header():
    """Real bug found live: the receipt's table has an "AMOUNT" column
    header (the per-line-item price) sitting earlier in reading order
    than the real "NET TOTAL" -- since "amount" the bare fallback used to
    sit before "total" in the alias list, it matched that column header
    first and grabbed the FIRST line item's own amount (2,334.00) instead
    of the actual total (15,979.90).
    """
    results = extract_requested_fields(
        specs=[FieldSpec("amount", "decimal")],
        text=_REAL_RECEIPT_TEXT,
        zone_fields={},
        lines_by_page={1: _REAL_RECEIPT_LINES_PAGE_1},
    )
    # `value` is the number; `raw_value` is how the page printed it.
    assert results[0].value == "15979.90"
    assert results[0].raw_value == "15,979.90"


def test_invoice_date_reads_ambiguous_numeric_dates_day_first():
    """Real bug found live: a Sri Lankan pharmacy receipt's "03/01/2026"
    was read month-first as 2026-03-01 (March 1st) instead of the correct
    2026-01-03 (January 3rd) -- confirmed wrong by the receipt's own
    handwritten follow-up note, "Submitted to COC 08/01/2026", which only
    makes sense a few days after a January 3rd invoice, not before a
    March 1st one.
    """
    results = extract_requested_fields(
        specs=[FieldSpec("invoice_date", "date")],
        text=_REAL_RECEIPT_TEXT,
        zone_fields={},
        lines_by_page={1: _REAL_RECEIPT_LINES_PAGE_1},
    )
    assert results[0].value == "2026-01-03"


# ----------------------------------------------------------------------
# Type-gated spatial matching
#
# Real per-line geometry from `Thanuja_Channelling` in the real eval set
# (a Medihelp payment receipt photographed with the consultant's rubber
# stamp overprinted across the Total row). Every bbox and every string
# below is what PaddleOCR actually returned for that page.
# ----------------------------------------------------------------------
_REAL_STAMPED_TOTAL_LINES_PAGE_1 = [
    ("Dr. Poorna Weerasinghe", (129, 394, 530, 461)),
    ("Consultation Practitioner Fee", (120, 712, 650, 770)),
    ("2,000.00", (902, 724, 1022, 762)),
    ("Consultation Hospital Fee", (124, 764, 620, 820)),
    ("1,000.00", (910, 774, 1029, 813)),
    ("MBBS,MD", (520, 787, 701, 836)),
    ("Total", (108, 806, 223, 856)),
    ("Consultant Dermatologist", (383, 823, 839, 886)),
    ("3,000.00", (867, 826, 1029, 874)),
    ("-SLMC-Ro0 No:12562", (418, 872, 788, 923)),
]


def test_a_stamp_overprinted_on_the_total_row_is_not_returned_as_the_amount():
    """Three documents in the real set failed exactly this way.

    "Consultant Dermatologist" starts at x=383 and "3,000.00" at x=867,
    both on the Total row, both to the right of the "Total" label. The
    nearest-line rule therefore picked the stamp -- and `amount` came
    back as a dermatologist's job title, marked `extracted`.

    Nothing about the stamp looks like a label, and it has real content,
    so the only property that separates it from the right answer is that
    it cannot be a decimal.
    """
    results = extract_requested_fields(
        specs=[FieldSpec("amount", "decimal")],
        text="Consultation Practitioner Fee 2,000.00 Consultation Hospital Fee 1,000.00 Total 3,000.00",
        zone_fields={},
        lines_by_page={1: _REAL_STAMPED_TOTAL_LINES_PAGE_1},
    )
    assert results[0].value == "3000.00"
    assert results[0].raw_value == "3,000.00"
    assert results[0].bbox == (867, 826, 1029, 874)


def test_the_gate_only_applies_to_decimals_not_to_free_text():
    """A vendor name has no shape to check, and gating it would reject
    legitimate values. The stamp line IS a valid vendor-name-shaped
    string, so this asserts the gate is not quietly global."""
    lines = [
        ("Vendor", (108, 806, 223, 856)),
        ("Consultant Dermatologist", (383, 823, 839, 886)),
    ]
    results = extract_requested_fields(
        specs=[FieldSpec("vendor_supplier_name", "text")],
        text="Vendor Consultant Dermatologist",
        zone_fields={},
        lines_by_page={1: lines},
    )
    assert results[0].value == "Consultant Dermatologist"


def test_a_label_whose_neighbours_are_all_the_wrong_type_does_not_abandon_the_field():
    """Before the gate, every matched label produced *some* string, so
    giving up after the first match cost nothing. With the gate, "the
    label matched but nothing near it was a number" became an ordinary
    outcome -- and stopping there would throw the field away on the first
    near-miss. Here the first "Total" has only prose beside it."""
    lines = [
        ("Total", (100, 100, 200, 130)),
        ("Qty 7", (300, 100, 400, 130)),
        ("Net Total", (100, 300, 260, 330)),
        ("30,288.00", (800, 300, 980, 330)),
    ]
    results = extract_requested_fields(
        specs=[FieldSpec("amount", "decimal")],
        text="Total Qty 7 Net Total 30,288.00",
        zone_fields={},
        lines_by_page={1: lines},
    )
    assert results[0].value == "30288.00"


# ----------------------------------------------------------------------
# A `decimal` field comes back as a number (production prompt 3.8)
# ----------------------------------------------------------------------
def test_a_currency_prefix_is_moved_out_of_the_amount_and_kept_as_raw_value():
    """Real: two OAK-template invoices returned "LKR 188,500.00" and
    "LKR 220,000.00" -- the right figure to the last digit, and unusable,
    because the currency belongs in `currency` and dms-core's
    `parseNumber` was being handed a string with letters in it."""
    lines = [
        ("INVOICE TOTAL", (740, 630, 875, 645)),
        ("LKR 188,500.00", (995, 630, 1125, 645)),
    ]
    results = extract_requested_fields(
        specs=[FieldSpec("amount", "decimal")],
        text="INVOICE TOTAL LKR 188,500.00",
        zone_fields={},
        lines_by_page={1: lines},
    )
    assert results[0].value == "188500.00"
    assert results[0].raw_value == "LKR 188,500.00"


def test_a_trailing_annotation_after_the_figure_does_not_lose_the_amount():
    """Real, Asiri Surgical Hospital: "TOTAL FEE : 4,100.00 (LKR) CS".
    The strict money parser rejects the whole token, so the field came
    back `not_found` even though the figure was read at confidence 1.00
    and the label was right there."""
    lines = [
        ("TOTAL FEE", (660, 838, 800, 858)),
        ("4,100.00 (LKR) CS", (1165, 845, 1420, 866)),
    ]
    results = extract_requested_fields(
        specs=[FieldSpec("amount", "decimal")],
        text="TOTAL FEE : 4,100.00 (LKR) CS",
        zone_fields={},
        lines_by_page={1: lines},
    )
    assert results[0].value == "4100.00"
    assert results[0].raw_value == "4,100.00 (LKR) CS"


def test_stripping_a_trailing_annotation_never_eats_an_accounting_negative():
    """"(1,234.50)" is a negative, not a figure with an annotation after
    it. The retry only runs when the strict parse has already failed, and
    this one does not fail -- which is the whole reason it is ordered that
    way."""
    assert extraction.parse_decimal_candidate("(1,234.50)").value == Decimal("-1234.50")
    assert not extraction.parse_decimal_candidate("(LKR) CS").ok
    # The merged qty/price/total run from a real thermal receipt must
    # stay rejected: no annotation to strip, and it is not a number.
    assert not extraction.parse_decimal_candidate("2 3,934.007,868.00").ok


# ----------------------------------------------------------------------
# ocr_extractable (V44) and the tenant default currency
# ----------------------------------------------------------------------
def test_a_field_that_is_not_ocr_extractable_is_never_attempted():
    """`classification_level` is mandatory for INVOICE and is chosen by us
    at filing time, so OCR reporting it "missing" put 20 of 21 real
    documents in the review queue. Not attempted, and the status says
    which kind of nothing this is."""
    spec = FieldSpec("classification_level", "dropdown", ["public", "internal"],
                     ocr_extractable=False)
    results = extract_requested_fields(
        specs=[spec],
        # The word is right there in the text; it must still not be read.
        text="CONFIDENTIAL - Internal use only. Invoice Total 100.00",
        zone_fields={},
    )
    assert results[0].value is None
    assert results[0].status == "not_extractable"


def test_not_extractable_beats_even_a_zone_template():
    """A zone template is the highest-priority tier, so if the flag did not
    short-circuit before it, a configured zone would be a way back in."""
    from ocr_worker.extraction import field_for_zone_name

    zone = field_for_zone_name("department", "Finance", 0.99, "dept_zone", bbox=(1, 2, 3, 4))
    results = extract_requested_fields(
        specs=[FieldSpec("department", "text", ocr_extractable=False)],
        text="Department: Finance",
        zone_fields={"department": zone},
    )
    assert results[0].status == "not_extractable"
    assert results[0].value is None


def test_a_page_with_no_currency_signal_gets_the_tenant_default():
    """Six real receipts print "Total 3,000.00" and name no currency at
    all. `defaulted` is deliberately not `extracted`: it is a real value,
    it is not something OCR read."""
    results = extract_requested_fields(
        specs=[FieldSpec("currency", "dropdown", ["USD", "LKR"])],
        text="Consultation Hospital Fee 1,000.00 Total 3,000.00",
        zone_fields={},
        default_currency="LKR",
    )
    assert results[0].value == "LKR"
    assert results[0].status == "defaulted"
    # No confidence at all, rather than a number that would be
    # indistinguishable from a real low-confidence reading.
    assert results[0].confidence is None


def test_a_printed_currency_always_beats_the_default():
    results = extract_requested_fields(
        specs=[FieldSpec("currency", "dropdown", ["USD", "LKR"])],
        text="Sub Total (USD) 1,200.00",
        zone_fields={},
        default_currency="LKR",
    )
    assert results[0].value == "USD"
    assert results[0].status != "defaulted"


def test_no_default_configured_still_reports_not_found():
    """The honest setting for a tenant that genuinely bills in several
    currencies."""
    results = extract_requested_fields(
        specs=[FieldSpec("currency", "dropdown", ["USD", "LKR"])],
        text="Total 3,000.00",
        zone_fields={},
        default_currency="",
    )
    assert results[0].status == "not_found"


def test_a_default_outside_the_fields_options_is_not_applied():
    """`_match_enum` never returns a value outside the caller's list, and
    a misconfigured default must not be the exception."""
    results = extract_requested_fields(
        specs=[FieldSpec("currency", "dropdown", ["USD", "EUR"])],
        text="Total 3,000.00",
        zone_fields={},
        default_currency="LKR",
    )
    assert results[0].status == "not_found"


# ----------------------------------------------------------------------
# The known-vendor list must never name the wrong company
# ----------------------------------------------------------------------
_KNOWN = [
    "MEDIHELP HOSPITAL (PVT) LTD",
    "Santa Dora Hospital (PVT) Ltd",
    "HEALTHGUARD PHARMACY LTD",
]


def _vendor_from(lines, known=_KNOWN):
    results = extract_requested_fields(
        specs=[FieldSpec("vendor_supplier_name", "text")],
        text=" ".join(t for t, _ in lines),
        zone_fields={},
        lines_by_page={1: lines},
        known_vendors=known,
    )
    return results[0]


def test_a_known_vendor_is_recognised_through_a_lost_logo_word():
    """The case the list exists for: a script letterhead came back as
    "Dora Hospital (PVT) Ltd" with "Santa" lost to the font, and no
    amount of cleaning turns the fragment back into the name."""
    lines = [("Dora Hospital (PVT) Ltd", (100, 100, 600, 130))]
    assert _vendor_from(lines).value == "Santa Dora Hospital (PVT) Ltd"


def test_a_generic_word_shared_by_two_vendors_is_refused():
    """Real: an Asiri letterhead sets its name stacked and OCR returned
    "HOSPITAL" alone, which is a substring of two different confirmed
    vendors. It was returned as the vendor, marked `extracted`, and was a
    different company entirely."""
    lines = [
        ("ASIRI", (100, 40, 200, 70)),
        ("HOSPITAL", (100, 100, 300, 130)),
        ("ASIRI SURGICAL HOSPITAL PLC", (100, 160, 700, 190)),
    ]
    result = _vendor_from(lines)
    assert result.value != "MEDIHELP HOSPITAL (PVT) LTD"
    assert result.value != "Santa Dora Hospital (PVT) Ltd"
    # Falls through to the letterhead fallback, which reads the page.
    assert result.value == "ASIRI SURGICAL HOSPITAL PLC"


def test_containment_turns_on_the_word_not_its_length():
    """Length was tried as the test and was wrong: requiring the fragment
    to be 70%% of the name rejected "hospital" (36%%) and also rejected
    "MEDIHELP" (36%%) on three real receipts -- three correct matches
    traded for one wrong one. Short and distinctive is not short and
    generic."""
    from ocr_worker.extraction import _KNOWN_VENDOR_MIN_RATIO, _vendor_key, _vendor_similarity

    # Distinctive fragments: the case the list exists for.
    assert _vendor_similarity(
        _vendor_key("Dora Hospital (PVT) Ltd"), _vendor_key("Santa Dora Hospital (PVT) Ltd")
    ) == 1.0
    assert _vendor_similarity(
        _vendor_key("MEDIHELP"), _vendor_key("MEDIHELP HOSPITAL (PVT) LTD")
    ) == 1.0
    # A generic marker identifies no company, whatever it is inside.
    assert _vendor_similarity(
        _vendor_key("HOSPITAL"), _vendor_key("MEDIHELP HOSPITAL (PVT) LTD")
    ) < _KNOWN_VENDOR_MIN_RATIO
    assert _vendor_similarity(
        _vendor_key("PHARMACY"), _vendor_key("HEALTHGUARD PHARMACY LTD")
    ) < _KNOWN_VENDOR_MIN_RATIO


def test_an_unknown_vendor_is_not_forced_onto_the_nearest_entry():
    lines = [("Northwind Trading Company", (100, 100, 700, 130))]
    assert _vendor_from(lines).value == "Northwind Trading Company"
