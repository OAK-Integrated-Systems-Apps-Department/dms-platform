"""Phase 3: deterministic value parsing (production prompt section 3.8 item 4).

The pattern rules this replaces were US-only -- `$` and `[\\d,]+\\.\\d{2}`
and nothing else -- in a system deployed in Sri Lanka. An ordinary local
invoice reading `Rs. 41,252.70` or `5,000/=` matched no amount at all.

Two behaviours are load-bearing and get the most attention here:

**A wrong money value is worse than no money value.** Reading `1.234` as
1.234 when the document meant 1,234 is a thousand-fold error on an
invoice total, and it would be presented with full confidence. So the
genuinely ambiguous shapes come back flagged rather than resolved.

**Ambiguous dates are flagged, not guessed.** `03/04/2026` is two real
dates. The locale decides how to read it; the flag is what stops that
reading being presented as fact.
"""

from __future__ import annotations

import sys
from decimal import Decimal
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "src"))

from ocr_worker.patterns import (  # noqa: E402
    DAY_FIRST,
    MONTH_FIRST,
    amount_in_words,
    parse_amount,
    parse_date,
)


# ----------------------------------------------------------------------
# Currency forms the old rules could not read at all
# ----------------------------------------------------------------------
@pytest.mark.parametrize(
    "raw,expected,currency",
    [
        ("Rs. 41,252.70", Decimal("41252.70"), "LKR"),
        ("Rs 41,252.70", Decimal("41252.70"), "LKR"),
        ("RS.41252.70", Decimal("41252.70"), "LKR"),
        ("LKR 41,252.70", Decimal("41252.70"), "LKR"),
        ("41,252.70 LKR", Decimal("41252.70"), "LKR"),
        ("$1,234.50", Decimal("1234.50"), "USD"),
        ("€1.234,56", Decimal("1234.56"), "EUR"),
        ("£999.99", Decimal("999.99"), "GBP"),
        ("₨ 5,000.00", Decimal("5000.00"), "LKR"),
    ],
)
def test_currency_symbols_and_codes_are_read_and_identified(raw, expected, currency):
    parsed = parse_amount(raw)
    assert parsed.value == expected
    assert parsed.currency == currency
    assert parsed.raw == raw  # the raw string always survives


@pytest.mark.parametrize("raw,expected", [("5,000/=", Decimal(5000)), ("5000/-", Decimal(5000)),
                                          ("Rs. 12,500/=", Decimal(12500))])
def test_the_sri_lankan_slash_terminator_is_stripped(raw, expected):
    """`5,000/=` is how a rupee amount is routinely ended on a local
    invoice or receipt. The trailing punctuation used to break the match
    or land inside the value."""
    assert parse_amount(raw).value == expected


# ----------------------------------------------------------------------
# Separators
# ----------------------------------------------------------------------
@pytest.mark.parametrize(
    "raw,expected",
    [
        ("1,234.56", Decimal("1234.56")),     # comma thousands, dot decimal
        ("1.234,56", Decimal("1234.56")),     # dot thousands, comma decimal
        ("1 234.56", Decimal("1234.56")),     # space thousands
        ("1 234,56", Decimal("1234.56")),  # non-breaking space thousands
        ("1.234.567", Decimal("1234567")),    # repeated separator: grouping only
        ("1,234,567.89", Decimal("1234567.89")),
        ("41252.70", Decimal("41252.70")),
        ("0.5", Decimal("0.5")),
        ("1234", Decimal("1234")),
    ],
)
def test_thousands_and_decimal_separators(raw, expected):
    assert parse_amount(raw).value == expected


def test_when_both_separators_appear_the_last_one_is_the_decimal():
    """Locale-free and unambiguous: whichever of `,` and `.` appears
    later must be the decimal separator, because grouping separators
    always come before it."""
    assert parse_amount("1,234.56").value == Decimal("1234.56")
    assert parse_amount("1.234,56").value == Decimal("1234.56")
    assert parse_amount("1,234.56").ambiguous is False
    assert parse_amount("1.234,56").ambiguous is False


