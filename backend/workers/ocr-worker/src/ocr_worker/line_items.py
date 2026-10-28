"""Invoice line items (production prompt section 3.8).

Reads the structured table grids Docling's TableFormer already produces
(`pages[].tables`, see pipeline's `_merge_native_text_and_tables`) and
turns the one that looks like a line-items table into typed rows.

**Built on the table grid, not on a regex over text.** A line items block
is the one part of an invoice whose meaning is carried entirely by
column position -- `12  10.50  126.00` means nothing without knowing
which column is which, and the same three numbers in a different order
are a different invoice. Matching text patterns would be guessing at that
layout; the grid states it.

Two consequences follow honestly from that choice and are documented
rather than worked around:

* **A page whose table was not detected yields nothing here.** That
  includes every landscape page (carried-forward item 11: a real ruled
  table on a landscape page comes back as 0 tables) and every page whose
  table pass was abandoned on its deadline. Returning nothing is correct
  -- inventing lines from loose text would be worse than saying the
  table was not read.
* **Scanned invoices yield nothing today**, because TableFormer needs a
  text layer and a PaddleOCR'd page has none. Scanned-table recognition
  is explicitly later work, behind a flag.
"""
from __future__ import annotations

import logging
import re
from dataclasses import dataclass, field as dataclass_field
from decimal import Decimal

from . import field_confidence as fc
from .patterns import parse_amount

logger = logging.getLogger(__name__)

# Header synonyms, per column. Matched against a normalized header cell.
# Deliberately explicit, never fuzzy: mistaking a "Discount" column for
# "Unit Price" silently changes every number on the invoice.
_DESCRIPTION_HEADERS = (
    "description", "item", "particulars", "details", "product", "service",
    "item description", "goods", "narration",
)
_QUANTITY_HEADERS = ("qty", "quantity", "units", "no of units", "nos", "count")
_UNIT_PRICE_HEADERS = (
    "unit price", "rate", "price", "unit rate", "unit cost", "price per unit", "cost",
)
_LINE_TOTAL_HEADERS = (
    "amount", "total", "line total", "value", "net amount", "extended", "subtotal",
)

# A table needs at least this many columns to be a line-items table.
# Two columns is a key/value summary block (the "Subtotal / 35,000.00"
# panel that sits under the real table), not a line-items grid, and
# reading one as the other produces confident nonsense.
MIN_COLUMNS = 3
MIN_BODY_ROWS = 1


@dataclass
class LineItem:
    line_number: int
    description: str | None = None
    quantity: Decimal | None = None
    unit_price: Decimal | None = None
    line_total: Decimal | None = None
    raw_cells: dict = dataclass_field(default_factory=dict)
    confidence: float | None = None
    page: int | None = None
    bbox: tuple[int, int, int, int] | None = None

    def to_dict(self) -> dict:
        return {
            "line_number": self.line_number,
            "description": self.description,
            "quantity": str(self.quantity) if self.quantity is not None else None,
            "unit_price": str(self.unit_price) if self.unit_price is not None else None,
            "line_total": str(self.line_total) if self.line_total is not None else None,
            "raw_cells": self.raw_cells,
            "confidence": self.confidence,
            "page": self.page,
            "bbox": list(self.bbox) if self.bbox else None,
        }

    def for_validation(self) -> dict:
        """The shape `validation.validate_money(line_items=...)` expects."""
        return {
            "quantity": self.quantity,
            "unit_price": self.unit_price,
            "line_total": self.line_total,
        }


def _normalize_header(text: str) -> str:
    return re.sub(r"[^a-z0-9 ]+", " ", (text or "").strip().lower()).strip()


def _match_column(header: str, synonyms: tuple[str, ...]) -> bool:
    normalized = _normalize_header(header)
    if not normalized:
        return False
    # Exact first, then containment. Containment alone would match
    # "Unit Price" for the "price" synonym AND for "unit price", so exact
    # wins to keep the most specific reading.
    if normalized in synonyms:
        return True
    return any(s == normalized or (len(s) > 3 and s in normalized) for s in synonyms)


def _column_map(header_cells: list[str]) -> dict[str, int]:
    """Column index per logical field, from the header row.

    A column is claimed by at most one field, first match wins in the
    order description -> quantity -> unit price -> line total. Without
    that, "Amount" would be claimed by both unit price (via "price"...
    no) and line total, and a column claimed twice means one of the two
    fields reads the wrong number on every row.
    """
    mapping: dict[str, int] = {}
    taken: set[int] = set()
    for name, synonyms in (
        ("description", _DESCRIPTION_HEADERS),
        ("quantity", _QUANTITY_HEADERS),
        ("unit_price", _UNIT_PRICE_HEADERS),
        ("line_total", _LINE_TOTAL_HEADERS),
    ):
        for index, cell in enumerate(header_cells):
            if index in taken:
                continue
            if _match_column(cell, synonyms):
                mapping[name] = index
                taken.add(index)
                break
    return mapping


