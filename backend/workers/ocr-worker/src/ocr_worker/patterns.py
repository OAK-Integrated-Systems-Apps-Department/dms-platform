"""Deterministic value parsing: money and dates.

Phase 3, production prompt section 3.8 item 4. Everything here is pure --
no models, no I/O, no engine -- so it is unit-testable in milliseconds and
a change to it can be reasoned about without a corpus run.

Two rules govern the whole module:

**Never invent a value.** Every parse returns the raw string alongside
whatever normalized form it managed, and returns `None` for the
normalized form rather than guessing. Section 3.8: "never clip, invent,
or 'correct' a value beyond deterministic normalization. Always store the
raw OCR string alongside the normalized value."

**Say when you are unsure.** Real documents are genuinely ambiguous --
`03/04/2026` is two different dates and `1.234` is two different numbers,
and no amount of cleverness resolves either from the token alone. Those
come back flagged rather than silently resolved, so the tier above can
mark the field `low_confidence` instead of presenting a coin-flip as
fact.
"""

from __future__ import annotations

import re
import unicodedata
from dataclasses import dataclass
from datetime import date, datetime
from decimal import Decimal, InvalidOperation

# ----------------------------------------------------------------------
# Currency
# ----------------------------------------------------------------------
# Symbols and codes seen on the documents this system actually handles.
# `Rs`/`Rs.`/`LKR` lead because Sri Lanka is the deployment: the previous
# pattern rules were US-only (`$` and nothing else), so an ordinary local
# invoice matched no amount at all.
_CURRENCY_TOKENS: tuple[tuple[str, str], ...] = (
    ("LKR", "LKR"),
    ("RS.", "LKR"),
    ("RS", "LKR"),
    ("USD", "USD"),
    ("EUR", "EUR"),
    ("GBP", "GBP"),
    ("INR", "INR"),
    ("AUD", "AUD"),
    ("$", "USD"),
    ("€", "EUR"),   # €
    ("£", "GBP"),   # £
    ("₹", "INR"),   # ₹
    ("₨", "LKR"),   # ₨
)

# "5,000/=" and "5000/-" are how a rupee amount is routinely terminated on
# a Sri Lankan invoice or receipt. Without this the trailing punctuation
# either breaks the match or lands inside the captured value.
_TRAILING_SUFFIX_RE = re.compile(r"\s*/[=\-]\s*$")


# ----------------------------------------------------------------------
# Finding the currency in a page of text
# ----------------------------------------------------------------------
# ISO 4217 codes only -- deliberately a fixed whitelist rather than "any
# three capitals", which would match VAT, PVT and LTD on every invoice
# here.
_ISO_CODES: tuple[str, ...] = (
    "USD", "EUR", "GBP", "LKR", "INR", "AUD", "CAD", "JPY", "CNY", "SGD",
    "AED", "SAR", "PKR", "BDT", "NPR", "ZAR", "NZD", "CHF", "HKD", "MYR",
    "THB", "PHP", "IDR", "VND", "KRW", "EGP", "QAR", "KWD", "BHD", "OMR",
)
_ISO_CODE_RE = re.compile(r"\b(" + "|".join(_ISO_CODES) + r")\b")

# `Rs` is how a Sri Lankan document almost always writes its currency, and
# it needs more care than a word-boundary match.
#
#   (?<![A-Za-z])Rs   a standalone "Rs." / "Rs 1,200"
#   (?<=[a-z])Rs      OCR glues a column header to its neighbour: a real
#                     Santa Dora receipt came back as "Pay Type AmountRs",
#                     with no space, on two separate documents
#
# Both arms are CASE SENSITIVE on "Rs" and forbid a following lowercase
# letter, which is what keeps the false positives out. On this corpus the
# words that would otherwise match are "Mrs." (on a handwritten form whose
# currency label was removed precisely because nothing is printed),
# "HOURS", "Others" and "yrs" -- "Mrs" is excluded by the uppercase-M
# lookbehind, the rest by case.
_RUPEE_RE = re.compile(r"(?:(?<![A-Za-z])|(?<=[a-z]))Rs\.?(?![a-z])")

