"""Cross-field validation (production prompt section 3.9).

The governing rule, stated there and enforced here by construction:

> a failing rule sets the involved fields to `low_confidence` with a
> `validation_errors` entry, **never drops them**.

So nothing in this module deletes, edits or "corrects" a value. It
returns findings. A value that fails every rule still reaches the user,
still carries its raw OCR string, and is merely marked as something a
human should look at -- which is the whole point of a review queue. A
validator that silently dropped a field would turn a visible wrong value
into an invisible missing one, which is strictly worse: the user cannot
correct what they cannot see.

Pure: no I/O, no models, no database. `Decimal` throughout, because
money compared as float is a bug waiting for the right pair of values.
"""

from __future__ import annotations

from dataclasses import dataclass, field as dataclass_field
from decimal import Decimal

from .patterns import parse_date

# Money is compared with a tolerance because OCR reads what is printed,
# and what is printed is already rounded: a line of 3 x 96.00 at 15% tax
# does not re-derive to the cent from the numbers on the page. 0.02 is
# two minor units -- enough for the rounding a document does to itself,
# far too small to hide a misread digit (which moves a value by at least
# a whole unit, usually orders of magnitude).
DEFAULT_MONEY_TOLERANCE = Decimal("0.02")

# A date outside this range is a misread, not a document. Invoices dated
# 1900 or 2126 exist only as OCR errors on a 4-digit year.
MIN_PLAUSIBLE_YEAR = 1990
MAX_PLAUSIBLE_YEAR = 2100


@dataclass(frozen=True)
class ValidationError:
    """One failed rule.

    `fields` is every field the rule involved, not just the one that
    looks wrong -- when `subtotal + tax != total` there is no way to know
    which of the three was misread, so all three are flagged and a human
    decides. Blaming one would be a guess presented as a diagnosis.
    """

    rule: str
    message: str
    fields: tuple[str, ...]

    def to_dict(self) -> dict:
        return {"rule": self.rule, "message": self.message, "fields": list(self.fields)}


@dataclass
class ValidationResult:
    errors: list[ValidationError] = dataclass_field(default_factory=list)

    @property
    def ok(self) -> bool:
        return not self.errors

    def fields_to_flag(self) -> set[str]:
        return {name for error in self.errors for name in error.fields}

    def to_dict(self) -> list[dict]:
        return [error.to_dict() for error in self.errors]


def _close(a: Decimal, b: Decimal, tolerance: Decimal) -> bool:
    return abs(a - b) <= tolerance


def validate_money(
    *,
    subtotal: Decimal | None = None,
    tax_amount: Decimal | None = None,
    total: Decimal | None = None,
    line_totals: list[Decimal] | None = None,
    line_items: list[dict] | None = None,
    tolerance: Decimal = DEFAULT_MONEY_TOLERANCE,
) -> ValidationResult:
    """Section 3.9's arithmetic rules.

    Every rule is skipped when its inputs are absent. A missing subtotal
    is a `not_found` field, which is already reported as such -- turning
    it into a *validation failure* as well would flag the same gap twice
    and train whoever reads these to ignore them.
    """
    result = ValidationResult()

    if subtotal is not None and tax_amount is not None and total is not None:
        expected = subtotal + tax_amount
        if not _close(expected, total, tolerance):
            result.errors.append(ValidationError(
                rule="subtotal_plus_tax_equals_total",
                message=f"subtotal {subtotal} + tax {tax_amount} = {expected}, but total reads {total}",
                fields=("subtotal", "tax_amount", "total"),
            ))

    if line_totals and subtotal is not None:
        summed = sum(line_totals, Decimal(0))
        if not _close(summed, subtotal, tolerance):
            result.errors.append(ValidationError(
                rule="line_totals_equal_subtotal",
                message=f"line totals sum to {summed}, but subtotal reads {subtotal}",
                fields=("subtotal", "line_items"),
            ))

    for index, item in enumerate(line_items or []):
        quantity, unit_price, line_total = item.get("quantity"), item.get("unit_price"), item.get("line_total")
        if quantity is None or unit_price is None or line_total is None:
            continue
        expected = Decimal(quantity) * Decimal(unit_price)
        if not _close(expected, Decimal(line_total), tolerance):
            result.errors.append(ValidationError(
                rule="quantity_times_unit_price_equals_line_total",
                message=(
                    f"line {index + 1}: {quantity} x {unit_price} = {expected}, "
                    f"but the line total reads {line_total}"
                ),
                fields=("line_items",),
            ))
    return result


