"""Normalized document representation + chunking (structuring/document_model.py,
structuring/chunking.py). Pure functions over the OCR output contract's
`pages` array -- no model, no Docling, no PaddleOCR."""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "src"))

from ocr_worker.structuring.chunking import estimate_tokens, plan_chunks  # noqa: E402
from ocr_worker.structuring.document_model import build_normalized_document  # noqa: E402


def _block(text, x0, y0, x1, y1, confidence=0.99, label=None):
    block = {"text": text, "bbox": [x0, y0, x1, y1], "confidence": confidence}
    if label:
        block["label"] = label
    return block


def _ocr_page(blocks, number=1):
    return {"page_number": number, "method": "paddleocr", "text": "\n".join(b["text"] for b in blocks), "confidence": 0.97, "blocks": blocks, "tables": []}


EMPLOYEE_FORM = _ocr_page([
    _block("EMPLOYMENT CONTRACT", 400, 100, 1200, 160),
    _block("Employee Name:", 150, 300, 500, 340),
    _block("John Silva", 620, 302, 900, 338),
    _block("Employee ID: EMP-1023", 150, 360, 700, 400),
    _block("Department:", 150, 420, 420, 460),
    _block("Finance", 150, 470, 360, 510),
    _block("Joining Date", 150, 540, 460, 580),
    _block("2026-09-01", 620, 541, 900, 579),
])


def test_side_by_side_blocks_become_one_row_in_reading_order():
    doc = build_normalized_document([EMPLOYEE_FORM])
    texts = [line.text for line in doc.lines]
    assert texts == [
        "EMPLOYMENT CONTRACT",
        "Employee Name: | John Silva",
        "Employee ID: EMP-1023",
        "Department:",
        "Finance",
        "Joining Date | 2026-09-01",
    ]


def test_all_caps_ocr_line_is_a_heading_but_native_text_relies_on_docling_labels():
    ocr = build_normalized_document([EMPLOYEE_FORM])
    assert ocr.lines[0].kind == "heading"

    native = build_normalized_document([{
        "page_number": 1, "method": "native_text", "confidence": 1.0, "tables": [],
        "blocks": [_block("TERMS", 100, 100, 300, 140), _block("Payment Terms", 100, 200, 400, 240, label="section_header")],
    }])
    assert [l.kind for l in native.lines] == ["text", "heading"]


def test_key_value_candidates_cover_same_row_below_and_inline_labels():
    doc = build_normalized_document([EMPLOYEE_FORM])
    pairs = {(kv.label, kv.value, kv.relation) for kv in doc.key_values}
    assert ("Employee Name", "John Silva", "same_row") in pairs
    assert ("Employee ID", "EMP-1023", "inline") in pairs
    assert ("Department", "Finance", "below") in pairs
    # No colon, but a short label followed by a value box with digits.
    assert ("Joining Date", "2026-09-01", "same_row") in pairs


def test_serialized_units_keep_positions_and_only_cross_block_key_values():
    doc = build_normalized_document([EMPLOYEE_FORM])
    rendered = "\n".join(u.text for u in doc.units())
    assert "L2 @150,300: Employee Name: | John Silva" in rendered
    assert "L1 @400,100: ## EMPLOYMENT CONTRACT" in rendered
    assert 'L4->L5: "Department" = "Finance"' in rendered
    # Inline "Label: value" is already explicit in its own line -- not repeated as a hint.
    assert '"Employee ID" = "EMP-1023"' not in rendered


def test_low_confidence_lines_are_marked_and_bboxes_can_be_disabled():
    page = _ocr_page([_block("Total 1,250.00", 100, 100, 400, 140, confidence=0.41)])
    assert build_normalized_document([page]).units()[0].text.startswith("L1? @100,100:")
    assert build_normalized_document([page], include_bboxes=False).units()[0].text == "L1?: Total 1,250.00"


def test_long_prose_blocks_are_never_merged_into_a_row():
    prose = "x" * 120
    page = _ocr_page([_block(prose, 100, 100, 800, 140), _block("Right column", 900, 100, 1200, 140)])
    assert len(build_normalized_document([page]).lines) == 2


def _native_table_page():
    cells = [
        {"row": 0, "col": 0, "row_span": 1, "col_span": 1, "text": "Item", "bbox": [100, 500, 300, 540], "column_header": True, "row_header": False},
        {"row": 0, "col": 1, "row_span": 1, "col_span": 1, "text": "Qty", "bbox": [300, 500, 400, 540], "column_header": True, "row_header": False},
        {"row": 1, "col": 0, "row_span": 1, "col_span": 1, "text": "Widget", "bbox": [100, 540, 300, 580], "column_header": False, "row_header": False},
        {"row": 1, "col": 1, "row_span": 1, "col_span": 1, "text": "2", "bbox": [300, 540, 400, 580], "column_header": False, "row_header": False},
    ]
    blocks = [_block("Invoice No: INV-9", 100, 100, 600, 140)] + [
        {"text": c["text"], "bbox": c["bbox"], "confidence": 1.0} for c in cells
    ] + [_block("Thank you", 100, 900, 400, 940)]
    return {
        "page_number": 1, "method": "native_text", "confidence": 1.0, "blocks": blocks,
        "tables": [{"bbox": [100, 500, 400, 580], "num_rows": 2, "num_cols": 2, "cells": cells}],
    }