@pytest.mark.parametrize("raw", ["1.234", "41,252", "999,000"])
def test_a_single_separator_before_three_digits_is_flagged_ambiguous(raw):
    """THE dangerous shape. `1.234` is either 1234 or 1.234 and nothing
    in the token says which -- a 1000x error on an invoice total if
    guessed wrong. It resolves to the commoner reading (thousands) and
    says it was unsure, so the field can be capped at low_confidence."""
    parsed = parse_amount(raw)
    assert parsed.ambiguous is True
    assert parsed.value == Decimal(raw.replace(".", "").replace(",", ""))
    assert "thousands separator or a decimal point" in parsed.reason


@pytest.mark.parametrize("raw,expected", [("1.5", Decimal("1.5")), ("1.50", Decimal("1.50")),
                                          ("12.3456", Decimal("12.3456"))])
def test_one_two_or_four_digits_after_a_separator_is_unambiguously_decimal(raw, expected):
    """A thousands group is always exactly three digits, so anything else
    after a lone separator can only be a decimal point."""
    parsed = parse_amount(raw)
    assert parsed.value == expected
    assert parsed.ambiguous is False


def test_a_caller_that_knows_the_locale_can_resolve_the_ambiguity():
    assert parse_amount("1.234", assume_decimal_separator=".").value == Decimal("1.234")
    assert parse_amount("1.234", assume_decimal_separator=",").value == Decimal("1234")
    assert parse_amount("1.234", assume_decimal_separator=".").ambiguous is False


# ----------------------------------------------------------------------
# Negatives
# ----------------------------------------------------------------------
@pytest.mark.parametrize(
    "raw,expected",
    [
        ("(1,234.50)", Decimal("-1234.50")),
        ("(Rs. 1,234.50)", Decimal("-1234.50")),
        ("-1,234.50", Decimal("-1234.50")),
        ("(0.00)", Decimal("0.00")),
    ],
)
def test_parentheses_are_accounting_negatives(raw, expected):
    parsed = parse_amount(raw)
    assert parsed.value == expected
    assert parsed.negative is True


def test_an_ordinary_amount_is_not_negative():
    assert parse_amount("1,234.50").negative is False


# ----------------------------------------------------------------------
# Refusing to guess
# ----------------------------------------------------------------------
@pytest.mark.parametrize("raw", ["", "   ", "N/A", "see attached", "INV-2026-05426", None])
def test_anything_that_is_not_a_number_returns_none_rather_than_a_guess(raw):
    parsed = parse_amount(raw)
    assert parsed.value is None
    assert parsed.reason  # and says why


def test_the_raw_string_is_always_preserved():
    """Section 3.8: 'always store the raw OCR string alongside the
    normalized value'. A consumer that disagrees with the normalization
    must be able to see what was actually read."""
    assert parse_amount("Rs. 41,252.70").raw == "Rs. 41,252.70"
    assert parse_amount("nonsense").raw == "nonsense"


# ----------------------------------------------------------------------
# Amounts in words -- cross-check only
# ----------------------------------------------------------------------
@pytest.mark.parametrize(
    "text,expected",
    [
        ("Forty One Thousand Two Hundred Fifty Two", 41252),
        ("Rupees Forty One Thousand Two Hundred Fifty Two Only", 41252),
        ("one thousand two hundred thirty four", 1234),
        ("five hundred", 500),
        ("twenty", 20),
        ("two million three hundred thousand", 2_300_000),
        ("three lakhs", 300_000),
    ],
)
def test_amounts_in_words(text, expected):
    assert amount_in_words(text) == Decimal(expected)


@pytest.mark.parametrize("text", ["", "see attached", "Forty One Thousand Rupiah Banana", "41,252.70"])
def test_an_unrecognized_word_yields_none_not_a_partial_number(text):
    """A half-parsed amount is worse than none: it would produce a
    confident mismatch against a correct numeric value and flag a good
    field for review."""
    assert amount_in_words(text) is None


