"""Phase 3: cross-field validation (production prompt section 3.9).

The rule the whole section hangs on, and the one most of these tests are
really about:

> a failing rule sets the involved fields to `low_confidence` with a
> `validation_errors` entry, **never drops them**.

Nothing here deletes or edits a value. A validator that silently dropped
a failing field would turn a visible wrong value into an invisible
missing one, which is strictly worse -- a user cannot correct what they
cannot see, and the review queue exists precisely so they can.

Two design choices get their own tests because both are easy to get
wrong in the direction that produces noise:

- **A failing rule flags every field it involved**, not the one that
  looks guilty. When `subtotal + tax != total` there is no way to know
  which of the three was misread; blaming one would be a guess dressed
  as a diagnosis.
- **A missing input skips its rule.** A missing subtotal is already
  reported as `not_found`; raising a validation failure for it as well
  reports one gap twice and teaches whoever reads these to ignore them.
"""

from __future__ import annotations

import sys
from decimal import Decimal
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "src"))

from ocr_worker.validation import (  # noqa: E402
    combine,
    cross_check_amount_in_words,
    validate_currency,
    validate_dates,
    validate_mandatory,
    validate_money,
)


# ----------------------------------------------------------------------
# Arithmetic
# ----------------------------------------------------------------------
def test_subtotal_plus_tax_must_equal_total():
    result = validate_money(
        subtotal=Decimal("1000.00"), tax_amount=Decimal("150.00"), total=Decimal("1200.00")
    )
    assert not result.ok
    assert result.errors[0].rule == "subtotal_plus_tax_equals_total"
    # All three, not a guess at which one is wrong.
    assert set(result.errors[0].fields) == {"subtotal", "tax_amount", "total"}


def test_a_consistent_invoice_raises_nothing():
    result = validate_money(
        subtotal=Decimal("1000.00"), tax_amount=Decimal("150.00"), total=Decimal("1150.00")
    )
    assert result.ok


def test_the_documents_own_rounding_is_tolerated():
    """OCR reads what is printed, and what is printed is already rounded.
    A tolerance of two minor units covers that; it is far too small to
    hide a misread digit, which moves a value by at least a whole unit."""
    assert validate_money(
        subtotal=Decimal("1000.00"), tax_amount=Decimal("150.01"), total=Decimal("1150.00")
    ).ok


def test_a_misread_digit_is_not_tolerated():
    assert not validate_money(
        subtotal=Decimal("1000.00"), tax_amount=Decimal("150.00"), total=Decimal("1151.00")
    ).ok


def test_line_totals_must_sum_to_the_subtotal():
    result = validate_money(
        subtotal=Decimal("500.00"),
        line_totals=[Decimal("222.00"), Decimal("288.00"), Decimal("10.00")],
    )
    assert not result.ok
    assert result.errors[0].rule == "line_totals_equal_subtotal"


def test_quantity_times_unit_price_must_equal_the_line_total():
    result = validate_money(line_items=[
        {"quantity": 12, "unit_price": Decimal("18.50"), "line_total": Decimal("222.00")},
        {"quantity": 3, "unit_price": Decimal("96.00"), "line_total": Decimal("188.00")},
    ])
    assert len(result.errors) == 1
    assert "line 2" in result.errors[0].message


@pytest.mark.parametrize("kwargs", [
    {"subtotal": Decimal("100"), "total": Decimal("115")},        # no tax
    {"tax_amount": Decimal("15"), "total": Decimal("115")},       # no subtotal
    {},                                                            # nothing at all
])
def test_a_rule_whose_inputs_are_missing_is_skipped_not_failed(kwargs):
    assert validate_money(**kwargs).ok


def test_a_line_item_missing_a_component_is_skipped():
    assert validate_money(line_items=[{"quantity": 12, "line_total": Decimal("222.00")}]).ok


# ----------------------------------------------------------------------
# Dates
# ----------------------------------------------------------------------
def test_an_unparseable_date_is_reported():
    result = validate_dates({"invoice_date": "not a date"})
    assert not result.ok
    assert result.errors[0].rule == "date_unparseable"
    assert result.errors[0].fields == ("invoice_date",)


def test_an_implausible_year_is_reported():
    result = validate_dates({"invoice_date": "2026-03-01", "due_date": "1901-03-01"})
    assert [error.rule for error in result.errors] == ["date_out_of_plausible_range"]


def test_a_due_date_may_not_precede_its_invoice():
    result = validate_dates({"invoice_date": "2026-07-11", "due_date": "2026-06-01"})
    assert not result.ok
    assert result.errors[0].rule == "date_order"
    assert set(result.errors[0].fields) == {"invoice_date", "due_date"}


def test_an_expiry_may_not_precede_its_effective_date():
    result = validate_dates({"effective_date": "2026-07-11", "expiry_date": "2026-06-01"})
    assert result.errors[0].rule == "date_order"