def test_tables_are_real_grids_placed_in_reading_order_and_not_duplicated_as_lines():
    doc = build_normalized_document([_native_table_page()])
    assert [l.text for l in doc.lines] == ["Invoice No: INV-9", "Thank you"]
    table = doc.tables[0]
    assert table.headers == ["Item", "Qty"] and table.rows == [["Widget", "2"]] and table.has_header
    kinds = [u.kind for u in doc.units()]
    assert kinds == ["line", "table", "line"]
    table_text = doc.units()[1].text
    assert "| Item | Qty |" in table_text and "| Widget | 2 |" in table_text


def test_malformed_table_is_repaired_and_flagged_not_dropped():
    raw = {
        "bbox": [0, 0, 10, 10], "num_rows": 1, "num_cols": 1,  # declared too small
        "cells": [
            {"row": 0, "col": 0, "text": "A", "column_header": True},
            {"row": 0, "col": 1, "text": "B", "column_header": True},
            {"row": 1, "col": 0, "text": "1"},
            {"row": 1, "col": 0, "text": "one"},  # collides with the previous cell
            {"row": None, "col": 3, "text": "lost"},
        ],
    }
    doc = build_normalized_document([{"page_number": 1, "method": "native_text", "blocks": [], "tables": [raw]}])
    table = doc.tables[0]
    assert table.headers == ["A", "B"]
    assert table.rows == [["1 one", ""]]
    assert table.malformed and doc.warnings[0]["code"] == "MALFORMED_TABLE"


def test_table_without_header_row_gets_placeholder_headers_that_are_not_grounding_source():
    raw = {"bbox": [0, 0, 10, 10], "num_rows": 1, "num_cols": 2, "cells": [{"row": 0, "col": 0, "text": "x"}, {"row": 0, "col": 1, "text": "y"}]}
    doc = build_normalized_document([{"page_number": 1, "method": "native_text", "blocks": [], "tables": [raw]}])
    unit = doc.units()[0]
    assert 'header="none-detected"' in unit.text
    assert unit.source_texts == ["x", "y"]


def test_empty_ocr_result_is_detected():
    assert build_normalized_document([_ocr_page([])]).is_empty
    assert build_normalized_document([]).is_empty
    assert not build_normalized_document([EMPLOYEE_FORM]).is_empty


def test_chunks_pack_pages_until_the_budget_then_split():
    pages = [_ocr_page([_block(f"Line {i} on page {p}", 100, 100 + 50 * i, 600, 140 + 50 * i) for i in range(10)], number=p) for p in range(1, 7)]
    doc = build_normalized_document(pages)
    single = plan_chunks(doc, budget_tokens=100_000, chars_per_token=3.0)
    assert len(single) == 1 and single[0].page_numbers == [1, 2, 3, 4, 5, 6]

    chunks = plan_chunks(doc, budget_tokens=300, chars_per_token=3.0)
    assert len(chunks) > 1
    for chunk in chunks:
        assert chunk.estimated_tokens <= 300 + 30  # page wrapper estimate slack
    # Every line appears exactly once across chunks.
    rendered = "\n".join(c.render() for c in chunks)
    assert all(rendered.count(f"Line {i} on page {p}") == 1 for p in range(1, 7) for i in range(10))


def test_oversized_table_is_split_by_rows_with_header_repeated():
    cells = [{"row": 0, "col": c, "text": h, "column_header": True} for c, h in enumerate(["Date", "Description", "Amount"])]
    for r in range(1, 81):
        cells += [
            {"row": r, "col": 0, "text": f"2026-01-{r % 28 + 1:02d}"},
            {"row": r, "col": 1, "text": f"Payment reference number {r}"},
            {"row": r, "col": 2, "text": f"{r * 10}.00"},
        ]
    page = {"page_number": 1, "method": "native_text", "blocks": [], "tables": [{"bbox": [0, 0, 10, 10], "num_rows": 81, "num_cols": 3, "cells": cells}]}
    chunks = plan_chunks(build_normalized_document([page]), budget_tokens=400, chars_per_token=3.0)
    assert len(chunks) > 1
    for chunk in chunks:
        text = chunk.render()
        assert "| Date | Description | Amount |" in text
        assert 'rows="' in text
    all_text = "\n".join(c.render() for c in chunks)
    assert all(f"Payment reference number {r} |" in all_text for r in range(1, 81))


