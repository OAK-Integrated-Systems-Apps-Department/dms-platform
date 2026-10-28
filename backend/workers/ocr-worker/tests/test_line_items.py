"""Invoice line items (Phase 3e, production prompt 3.8)."""
from __future__ import annotations

from decimal import Decimal

import pytest

from ocr_worker.line_items import extract_from_tables


def _cell(row, col, text):
    return {"row": row, "col": col, "text": text}


def _table(header, rows, bbox=(10, 10, 500, 200)):
    cells = [_cell(0, c, h) for c, h in enumerate(header)]
    for r, row in enumerate(rows, start=1):
        cells.extend(_cell(r, c, v) for c, v in enumerate(row))
    return {
        "num_rows": len(rows) + 1, "num_cols": len(header),
        "bbox": list(bbox), "cells": cells,
    }


STANDARD = _table(
    ["Description", "Qty", "Unit Price", "Amount"],
    [
        ["Archive Storage Boxes", "12", "10.50", "126.00"],
        ["Document Shredding", "4", "35.00", "140.00"],
    ],
)


def test_it_reads_description_quantity_unit_price_and_line_total():
    items = extract_from_tables({1: [STANDARD]})
    assert len(items) == 2
    first = items[0]
    assert first.description == "Archive Storage Boxes"
    assert first.quantity == Decimal("12")
    assert first.unit_price == Decimal("10.50")
    assert first.line_total == Decimal("126.00")
    assert first.line_number == 0
    assert first.page == 1


def test_the_raw_cells_are_kept_alongside_the_parsed_numbers():
    """Same rule as a field's raw_value: the parse is where information is
    lost, so its input is what a reviewer needs."""
    items = extract_from_tables({1: [STANDARD]})
    assert items[0].raw_cells["unit_price"] == "10.50"


@pytest.mark.parametrize("header", [
    ["Item", "Quantity", "Rate", "Total"],
    ["Particulars", "Nos", "Unit Cost", "Value"],
    ["Product", "QTY", "Price", "Net Amount"],
])
def test_common_header_synonyms_are_understood(header):
    items = extract_from_tables({1: [_table(header, [["Widget", "2", "5.00", "10.00"]])]})
    assert len(items) == 1
    assert items[0].quantity == Decimal("2")
    assert items[0].line_total == Decimal("10.00")


def test_a_trailing_total_row_is_not_a_line_item():
    """Including it would double-count the invoice's own total and break
    `sum(line_total) == subtotal` on every document that formats this way."""
    table = _table(
        ["Description", "Qty", "Unit Price", "Amount"],
        [["Widget", "2", "5.00", "10.00"], ["Subtotal", "", "", "10.00"]],
    )
    items = extract_from_tables({1: [table]})
    assert [i.description for i in items] == ["Widget"]


@pytest.mark.parametrize("label", ["Subtotal", "Total", "Grand Total", "VAT", "Tax", "Balance Due"])
def test_every_summary_row_label_is_excluded(label):
    table = _table(
        ["Description", "Qty", "Unit Price", "Amount"],
        [["Widget", "2", "5.00", "10.00"], [label, "", "", "10.00"]],
    )
    assert len(extract_from_tables({1: [table]})) == 1


def test_a_wrapped_description_joins_the_line_above_it():
    """A cell that wrapped onto a second grid row has a description and no
    numbers. It is a continuation, not a line with everything missing."""
    table = _table(
        ["Description", "Qty", "Unit Price", "Amount"],
        [["Courier Collection", "2", "18.25", "36.50"], ["and weekend surcharge", "", "", ""]],
    )
    items = extract_from_tables({1: [table]})
    assert len(items) == 1
    assert items[0].description == "Courier Collection and weekend surcharge"


def test_a_two_column_summary_panel_is_not_a_line_items_table():
    """`Subtotal | 35,000.00` is a summary block that sits under the real
    table. Reading it as line items produces confident nonsense."""
    panel = _table(["Description", "Amount"], [["Subtotal", "35,000.00"]])
    assert extract_from_tables({1: [panel]}) == []


def test_a_table_with_no_description_column_is_ignored():
    table = _table(["Qty", "Unit Price", "Amount"], [["2", "5.00", "10.00"]])
    assert extract_from_tables({1: [table]}) == []


def test_a_table_with_a_description_but_no_numbers_is_ignored():
    table = _table(["Description", "Notes", "Remarks"], [["Widget", "a", "b"]])
    assert extract_from_tables({1: [table]}) == []


def test_only_the_best_table_is_used_never_several_concatenated():
    """An invoice has ONE line-items grid; the others are summary panels
    and address blocks, and concatenating them mixes a total into the
    lines."""
    small = _table(["Description", "Qty", "Unit Price", "Amount"], [["One", "1", "1.00", "1.00"]])
    items = extract_from_tables({1: [small], 2: [STANDARD]})
    assert len(items) == 2
    assert items[0].description == "Archive Storage Boxes"


def test_no_tables_at_all_yields_nothing_rather_than_guessing():
    """A page whose table was never detected -- every landscape page today
    (carried-forward item 11) -- yields nothing. Inventing lines from
    loose text would be worse than reporting the table was not read."""
    assert extract_from_tables({}) == []
    assert extract_from_tables({1: []}) == []


def test_line_numbers_are_contiguous_after_rows_are_skipped():
    table = _table(
        ["Description", "Qty", "Unit Price", "Amount"],
        [["A", "1", "1.00", "1.00"], ["", "", "", ""], ["B", "2", "2.00", "4.00"], ["Total", "", "", "5.00"]],
    )
    items = extract_from_tables({1: [table]})
    assert [i.line_number for i in items] == [0, 1]
    assert [i.description for i in items] == ["A", "B"]


def test_every_line_carries_a_computed_confidence():
    items = extract_from_tables({1: [STANDARD]})
    assert all(0 < i.confidence <= 1 for i in items)


def test_for_validation_produces_the_shape_validate_money_expects():
    from ocr_worker.validation import validate_money

    items = extract_from_tables({1: [STANDARD]})
    result = validate_money(
        subtotal=Decimal("266.00"),
        line_totals=[i.line_total for i in items],
        line_items=[i.for_validation() for i in items],
    )
    assert result.ok, result.to_dict()


def test_a_line_whose_arithmetic_disagrees_is_caught_end_to_end():
    from ocr_worker.validation import validate_money

    table = _table(["Description", "Qty", "Unit Price", "Amount"], [["Widget", "3", "5.00", "99.00"]])
    items = extract_from_tables({1: [table]})
    result = validate_money(line_items=[i.for_validation() for i in items])
    assert not result.ok
    assert result.errors[0].rule == "quantity_times_unit_price_equals_line_total"