# "1100/=" and "1100/-", the Sri Lankan terminator, used here as a
# currency signal rather than as punctuation to strip. Anchored to a digit
# so a bare "/=" or a date like "1/2-3" cannot trigger it.
_RUPEE_SUFFIX_RE = re.compile(r"\d\s*/[=\-]")

_SYMBOLS: tuple[tuple[str, str], ...] = (
    ("₨", "LKR"), ("₹", "INR"), ("€", "EUR"), ("£", "GBP"), ("$", "USD"),
)


def detect_currency(text: str) -> str | None:
    """The currency this page is denominated in, or None.

    Ordered by how unambiguous each signal is: an explicit ISO code beats
    a symbol, and a symbol beats the `Rs`/`/=` conventions, which are the
    loosest. First match wins, mirroring the "first mention wins"
    behaviour a reader has.

    `extraction.py` previously had its own ISO-codes-only copy of this and
    never consulted this module -- so `Rs.` printed as a column header at
    confidence 1.00, and the `/=` terminator, were simply not currency as
    far as the `currency` field was concerned.
    """
    if not text:
        return None
    normalized = unicodedata.normalize("NFKC", text)

    iso = _ISO_CODE_RE.search(normalized)
    if iso:
        return iso.group(1)
    for symbol, code in _SYMBOLS:
        if symbol in normalized:
            return code
    if _RUPEE_RE.search(normalized) or _RUPEE_SUFFIX_RE.search(normalized):
        return "LKR"
    return None

# Separators a real document uses between thousands groups. The space
# variants matter: SI style and many European locales use a space or a
# non-breaking space, and OCR renders both as ordinary whitespace or as
# U+00A0/U+202F depending on the font.
_SPACE_SEPARATORS = "    "


@dataclass(frozen=True)
class AmountParse:
    """One parsed money value.

    `value` is None when nothing could be parsed deterministically --
    never a guess. `raw` is always the string as it was read.
    """

    raw: str
    value: Decimal | None
    currency: str | None = None
    negative: bool = False
    ambiguous: bool = False
    reason: str = ""

    @property
    def ok(self) -> bool:
        return self.value is not None


def _strip_currency(text: str) -> tuple[str, str | None]:
    """Removes a leading or trailing currency token, returning the code."""
    cleaned = text.strip()
    upper = cleaned.upper()
    for token, code in _CURRENCY_TOKENS:
        if upper.startswith(token):
            return cleaned[len(token):].strip(), code
        if upper.endswith(token):
            return cleaned[: len(cleaned) - len(token)].strip(), code
    return cleaned, None


def _split_groups(digits: str, separator: str) -> list[str]:
    return [group for group in digits.split(separator) if group != ""]


