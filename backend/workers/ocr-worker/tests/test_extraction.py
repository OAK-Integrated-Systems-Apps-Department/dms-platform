from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "src"))

from ocr_worker.extraction import extract_structured_fields, field_for_zone_name, fields_to_dict  # noqa: E402


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
    field = field_for_zone_name("invoice_number", "INV-99", 0.88, "invoice_number_zone", bbox=(10, 20, 30, 40))
    result = fields_to_dict([field])
    assert result == {"invoice_number": {"value": "INV-99", "confidence": 0.88, "bbox": [10, 20, 30, 40]}}


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
