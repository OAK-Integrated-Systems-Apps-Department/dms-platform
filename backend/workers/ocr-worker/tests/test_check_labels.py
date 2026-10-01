"""`python -m eval.check_labels`.

The rules here are not style preferences. Every ERROR is a value that
`metrics.field_matches` would score as a mismatch even when the pipeline
read the document perfectly -- so each test that asserts ERROR also
asserts, where it can, that the scorer really does reject the value. A
checker whose rules drift from the scorer's is worse than no checker: it
would send a reviewer to "fix" a label that was already right.
"""
from __future__ import annotations

import json

import pytest

from eval import check_labels
from eval.metrics import field_matches


def label(**overrides) -> dict:
    base = {
        "reviewed": True,
        "doc_id": "acme-invoice-001",
        "type_code": "INVOICE",
        "fields": {
            "invoice_number": "INV-4471",
            "invoice_date": "2026-04-03",
            "amount": "1234.50",
            "currency": "USD",
            "vendor_supplier_name": "Globex Corporation",
            "classification_level": "internal",
        },
    }
    base.update(overrides)
    return base


def findings_for(**overrides) -> list[check_labels.Finding]:
    return check_labels.check_label(label(**overrides), source="acme-invoice-001")


def errors(findings) -> list[check_labels.Finding]:
    return [f for f in findings if f.severity == check_labels.ERROR]


def warnings(findings) -> list[check_labels.Finding]:
    return [f for f in findings if f.severity == check_labels.WARN]


def fields_with(**changes) -> dict:
    merged = dict(label()["fields"])
    for key, value in changes.items():
        if value is check_labels:  # sentinel: delete the key
            merged.pop(key, None)
        else:
            merged[key] = value
    return merged


class TestAWellFormedLabel:
    def test_it_finds_nothing_wrong(self):
        assert findings_for() == []

    def test_a_deleted_optional_field_is_not_a_complaint(self):
        """A field the document does not contain is DELETED. That is the
        whole convention, so it must be silent."""
        assert findings_for(fields=fields_with(purchase_order_number=check_labels)) == []


class TestDates:
    def test_the_document_s_own_format_is_an_error(self):
        found = errors(findings_for(fields=fields_with(invoice_date="03/04/2026")))

        assert len(found) == 1
        # And it really would be scored wrong, which is the point.
        assert not field_matches("03/04/2026", "2026-04-03")

    def test_it_does_not_guess_which_number_is_the_month(self):
        message = errors(findings_for(fields=fields_with(invoice_date="03/04/2026")))[0].message

        # Both readings are offered. Picking one for the reviewer is how a
        # checker silently corrupts a label.
        assert "2026-04-03" in message
        assert "2026-03-04" in message

    def test_a_two_digit_year_is_named_as_such(self):
        message = errors(findings_for(fields=fields_with(invoice_date="03/04/26")))[0].message
        assert "two-digit year" in message

    def test_a_date_that_does_not_exist_is_an_error(self):
        assert errors(findings_for(fields=fields_with(invoice_date="2026-02-30")))

    def test_a_mis_keyed_year_is_a_warning_not_an_error(self):
        found = findings_for(fields=fields_with(invoice_date="0226-04-03"))

        assert not errors(found)
        assert warnings(found)

    def test_an_ordinary_iso_date_passes(self):
        assert findings_for(fields=fields_with(invoice_date="2026-12-31")) == []


class TestAmounts:
    def test_a_currency_symbol_is_an_error(self):
        found = errors(findings_for(fields=fields_with(amount="$1,234.50")))

        assert len(found) == 1
        assert "currency" in found[0].message
        assert not field_matches("$1,234.50", "1,234.50")

    def test_words_are_an_error(self):
        assert errors(findings_for(fields=fields_with(amount="one thousand")))

    def test_thousands_separators_are_only_a_warning(self):
        """The scorer compares numerically, so this really is correct --
        flagging it as an error would send the reviewer to change a label
        that already scores."""
        found = findings_for(fields=fields_with(amount="1,234.50"))

        assert not errors(found)
        assert warnings(found)
        assert field_matches("1,234.50", "1234.50")

    def test_a_json_number_is_a_warning_about_trailing_zeros(self):
        found = findings_for(fields=fields_with(amount=1234.5))

        assert not errors(found)
        assert "trailing zeros" in warnings(found)[0].message

    def test_a_negative_amount_is_allowed(self):
        assert findings_for(fields=fields_with(amount="-45.00")) == []

    def test_an_identifier_that_contains_digits_is_not_checked_as_a_number(self):
        """`purchase_order_number` is text. "PO-74300" must not be
        rejected for not being numeric."""
        assert findings_for(fields=fields_with(purchase_order_number="PO-74300")) == []