def test_chunk_split_halves_units_and_single_table_rows():
    doc = build_normalized_document([EMPLOYEE_FORM])
    chunk = plan_chunks(doc, budget_tokens=100_000, chars_per_token=3.0)[0]
    first, second = chunk.split()
    assert len(first.units) + len(second.units) == len(chunk.units)
    assert first.depth == second.depth == 1

    table_doc = build_normalized_document([_native_table_page()])
    table_chunk = [c for c in plan_chunks(table_doc, budget_tokens=100_000, chars_per_token=3.0)][0]
    table_only = type(table_chunk)(units=[u for u in table_chunk.units if u.kind == "table"], pages=table_chunk.pages, chars_per_token=3.0)
    assert not table_only.can_split()  # a single data row can't be halved


def test_a_single_line_larger_than_the_budget_is_split_at_word_boundaries():
    words = " ".join(f"word{i}" for i in range(600))
    doc = build_normalized_document([_ocr_page([_block(words, 0, 0, 10, 10)])])
    chunks = plan_chunks(doc, budget_tokens=300, chars_per_token=3.0)
    assert len(chunks) > 1
    assert all(estimate_tokens(u.text, 3.0) <= 300 for c in chunks for u in c.units)
    assert "[part 1/" in chunks[0].render()


def test_native_pages_use_pymupdf_lines_with_docling_labels_instead_of_merged_paragraphs():
    # Real Docling behaviour: a borderless label/value region comes back as
    # ONE paragraph block; PyMuPDF keeps each visual line.
    page = {
        "page_number": 1, "method": "native_text", "confidence": 1.0, "tables": [],
        "blocks": [
            _block("EMPLOYMENT CONTRACT", 500, 150, 1100, 200, label="section_header"),
            _block("Employee Name: John Silva Employee ID: EMP-1023", 160, 360, 900, 450, label="text"),
        ],
        "lines": [
            {"text": "EMPLOYMENT CONTRACT", "bbox": [500, 150, 1100, 200]},
            {"text": "Employee Name:", "bbox": [160, 360, 400, 390]},
            {"text": "John Silva", "bbox": [600, 360, 800, 390]},
            {"text": "Employee ID:", "bbox": [160, 420, 400, 450]},
            {"text": "EMP-1023", "bbox": [600, 420, 800, 450]},
        ],
    }
    doc = build_normalized_document([page])
    assert [(l.kind, l.text) for l in doc.lines] == [
        ("heading", "EMPLOYMENT CONTRACT"),
        ("text", "Employee Name: | John Silva"),
        ("text", "Employee ID: | EMP-1023"),
    ]


def test_native_lines_inside_a_table_are_left_to_the_table_grid():
    page = _native_table_page()
    page["lines"] = [{"text": "Invoice No: INV-9", "bbox": [100, 100, 600, 140]}, {"text": "Widget", "bbox": [100, 540, 300, 580]}]
    doc = build_normalized_document([page])
    assert [l.text for l in doc.lines] == ["Invoice No: INV-9"]


def test_text_first_row_above_numeric_rows_is_inferred_as_the_header():
    cells = [
        {"row": 0, "col": 0, "text": "Component"}, {"row": 0, "col": 1, "text": "Monthly Amount (LKR)"},
        {"row": 1, "col": 0, "text": "Basic Salary"}, {"row": 1, "col": 1, "text": "120,000"},
    ]
    doc = build_normalized_document([{"page_number": 1, "method": "native_text", "blocks": [], "tables": [{"bbox": [0, 0, 9, 9], "num_rows": 2, "num_cols": 2, "cells": cells}]}])
    table = doc.tables[0]
    assert table.headers == ["Component", "Monthly Amount (LKR)"] and table.rows == [["Basic Salary", "120,000"]]
    assert 'header="inferred"' in doc.units()[0].text


def test_only_non_obvious_key_values_are_hinted():
    page = _ocr_page([
        _block("Name:", 100, 100, 250, 130), _block("John Silva", 300, 100, 500, 130),  # 2-part row: explicit already
        _block("Invoice No:", 100, 200, 250, 230), _block("INV-7", 300, 200, 400, 230), _block("Bill To:", 600, 200, 700, 230), _block("Oak", 750, 200, 850, 230),
        _block("Signature:", 100, 300, 250, 330), _block("Date: 25/08/2026", 600, 300, 900, 330),
    ])
    doc = build_normalized_document([page])
    hints = [u.text for u in doc.units() if u.kind == "kv"]
    assert hints == ['L2->L2: "Invoice No" = "INV-7"', 'L2->L2: "Bill To" = "Oak"']
    # A label is never paired with a segment that is its own "Label: value".
    assert not any(kv.label == "Signature" for kv in doc.key_values)