def test_ordinary_ordering_passes():
    assert validate_dates({
        "invoice_date": "2026-07-11", "due_date": "2026-08-10",
        "effective_date": "2026-01-01", "expiry_date": "2026-12-31",
    }).ok


def test_only_pairs_with_a_real_required_order_are_checked():
    """There is deliberately no generic "the later date comes second"
    rule. Plenty of legitimate date pairs have no required order, and
    inventing one would flag correct documents."""
    result = validate_dates({"invoice_date": "2026-07-11", "review_due_date": "2026-01-01"})
    assert result.ok


def test_the_date_locale_is_honoured():
    """`03/04/2026` orders differently depending on how it is read, so
    the ordering rule has to use the tenant's locale or it will invent
    failures."""
    values = {"invoice_date": "03/04/2026", "due_date": "05/04/2026"}
    assert validate_dates(values, locale="day_first").ok
    assert validate_dates(values, locale="month_first").ok
    crossing = {"invoice_date": "04/03/2026", "due_date": "03/04/2026"}
    assert validate_dates(crossing, locale="day_first").ok
    assert not validate_dates(crossing, locale="month_first").ok


@pytest.mark.parametrize("raw", [None, "", "   "])
def test_an_absent_date_is_not_a_failure(raw):
    assert validate_dates({"invoice_date": raw}).ok


# ----------------------------------------------------------------------
# Currency and mandatory fields
# ----------------------------------------------------------------------
def test_currency_must_be_one_of_the_configured_options():
    result = validate_currency("XYZ", ["LKR", "USD", "EUR"])
    assert not result.ok
    assert result.errors[0].rule == "currency_not_in_options"


@pytest.mark.parametrize("value", ["EUR", "eur", " EUR "])
def test_currency_comparison_ignores_case_and_padding(value):
    assert validate_currency(value, ["LKR", "USD", "EUR"]).ok


def test_an_unconstrained_currency_field_cannot_fail():
    assert validate_currency("XYZ", None).ok
    assert validate_currency("XYZ", []).ok


@pytest.mark.parametrize("value", [None, "", "   ", []])
def test_a_missing_mandatory_field_is_reported(value):
    result = validate_mandatory({"invoice_number": value}, ["invoice_number"])
    assert not result.ok
    assert result.errors[0].rule == "mandatory_field_missing"


def test_a_present_mandatory_field_passes():
    assert validate_mandatory({"invoice_number": "INV-2026-05426"}, ["invoice_number"]).ok


# ----------------------------------------------------------------------
# Amount in words, as a cross-check
# ----------------------------------------------------------------------
def test_the_amount_in_words_confirms_the_numeric_read():
    assert cross_check_amount_in_words(Decimal("41252.70"), Decimal(41252)).ok


def test_a_mismatch_between_digits_and_words_is_reported():
    result = cross_check_amount_in_words(Decimal("41252.70"), Decimal(41152))
    assert not result.ok
    assert result.errors[0].rule == "amount_in_words_mismatch"


def test_only_whole_units_are_compared():
    """Real documents write the words against whole units -- "Forty One
    Thousand Two Hundred Fifty Two Only" next to 41,252.70. Demanding the
    cents match would fail every correct document using that
    convention."""
    assert cross_check_amount_in_words(Decimal("41252.70"), Decimal(41252)).ok
    assert cross_check_amount_in_words(Decimal("41252.00"), Decimal(41252)).ok


def test_a_missing_side_of_the_cross_check_is_not_a_failure():
    assert cross_check_amount_in_words(None, Decimal(41252)).ok
    assert cross_check_amount_in_words(Decimal("41252.70"), None).ok


# ----------------------------------------------------------------------
# The contract the whole section rests on
# ----------------------------------------------------------------------
def test_validation_reports_and_never_removes():
    """Nothing in this module returns a modified value set. It returns
    findings, and the fields to flag -- so a failing field still reaches
    the user with its raw OCR string, marked for review."""
    result = combine(
        validate_money(subtotal=Decimal("1000"), tax_amount=Decimal("150"), total=Decimal("1200")),
        validate_dates({"invoice_date": "2026-07-11", "due_date": "2026-06-01"}),
        validate_currency("XYZ", ["LKR"]),
        validate_mandatory({}, ["invoice_number"]),
    )
    assert len(result.errors) == 4
    assert result.fields_to_flag() == {
        "subtotal", "tax_amount", "total", "invoice_date", "due_date",
        "currency", "invoice_number",
    }
    # Serializable for `validation_errors` in the output contract.
    as_dicts = result.to_dict()
    assert all(set(entry) == {"rule", "message", "fields"} for entry in as_dicts)
    assert all(entry["message"] for entry in as_dicts)
