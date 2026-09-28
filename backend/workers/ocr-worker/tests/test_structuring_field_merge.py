"""structuring/field_merge.py: how the LLM's values for the DMS's requested
fields replace or fill the rule-based (label-hint/regex) results."""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "src"))

from ocr_worker.extraction import FieldSpec, RequestedFieldResult  # noqa: E402
from ocr_worker.structuring.field_merge import merge_requested_fields  # noqa: E402

SPECS = [
    FieldSpec(field_key="line_manager", data_type="text"),
    FieldSpec(field_key="email", data_type="text"),
    FieldSpec(field_key="department", data_type="text"),
    FieldSpec(field_key="tags", data_type="multi_select"),
    FieldSpec(field_key="priority", data_type="dropdown", options=["High", "Low"]),
]
RULES = [
    # Typical rule-based misses on a two-column/blank-label layout:
    RequestedFieldResult(field_key="line_manager", value="Work Location:", confidence=0.7, status="extracted", page=1, bbox=(1, 2, 3, 4)),
    RequestedFieldResult(field_key="email", value="Phone:", confidence=0.65, status="extracted", page=1, bbox=(1, 2, 3, 4)),
    RequestedFieldResult(field_key="department", value="Finance", confidence=0.9, status="extracted", page=1, bbox=(5, 6, 7, 8)),
    RequestedFieldResult(field_key="tags", value=None, confidence=None, status="not_found"),
    RequestedFieldResult(field_key="priority", value=None, confidence=None, status="not_found"),
]


def _structured(fields, status="completed"):
    return {"status": status, "requested_fields": [s.field_key for s in SPECS], "fields": fields}


def _by_key(results):
    return {r.field_key: r for r in results}


def test_llm_first_replaces_wrong_rule_values_and_nulls_blank_labels():
    merged, sources = merge_requested_fields(
        SPECS, RULES, _structured({"line_manager": "Nimal Fernando", "email": None, "department": "Finance", "tags": ["hr", "onboarding"], "priority": "high"})
    )
    m = _by_key(merged)
    assert (m["line_manager"].value, m["line_manager"].status, m["line_manager"].confidence) == ("Nimal Fernando", "extracted", 0.85)
    assert m["line_manager"].bbox is None  # the rule result's box belonged to the wrong text
    assert (m["email"].value, m["email"].status) == (None, "not_found")
    assert m["department"].bbox == (5, 6, 7, 8)  # agreement keeps the rule result (and its position)
    assert m["tags"].value == ["hr", "onboarding"]
    assert m["priority"].value == "High"  # canonical option spelling
    assert set(sources.values()) == {"llm"}


def test_fill_missing_keeps_rule_values_and_only_fills_gaps():
    merged, sources = merge_requested_fields(
        SPECS, RULES, _structured({"line_manager": "Nimal Fernando", "email": None, "department": "HR", "tags": ["hr"], "priority": None}),
        policy="fill_missing",
    )
    m = _by_key(merged)
    assert m["line_manager"].value == "Work Location:" and m["department"].value == "Finance"
    assert m["tags"].value == ["hr"] and sources["tags"] == "llm"
    assert sources["line_manager"] == "rules"


def test_failed_structuring_leaves_every_rule_result_untouched():
    for status in ("failed", "skipped", "pending"):
        merged, sources = merge_requested_fields(SPECS, RULES, _structured({"line_manager": "X"}, status=status))
        assert merged == RULES and set(sources.values()) == {"rules"}


def test_partial_result_never_nulls_a_rule_value():
    # A partial run may simply not have covered the page the value is on.
    merged, _ = merge_requested_fields(SPECS, RULES, _structured({"line_manager": None, "email": None}, status="partial"))
    assert _by_key(merged)["email"].value == "Phone:"


def test_values_that_do_not_fit_the_field_keep_the_rule_result():
    merged, sources = merge_requested_fields(
        SPECS, RULES, _structured({"line_manager": ["Nimal Fernando", "Sunil Perera"], "department": {"name": "Finance"}, "priority": "Urgent"})
    )
    m = _by_key(merged)
    assert m["line_manager"].value == "Work Location:"  # two values for a single-value field: ambiguous
    assert m["department"].value == "Finance" and m["priority"].status == "not_found"
    assert sources["line_manager"] == "rules"