class TestCurrencyAndDropdowns:
    def test_an_unknown_currency_code_is_an_error(self):
        found = errors(findings_for(fields=fields_with(currency="CHF")))

        assert len(found) == 1
        assert "USD" in found[0].message

    def test_a_currency_symbol_in_the_currency_field_is_an_error(self):
        assert errors(findings_for(fields=fields_with(currency="$")))

    def test_wrong_case_is_a_warning_because_it_still_scores(self):
        found = findings_for(fields=fields_with(currency="usd"))

        assert not errors(found)
        assert warnings(found)
        assert field_matches("usd", "USD")

    def test_an_unknown_classification_is_an_error(self):
        assert errors(findings_for(fields=fields_with(classification_level="secret")))

    def test_a_dropdown_with_no_seeded_options_is_left_alone(self):
        """`cost_centre` is a dropdown that V30 seeds no options for.
        Inventing a list here would reject valid values."""
        assert findings_for(fields=fields_with(cost_centre="CC-1042")) == []


class TestMultiSelect:
    def test_a_comma_joined_string_is_an_error(self):
        found = errors(findings_for(fields=fields_with(tags="audited, renewal")))

        assert len(found) == 1
        assert '["audited", "renewal"]' in found[0].message
        # The trap this exists for: it looks right and never matches.
        assert not field_matches("audited, renewal", ["audited", "renewal"])

    def test_a_single_value_as_a_bare_string_is_only_a_warning(self):
        """It folds to the same string as a one-element list, so it really
        does score. Flagging it as an error would send the reviewer to
        change a label that was already right -- which is how a checker
        starts costing more than it saves."""
        found = findings_for(fields=fields_with(tags="audited"))

        assert not errors(found)
        assert len(warnings(found)) == 1
        assert field_matches("audited", ["audited"])

    def test_a_real_list_passes(self):
        assert findings_for(fields=fields_with(tags=["audited", "renewal"])) == []

    def test_the_json_encoded_form_is_only_a_warning(self):
        """It is what ocr-worker emits, so it scores -- see
        metrics.normalize_field_value."""
        found = findings_for(fields=fields_with(tags='["audited", "renewal"]'))

        assert not errors(found)
        assert warnings(found)
        assert field_matches('["audited", "renewal"]', ["audited", "renewal"])

    def test_non_string_entries_are_an_error(self):
        assert errors(findings_for(fields=fields_with(tags=["audited", 7])))


class TestTypeCode:
    def test_an_unknown_type_code_is_an_error(self):
        found = errors(findings_for(type_code="RECEIPT"))

        assert len(found) == 1
        assert found[0].field == "type_code"

    def test_a_field_from_another_type_is_an_error_naming_the_two_ways_out(self):
        found = errors(findings_for(fields=fields_with(contract_party="Globex")))

        assert len(found) == 1
        assert "type_code" in found[0].message

    def test_a_field_that_is_not_a_field_at_all_is_an_error(self):
        found = errors(findings_for(fields=fields_with(invoice_total="99.00")))

        assert len(found) == 1
        assert "not a field at all" in found[0].message

    def test_nothing_else_is_reported_once_the_type_is_wrong(self):
        """Every other rule is relative to the type, so reporting them
        against a type nobody chose is noise."""
        found = findings_for(type_code="RECEIPT", fields={"nonsense": "x", "invoice_date": "03/04/2026"})

        assert len(found) == 1


class TestMandatoryAndNull:
    def test_a_missing_mandatory_field_is_a_warning_not_an_error(self):
        """A mandatory field the document genuinely lacks is a real case
        -- it is what requires_review exists for."""
        found = findings_for(fields=fields_with(amount=check_labels))

        assert not errors(found)
        assert [f.field for f in warnings(found)] == ["amount"]

    def test_null_is_an_error_because_the_convention_is_deletion(self):
        found = errors(findings_for(fields=fields_with(currency=None)))

        assert len(found) == 1
        assert "DELETED" in found[0].message

    def test_an_empty_string_is_an_error_for_the_same_reason(self):
        assert errors(findings_for(fields=fields_with(vendor_supplier_name="")))

    def test_a_label_with_no_fields_at_all_is_an_error(self):
        found = errors(check_labels.check_label(
            {"reviewed": True, "doc_id": "x", "type_code": "INVOICE"}, source="x"))

        assert len(found) == 1
        assert found[0].field == "fields"