def parse_amount(
    raw: str, *, assume_decimal_separator: str | None = None, locale: str | None = None
) -> AmountParse:
    """Parse a money string into a Decimal, or say why not.

    Handles, because real documents contain all of them:

    - `Rs. 41,252.70`, `LKR 41 252,70`, `$1,234.50`, `41252.70`
    - `5,000/=` and `5000/-` (Sri Lankan terminator)
    - `(1,234.50)` -- accounting negatives in parentheses
    - `1.234,56` -- comma as the decimal separator
    - `41 252.70` -- space as the thousands separator

    **The genuinely ambiguous case is flagged, not resolved.** A token
    like `1.234` is either one thousand two hundred thirty four with a
    European thousands separator, or 1.234 with a decimal point, and
    nothing in the token says which. Guessing is a 3-orders-of-magnitude
    error on an invoice total, so it comes back `ambiguous=True` with the
    more common reading (thousands) as `value`, and the caller is
    expected to cap the field at `low_confidence`.

    `assume_decimal_separator` ("." or ",") resolves that ambiguity
    directly; `locale` resolves it via the tenant's convention, which is
    the form a caller usually has. An explicit separator wins over a
    locale, and a locale that implies no separator leaves the ambiguity
    flagged rather than resolving it on a guess -- because a wrong
    resolution here is a 1000x error on an invoice total, which is worse
    than saying "unsure".
    """
    if assume_decimal_separator is None:
        assume_decimal_separator = decimal_separator_for(locale)
    if raw is None:
        return AmountParse(raw="", value=None, reason="no value")
    original = raw
    text = unicodedata.normalize("NFKC", str(raw)).strip()
    if not text:
        return AmountParse(raw=original, value=None, reason="empty")

    negative = False
    # Accounting parentheses. Checked before currency stripping because
    # "(Rs. 1,234)" puts the bracket outside the symbol.
    if text.startswith("(") and text.endswith(")"):
        negative = True
        text = text[1:-1].strip()

    text, currency = _strip_currency(text)
    text = _TRAILING_SUFFIX_RE.sub("", text)

    if text.startswith("-"):
        negative = True
        text = text[1:].strip()

    for space in _SPACE_SEPARATORS[1:]:
        text = text.replace(space, " ")

    if not re.fullmatch(r"[0-9 .,]+", text or ""):
        return AmountParse(
            raw=original, value=None, currency=currency, negative=negative,
            reason="contains characters that are not part of a number",
        )

    has_comma, has_dot = "," in text, "." in text
    has_space = " " in text
    ambiguous = False
    reason = ""

    if has_comma and has_dot:
        # Whichever appears last is the decimal separator; the other is
        # grouping. Unambiguous, and it covers both `1,234.56` and
        # `1.234,56` without knowing the locale.
        decimal_sep = "," if text.rfind(",") > text.rfind(".") else "."
        thousands_sep = "." if decimal_sep == "," else ","
        text = text.replace(thousands_sep, "").replace(" ", "")
        text = text.replace(decimal_sep, ".")
    elif has_comma or has_dot:
        separator = "," if has_comma else "."
        groups = _split_groups(text.replace(" ", ""), separator)
        occurrences = text.count(separator)
        if assume_decimal_separator is not None:
            if separator == assume_decimal_separator and occurrences == 1:
                text = text.replace(" ", "").replace(separator, ".")
            else:
                text = text.replace(separator, "").replace(" ", "")
        elif occurrences > 1:
            # `1.234.567` -- only grouping makes sense.
            text = text.replace(separator, "").replace(" ", "")
        elif len(groups) == 2 and len(groups[1]) == 3 and len(groups[0]) <= 3:
            # THE ambiguous shape: `1.234` / `41,252`. Three digits after
            # a single separator is exactly what a thousands group looks
            # like AND exactly what a 3-decimal-place value looks like.
            ambiguous = True
            reason = (
                f"'{groups[0]}{separator}{groups[1]}' is either a thousands separator "
                f"or a decimal point; read as thousands"
            )
            text = text.replace(separator, "").replace(" ", "")
        else:
            # 1, 2 or 4+ digits after the separator: a thousands group is
            # always exactly 3, so this is a decimal point.
            text = text.replace(" ", "").replace(separator, ".")
    else:
        # Only spaces, or nothing: spaces can only be grouping.
        text = text.replace(" ", "")

    if not text:
        return AmountParse(raw=original, value=None, currency=currency, reason="no digits")

    try:
        value = Decimal(text)
    except InvalidOperation:
        return AmountParse(
            raw=original, value=None, currency=currency, negative=negative,
            reason=f"{text!r} is not a number",
        )

    if negative:
        value = -value
    return AmountParse(
        raw=original, value=value, currency=currency, negative=negative,
        ambiguous=ambiguous, reason=reason,
    )