def validate_dates(
    values: dict[str, str | None],
    *,
    locale: str = "day_first",
    pairs: tuple[tuple[str, str], ...] = (
        ("invoice_date", "due_date"),
        ("effective_date", "expiry_date"),
        ("contract_start_date", "contract_end_date"),
    ),
) -> ValidationResult:
    """Parseability, plausible range, and ordering.

    Ordering is checked only for pairs where the order is a *fact about
    the world* -- a due date cannot precede its invoice, a contract
    cannot end before it starts. There is deliberately no generic "later
    date must come second" rule, because plenty of legitimate date pairs
    have no required order and inventing one would flag correct documents.
    """
    result = ValidationResult()
    parsed: dict[str, str] = {}

    for name, raw in values.items():
        if raw is None or not str(raw).strip():
            continue
        outcome = parse_date(str(raw), locale=locale)
        if not outcome.ok:
            result.errors.append(ValidationError(
                rule="date_unparseable",
                message=f"{name}: {raw!r} is not a date this parser recognizes",
                fields=(name,),
            ))
            continue
        year = int(outcome.iso[:4])
        if not (MIN_PLAUSIBLE_YEAR <= year <= MAX_PLAUSIBLE_YEAR):
            result.errors.append(ValidationError(
                rule="date_out_of_plausible_range",
                message=f"{name}: {outcome.iso} is outside {MIN_PLAUSIBLE_YEAR}-{MAX_PLAUSIBLE_YEAR}",
                fields=(name,),
            ))
            continue
        parsed[name] = outcome.iso

    for earlier, later in pairs:
        if earlier in parsed and later in parsed and parsed[later] < parsed[earlier]:
            result.errors.append(ValidationError(
                rule="date_order",
                message=f"{later} ({parsed[later]}) is before {earlier} ({parsed[earlier]})",
                fields=(earlier, later),
            ))
    return result


def validate_currency(value: str | None, options: list[str] | None, *, field_name: str = "currency") -> ValidationResult:
    """The value must be one of the field's configured options.

    No options configured means no rule -- an unconstrained field cannot
    fail a constraint. Comparison is case-insensitive and
    whitespace-trimmed, because `eur ` and `EUR` are the same answer and
    flagging the difference would be pedantry, not validation.
    """
    result = ValidationResult()
    if value is None or not options:
        return result
    normalized = str(value).strip().upper()
    if normalized and normalized not in {str(option).strip().upper() for option in options}:
        result.errors.append(ValidationError(
            rule="currency_not_in_options",
            message=f"{field_name} {value!r} is not one of {options}",
            fields=(field_name,),
        ))
    return result


def validate_mandatory(values: dict[str, object], mandatory: list[str] | None) -> ValidationResult:
    """Feeds the existing "highlight the failed field, prompt manual
    correction" flow. A mandatory field that is absent, empty or
    whitespace counts as missing."""
    result = ValidationResult()
    for name in mandatory or []:
        value = values.get(name)
        if value is None or (isinstance(value, str) and not value.strip()) or value == []:
            result.errors.append(ValidationError(
                rule="mandatory_field_missing",
                message=f"{name} is mandatory for this document type and was not extracted",
                fields=(name,),
            ))
    return result


def cross_check_amount_in_words(
    numeric: Decimal | None, words_value: Decimal | None, *, field_name: str = "total"
) -> ValidationResult:
    """Section 3.8's "amounts in words as a cross-check".

    Compares only the INTEGER part, because the words on a real document
    are written against the whole units -- "Forty One Thousand Two
    Hundred Fifty Two Only" alongside `41,252.70`. Demanding the cents
    match would fail every correct document that uses this convention,
    which is most of them.
    """
    result = ValidationResult()
    if numeric is None or words_value is None:
        return result
    if int(numeric) != int(words_value):
        result.errors.append(ValidationError(
            rule="amount_in_words_mismatch",
            message=f"{field_name} reads {numeric}, but the amount in words reads {words_value}",
            fields=(field_name,),
        ))
    return result


def combine(*results: ValidationResult) -> ValidationResult:
    combined = ValidationResult()
    for result in results:
        combined.errors.extend(result.errors)
    return combined