def _grid(table: dict) -> list[list[str]]:
    """The table's cells as a dense row-major grid of strings."""
    rows = int(table.get("num_rows") or 0)
    cols = int(table.get("num_cols") or 0)
    if rows <= 0 or cols <= 0:
        return []
    grid = [["" for _ in range(cols)] for _ in range(rows)]
    for cell in table.get("cells") or []:
        r, c = cell.get("row"), cell.get("col")
        if r is None or c is None or not (0 <= r < rows and 0 <= c < cols):
            continue
        grid[r][c] = (cell.get("text") or "").strip()
    return grid


def _looks_like_a_total_row(cells: list[str]) -> bool:
    """A trailing Subtotal/Tax/Total row is part of the table's rendering,
    not a line item. Including it would double-count the invoice's own
    total as a line and break `sum(line_total) == subtotal` on every
    document that formats this way -- which is most of them."""
    joined = " ".join(c for c in cells if c).strip().lower()
    if not joined:
        return False
    return bool(re.match(
        r"^(sub\s*total|subtotal|total|grand\s*total|tax|vat|gst|amount\s+due|balance)\b",
        joined,
    ))


def extract_from_tables(
    tables_by_page: dict[int, list[dict]],
    *,
    locale: str = "day_first",
    page_confidence: dict[int, float] | None = None,
) -> list[LineItem]:
    """Line items from the best line-items-looking table in the document.

    Only ONE table is used. An invoice has one line-items grid; the other
    tables on the page are summary panels and address blocks, and
    concatenating them would mix a "Subtotal 35,000.00" row into the
    lines. The best candidate is the one with the most usable body rows.
    """
    best: tuple[int, list[LineItem]] = (0, [])

    for page, tables in sorted((tables_by_page or {}).items()):
        for table in tables or []:
            grid = _grid(table)
            if len(grid) < 1 + MIN_BODY_ROWS or len(grid[0]) < MIN_COLUMNS:
                continue
            mapping = _column_map(grid[0])
            # A line-items table must have at least a description AND one
            # number. A grid with neither is a layout table, not data.
            if "description" not in mapping:
                continue
            if not ({"quantity", "unit_price", "line_total"} & set(mapping)):
                continue

            items = _rows_to_items(
                grid[1:], mapping, page=page, table=table, locale=locale,
                page_conf=(page_confidence or {}).get(page),
            )
            if len(items) > best[0]:
                best = (len(items), items)

    if best[1]:
        logger.info("Extracted %d invoice line item(s)", len(best[1]))
    return best[1]


def _rows_to_items(body, mapping, *, page, table, locale, page_conf) -> list[LineItem]:
    items: list[LineItem] = []
    for cells in body:
        if _looks_like_a_total_row(cells):
            continue
        raw = {
            name: (cells[index] if index < len(cells) else "")
            for name, index in mapping.items()
        }
        if not any((v or "").strip() for v in raw.values()):
            continue

        quantity = _decimal(raw.get("quantity"), locale)
        unit_price = _decimal(raw.get("unit_price"), locale)
        line_total = _decimal(raw.get("line_total"), locale)
        description = (raw.get("description") or "").strip() or None

        # A row with a description and no numbers at all is a continuation
        # of the previous line's description (a wrapped cell), not a line.
        if description and quantity is None and unit_price is None and line_total is None:
            if items and items[-1].description:
                items[-1].description = f"{items[-1].description} {description}".strip()
            continue

        items.append(LineItem(
            line_number=len(items),
            description=description,
            quantity=quantity,
            unit_price=unit_price,
            line_total=line_total,
            raw_cells=raw,
            # A table cell comes from the native text layer on every path
            # that can currently produce one, so the OCR term is 1.0 --
            # `page_conf` is carried for the day a scanned table is read.
            confidence=fc.compute(
                ocr_confidence=page_conf,
                tier=fc.TIER_PATTERN,
            ),
            page=page,
            bbox=tuple(table["bbox"]) if table.get("bbox") else None,
        ))
    return items


def _decimal(raw: str | None, locale: str) -> Decimal | None:
    if not raw or not str(raw).strip():
        return None
    parsed = parse_amount(str(raw), locale=locale)
    return parsed.value if parsed is not None else None