# ----------------------------------------------------------------------
# Amounts in words -- a cross-check, never a source
# ----------------------------------------------------------------------
_UNITS = {
    "zero": 0, "one": 1, "two": 2, "three": 3, "four": 4, "five": 5, "six": 6,
    "seven": 7, "eight": 8, "nine": 9, "ten": 10, "eleven": 11, "twelve": 12,
    "thirteen": 13, "fourteen": 14, "fifteen": 15, "sixteen": 16,
    "seventeen": 17, "eighteen": 18, "nineteen": 19,
}
_TENS = {
    "twenty": 20, "thirty": 30, "forty": 40, "fifty": 50,
    "sixty": 60, "seventy": 70, "eighty": 80, "ninety": 90,
}
_SCALES = {"hundred": 100, "thousand": 1_000, "lakh": 100_000, "lakhs": 100_000,
           "million": 1_000_000, "crore": 10_000_000, "billion": 1_000_000_000}
_WORD_NOISE = {"and", "only", "rupees", "rupee", "dollars", "dollar", "euros",
               "euro", "cents", "cent", "paisa", "paise", "sri", "lankan", "of"}


def amount_in_words(text: str) -> Decimal | None:
    """The integer value of an amount written in words, or None.

    Its only job is the cross-check section 3.8 asks for: an invoice that
    prints "Rupees Forty One Thousand Two Hundred Fifty Two Only" next to
    a numeric total is telling us the numeric read is right, and a
    mismatch is a signal the OCR misread a digit. It is deliberately NOT
    used as a value source -- words are far easier to misread than
    digits, and a wrong cross-check should never become the answer.

    Returns None rather than a partial number whenever it meets a token
    it does not understand, because a half-parsed amount is worse than no
    amount: it would produce a confident mismatch against a correct
    numeric value and flag a good field for review.
    """
    if not text:
        return None
    tokens = [
        token for token in re.findall(r"[a-z]+", text.lower())
        if token not in _WORD_NOISE
    ]
    if not tokens:
        return None

    total = Decimal(0)
    current = Decimal(0)
    seen_any = False
    for token in tokens:
        if token in _UNITS:
            current += _UNITS[token]
            seen_any = True
        elif token in _TENS:
            current += _TENS[token]
            seen_any = True
        elif token == "hundred":
            if not seen_any:
                return None
            current = (current or Decimal(1)) * 100
        elif token in _SCALES:
            if not seen_any:
                return None
            total += (current or Decimal(1)) * _SCALES[token]
            current = Decimal(0)
        else:
            # An unrecognized word means this is not (only) a number in
            # words, and a partial parse would be a false cross-check.
            return None
    if not seen_any:
        return None
    return total + current


# ----------------------------------------------------------------------
# Dates
# ----------------------------------------------------------------------
DAY_FIRST = "day_first"
MONTH_FIRST = "month_first"

# A locale bundles the two conventions that make the same characters mean
# different numbers and different days. Kept together because a document
# has one locale, not two: a tenant whose dates are day-first is
# overwhelmingly one whose decimal separator is a comma or a point
# consistently, and letting the two be configured independently invites a
# combination no real document uses.
DECIMAL_SEPARATOR_BY_LOCALE = {
    DAY_FIRST: None,     # unset: day-first is used in both '.' and ',' decimal regions
    MONTH_FIRST: ".",    # month-first is US convention, which is dot-decimal
}


def decimal_separator_for(locale: str | None) -> str | None:
    """The decimal separator a locale implies, or None when it implies
    none. None means `parse_amount` keeps flagging the ambiguous shape
    rather than resolving it on a guess."""
    if not locale:
        return None
    return DECIMAL_SEPARATOR_BY_LOCALE.get(locale)

_NUMERIC_DATE_RE = re.compile(r"^\s*(\d{1,4})\s*[/.\-]\s*(\d{1,2})\s*[/.\-]\s*(\d{1,4})\s*$")