class TestLineItems:
    def test_absent_line_items_are_fine(self):
        assert findings_for() == []

    def test_a_well_formed_line_passes(self):
        found = findings_for(line_items=[
            {"description": "Widget", "quantity": "2", "unit_price": "10.00", "line_total": "20.00"},
        ])
        assert found == []

    def test_a_non_numeric_quantity_is_an_error(self):
        found = errors(findings_for(line_items=[{"description": "Widget", "quantity": "two"}]))

        assert len(found) == 1
        assert found[0].field == "line_items[0].quantity"

    def test_an_unknown_key_is_an_error(self):
        found = errors(findings_for(line_items=[{"description": "Widget", "vat": "2.00"}]))

        assert len(found) == 1
        assert "vat" in found[0].message

    def test_arithmetic_that_does_not_add_up_is_a_warning(self):
        """The one error a reviewer cannot catch by reading."""
        found = findings_for(line_items=[
            {"description": "Widget", "quantity": "2", "unit_price": "10.00", "line_total": "25.00"},
        ])

        assert not errors(found)
        assert "25.00" in warnings(found)[0].message

    def test_a_cent_of_rounding_is_the_document_s_own(self):
        found = findings_for(line_items=[
            {"description": "Widget", "quantity": "3", "unit_price": "3.33", "line_total": "10.00"},
        ])
        assert found == []

    def test_line_items_must_be_a_list(self):
        assert errors(findings_for(line_items={"description": "Widget"}))


class TestDraftLeftovers:
    def test_draft_scaffolding_in_a_reviewed_label_is_a_warning(self):
        found = findings_for(_README=["draft"], _draft_notes={"amount": {}})

        assert not errors(found)
        assert len(warnings(found)) == 1


class TestItNeverWrites:
    """The one property that must never regress. A tool that 'helpfully
    fixed' a label would be editing ground truth to agree with a machine,
    which is what this whole harness exists to prevent."""

    def _corpus(self, tmp_path, monkeypatch, labels: dict[str, dict]):
        directory = tmp_path / "labels" / "real"
        directory.mkdir(parents=True)
        for name, body in labels.items():
            (directory / f"{name}.json").write_text(json.dumps(body, indent=2), encoding="utf-8")
        monkeypatch.setattr(check_labels.realset, "REAL_LABELS_DIR", directory)
        return directory

    def test_a_run_over_broken_labels_changes_no_byte_of_them(self, tmp_path, monkeypatch, capsys):
        directory = self._corpus(tmp_path, monkeypatch, {
            "good": label(),
            "bad": label(doc_id="bad", fields=fields_with(
                invoice_date="03/04/2026", amount="$1,234.50", currency="CHF", tags="a, b")),
        })
        before = {p.name: (p.read_bytes(), p.stat().st_mtime_ns) for p in directory.glob("*.json")}

        exit_code = check_labels.main([])

        assert exit_code == 1, "it found the broken label"
        after = {p.name: (p.read_bytes(), p.stat().st_mtime_ns) for p in directory.glob("*.json")}
        assert after == before
        assert sorted(p.name for p in directory.glob("*")) == ["bad.json", "good.json"]

    def test_clean_labels_exit_zero(self, tmp_path, monkeypatch, capsys):
        self._corpus(tmp_path, monkeypatch, {"good": label()})

        assert check_labels.main([]) == 0
        assert "OK" in capsys.readouterr().out

    def test_warnings_alone_do_not_fail_unless_strict(self, tmp_path, monkeypatch, capsys):
        self._corpus(tmp_path, monkeypatch, {
            "warn": label(fields=fields_with(amount="1,234.50")),
        })

        assert check_labels.main([]) == 0
        assert check_labels.main(["--strict"]) == 1

    def test_an_unreviewed_draft_is_not_scored_against(self, tmp_path, monkeypatch, capsys):
        """A draft is the pipeline's own output; complaining about it by
        default would bury the reviewed labels the reviewer cares about."""
        self._corpus(tmp_path, monkeypatch, {
            "draft": label(reviewed=False, fields=fields_with(invoice_date="03/04/2026")),
        })

        assert check_labels.main([]) == 0
        assert "0 reviewed" in capsys.readouterr().out

        assert check_labels.main(["--include-pending"]) == 0
        assert "information only" in capsys.readouterr().out

    def test_unreadable_json_is_reported_not_raised(self, tmp_path, monkeypatch, capsys):
        directory = tmp_path / "labels" / "real"
        directory.mkdir(parents=True)
        (directory / "broken.json").write_text("{not json", encoding="utf-8")
        monkeypatch.setattr(check_labels.realset, "REAL_LABELS_DIR", directory)

        assert check_labels.main([]) == 1
        assert "could not be read" in capsys.readouterr().out

    def test_no_label_directory_is_not_a_failure(self, tmp_path, monkeypatch, capsys):
        monkeypatch.setattr(check_labels.realset, "REAL_LABELS_DIR", tmp_path / "nope")

        assert check_labels.main([]) == 0


@pytest.mark.parametrize("field", ["invoice_date", "amount", "currency", "classification_level"])
def test_every_checked_field_actually_belongs_to_the_type(field):
    """Guards the fixture itself: a test suite built on a label the real
    schema would reject proves nothing."""
    allowed = {key for key, _ in check_labels.schema_mod.TYPE_FIELDS["INVOICE"]}
    assert field in allowed