# ----------------------------------------------------------------------
# Dates
# ----------------------------------------------------------------------
def test_an_ambiguous_numeric_date_is_read_day_first_by_default_and_flagged():
    """Section 3.8 names this exact case. The real document that forced
    the day-first default was a Sri Lankan receipt whose `03/01/2026` was
    read as March 1st instead of January 3rd."""
    parsed = parse_date("03/04/2026")
    assert parsed.iso == "2026-04-03"
    assert parsed.ambiguous is True
    assert "both <= 12" in parsed.reason


def test_the_locale_is_per_call_not_hardcoded():
    assert parse_date("03/04/2026", locale=DAY_FIRST).iso == "2026-04-03"
    assert parse_date("03/04/2026", locale=MONTH_FIRST).iso == "2026-03-04"
    assert parse_date("03/04/2026", locale=MONTH_FIRST).ambiguous is True


@pytest.mark.parametrize(
    "raw,expected",
    [
        ("25/03/2026", "2026-03-25"),   # day > 12: only one reading works
        ("2026-03-01", "2026-03-01"),   # ISO
        ("2026/03/01", "2026-03-01"),
        ("1 May 2026", "2026-05-01"),
        ("May 1 2026", "2026-05-01"),
        ("05 January 2026", "2026-01-05"),
        ("31.12.2026", "2026-12-31"),
    ],
)
def test_unambiguous_dates_are_never_flagged(raw, expected):
    parsed = parse_date(raw)
    assert parsed.iso == expected
    assert parsed.ambiguous is False


def test_a_two_digit_year_uses_a_stated_pivot():
    assert parse_date("25/03/26").iso == "2026-03-25"
    assert parse_date("25/03/85").iso == "1985-03-25"


@pytest.mark.parametrize("raw", ["", "not a date", "32/13/2026", "00/00/0000"])
def test_an_unparseable_date_returns_none_with_a_reason(raw):
    parsed = parse_date(raw)
    assert parsed.iso is None
    assert parsed.reason


def test_an_impossible_date_is_rejected_rather_than_rolled_over():
    """31 February must not become 3 March. A rolled-over date is a
    fabricated value, which section 3.8 forbids outright."""
    assert parse_date("31/02/2026").iso is None


# ----------------------------------------------------------------------
# Locale resolves the ambiguous shape
# ----------------------------------------------------------------------
from ocr_worker.patterns import decimal_separator_for  # noqa: E402


def test_a_comma_decimal_locale_reads_1_234_as_one_thousand():
    """With the locale known, `1.234` is no longer a coin flip: in a
    comma-decimal region a dot can only be grouping."""
    parsed = parse_amount("1.234", assume_decimal_separator=",")
    assert parsed.value == Decimal(1234)
    assert parsed.ambiguous is False


def test_a_dot_decimal_locale_reads_1_234_as_one_point_two_three_four():
    parsed = parse_amount("1.234", assume_decimal_separator=".")
    assert parsed.value == Decimal("1.234")
    assert parsed.ambiguous is False


def test_the_month_first_locale_implies_a_dot_decimal_separator():
    """US convention is month-first dates and dot decimals; they travel
    together, so one setting resolves both."""
    assert decimal_separator_for(MONTH_FIRST) == "."
    parsed = parse_amount("1.234", locale=MONTH_FIRST)
    assert parsed.value == Decimal("1.234")
    assert parsed.ambiguous is False


def test_a_locale_that_implies_nothing_leaves_the_ambiguity_flagged():
    """`day_first` is used in both dot- and comma-decimal regions, so it
    cannot settle the number. Saying 'unsure' is right here -- guessing is
    a 1000x error on a total."""
    assert decimal_separator_for(DAY_FIRST) is None
    parsed = parse_amount("1.234", locale=DAY_FIRST)
    assert parsed.ambiguous is True


def test_an_explicit_separator_wins_over_the_locale():
    parsed = parse_amount("1.234", assume_decimal_separator=",", locale=MONTH_FIRST)
    assert parsed.value == Decimal(1234)


def test_no_locale_at_all_behaves_exactly_as_before():
    assert parse_amount("1.234", locale=None).ambiguous is True
    assert parse_amount("1.234").ambiguous is True