_TEXTUAL_FORMATS = (
    "%d %B %Y", "%d %b %Y", "%B %d %Y", "%b %d %Y",
    "%d %B %y", "%d %b %y", "%B %d %y", "%b %d %y",
)


@dataclass(frozen=True)
class DateParse:
    raw: str
    iso: str | None
    ambiguous: bool = False
    reason: str = ""

    @property
    def ok(self) -> bool:
        return self.iso is not None


def parse_date(raw: str, *, locale: str = DAY_FIRST) -> DateParse:
    """Parse a date into ISO `YYYY-MM-DD`, flagging genuine ambiguity.

    `locale` is the tenant's convention -- `day_first` (the default, and
    correct for every real document this system has seen) or
    `month_first`. It was hardcoded before Phase 3, which section 3.8
    item 4 calls out by name.

    **A numeric date whose first two components are both <= 12 is
    genuinely ambiguous** -- `03/04/2026` is 3 April or 4 March and the
    token cannot say which. The locale decides how to read it, and the
    result is flagged so the field can be capped at `low_confidence`
    rather than presented as fact. Section 3.8: "mark genuinely ambiguous
    dates (`03/04/2026` with no other signal) as `low_confidence`".

    Unambiguous forms are never flagged: ISO (`2026-03-01`), a
    day > 12 (`25/03/2026`), or any month-name form.
    """
    if not raw or not str(raw).strip():
        return DateParse(raw=raw or "", iso=None, reason="empty")
    text = unicodedata.normalize("NFKC", str(raw)).strip().replace(",", "")

    match = _NUMERIC_DATE_RE.match(text)
    if match:
        first, second, third = (int(g) for g in match.groups())
        first_raw = match.group(1)

        # Year-first (ISO-ish) is unambiguous whatever the locale: a
        # four-digit leading component can only be a year, so the
        # remaining two are month then day in that order.
        if len(first_raw) == 4:
            return _build(raw, first, second, third, ambiguous=False)

        year = third if third > 99 else _expand_two_digit_year(third)
        both_plausible_months = first <= 12 and second <= 12
        if first > 12:
            day, month = first, second          # only one reading works
        elif second > 12:
            day, month = second, first
        elif locale == MONTH_FIRST:
            month, day = first, second
        else:
            day, month = first, second

        result = _build(raw, year, month, day, ambiguous=both_plausible_months)
        if result.ok and both_plausible_months:
            other = f"{year:04d}-{day:02d}-{month:02d}" if day <= 12 else None
            return DateParse(
                raw=result.raw, iso=result.iso, ambiguous=True,
                reason=(
                    f"day and month are both <= 12; read {locale.replace('_', '-')}"
                    + (f" (the other reading is {other})" if other else "")
                ),
            )
        return result

    for fmt in _TEXTUAL_FORMATS:
        try:
            parsed = datetime.strptime(text, fmt).date()
        except ValueError:
            continue
        return DateParse(raw=raw, iso=parsed.isoformat(), ambiguous=False)

    try:
        return DateParse(raw=raw, iso=date.fromisoformat(text).isoformat(), ambiguous=False)
    except ValueError:
        return DateParse(raw=raw, iso=None, reason="unrecognized date format")


def _expand_two_digit_year(year: int) -> int:
    """`26` -> 2026. A document dated in the 1900s is possible but a
    two-digit year on a live invoice is overwhelmingly this century, and
    the pivot is stated rather than left to strptime's own."""
    return 2000 + year if year < 70 else 1900 + year


def _build(raw: str, year: int, month: int, day: int, *, ambiguous: bool) -> DateParse:
    try:
        return DateParse(raw=raw, iso=date(year, month, day).isoformat(), ambiguous=ambiguous)
    except ValueError:
        return DateParse(raw=raw, iso=None, reason=f"{year:04d}-{month:02d}-{day:02d} is not a real date")