def test_fields_the_llm_was_not_asked_for_are_not_touched():
    structured = {"status": "completed", "requested_fields": ["department"], "fields": {"line_manager": "Nimal Fernando", "department": "Finance"}}
    merged, sources = merge_requested_fields(SPECS, RULES, structured)
    assert _by_key(merged)["line_manager"].value == "Work Location:" and sources["line_manager"] == "rules"


def test_a_grounding_nulled_value_keeps_the_rule_result():
    # The model found a value the guard couldn't match to the source text --
    # that is not the model judging the field absent.
    structured = _structured({"department": None})
    structured["warnings"] = [{"code": "UNGROUNDED_VALUE", "message": "value not found in the document text; replaced with null", "path": "fields.department"}]
    merged, sources = merge_requested_fields(SPECS, RULES, structured)
    assert _by_key(merged)["department"].value == "Finance" and sources["department"] == "rules"


TYPED_SPECS = [
    FieldSpec(field_key="amount", data_type="decimal"),
    FieldSpec(field_key="invoice_date", data_type="date"),
    FieldSpec(field_key="signed", data_type="boolean"),
    FieldSpec(field_key="address", data_type="text"),
    FieldSpec(field_key="tags", data_type="multi_select", options=["Finance", "HR"]),
]
NOT_FOUND = [RequestedFieldResult(field_key=s.field_key, value=None, confidence=None, status="not_found") for s in TYPED_SPECS]


def _typed(fields):
    return {"status": "completed", "requested_fields": [s.field_key for s in TYPED_SPECS], "fields": fields}


def test_values_are_coerced_to_what_dms_core_and_the_form_accept():
    merged, _ = merge_requested_fields(TYPED_SPECS, NOT_FOUND, _typed({
        "amount": "LKR 150,000.00",
        "invoice_date": "12 September 2026",
        "signed": True,
        "address": {"street": "12 Galle Road", "city": "Colombo"},
        "tags": ["finance", "Travel"],
    }))
    m = _by_key(merged)
    assert m["amount"].value == "150000.00"  # BigDecimal / type=number: no currency, no commas
    assert m["invoice_date"].value == "2026-09-12"  # dms-core parses ISO dates only
    assert m["signed"].value == "true"  # Boolean.parseBoolean("Yes") would be false
    assert m["address"].value == "12 Galle Road, Colombo"
    assert m["tags"].value == ["Finance"]  # only configured options render in the form


def test_numeric_json_values_become_plain_decimals():
    merged, _ = merge_requested_fields(TYPED_SPECS, NOT_FOUND, _typed({"amount": 1234.5}))
    assert _by_key(merged)["amount"].value == "1234.5"


def test_an_uncoercible_value_keeps_the_rule_result():
    rules = [RequestedFieldResult(field_key="invoice_date", value="2026-09-01", confidence=0.9, status="extracted", page=1, bbox=(1, 2, 3, 4)),
             RequestedFieldResult(field_key="amount", value="2,479,475.00", confidence=0.8, status="extracted", page=1, bbox=(5, 6, 7, 8))]
    merged, _ = merge_requested_fields(TYPED_SPECS, rules, _typed({"invoice_date": "sometime in autumn", "amount": "10 x 25.00"}))
    m = _by_key(merged)
    assert m["invoice_date"].value == "2026-09-01" and m["amount"].value == "2,479,475.00"


def test_agreement_after_coercion_keeps_the_rule_position_with_the_clean_value():
    rules = [RequestedFieldResult(field_key="amount", value="2,479,475.00", confidence=0.8, status="extracted", page=1, bbox=(5, 6, 7, 8))]
    merged, _ = merge_requested_fields(TYPED_SPECS, rules, _typed({"amount": "2479475.00"}))
    m = _by_key(merged)
    assert (m["amount"].value, m["amount"].bbox) == ("2479475.00", (5, 6, 7, 8))
