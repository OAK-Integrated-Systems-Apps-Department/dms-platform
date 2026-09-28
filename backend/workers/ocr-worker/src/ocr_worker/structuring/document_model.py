"""Normalized document representation: the layer between OCR output and the
LLM prompt.

Input is exactly the OCR output contract's `pages` array (README "Output
contract") -- PaddleOCR line blocks for scanned pages, Docling paragraph
blocks (+ their `label`) and TableFormer grids for native-text pages. The
model input is NOT the flattened `text`: it keeps, per page,

- visual rows in reading order, with the top-left position of each row
  (blocks sitting side by side on one row become ` | `-separated segments,
  which is how a label and its value usually relate on a form),
- headings / page headers / footers / list items (Docling's labels, or a
  conservative all-caps heuristic for OCR pages that have no labels),
- tables as real header + row/column grids (malformed grids repaired and
  flagged rather than dropped),
- key/value candidates where the label and value are separate blocks
  (label and value on the same row, or the value directly below its label),
- a low-OCR-confidence marker per line.

Everything is emitted as `Unit`s -- the atoms chunking.py packs into
model-sized chunks without ever splitting a line and while keeping tables
whole where possible.
"""
from __future__ import annotations

import math
import re
from dataclasses import dataclass, field

HEADING_LABELS = frozenset({"title", "section_header"})

# A block longer than this is prose, never merged side by side with its
# neighbours into one row (that would interleave the columns of a
# two-column article).
_MAX_ROW_SEGMENT_CHARS = 80
_MAX_LABEL_WORDS = 6
_INLINE_KV_RE = re.compile(r"^\s*([^:：]{1,60}?)\s*[:：]\s*(\S.*)$")
_LABEL_ONLY_RE = re.compile(r"^\s*([^:：]{1,60}?)\s*[:：]\s*$")


@dataclass
class Segment:
    text: str
    bbox: tuple[int, int, int, int] | None
    confidence: float | None
    label: str | None = None


@dataclass
class Line:
    """One visual row: one or more segments left to right."""

    id: str
    page: int
    segments: list[Segment]
    kind: str = "text"  # text | heading | title | header | footer | caption | list_item

    @property
    def bbox(self) -> tuple[int, int, int, int] | None:
        boxes = [s.bbox for s in self.segments if s.bbox]
        if not boxes:
            return None
        return (min(b[0] for b in boxes), min(b[1] for b in boxes), max(b[2] for b in boxes), max(b[3] for b in boxes))

    @property
    def min_confidence(self) -> float | None:
        values = [s.confidence for s in self.segments if s.confidence is not None]
        return min(values) if values else None

    @property
    def text(self) -> str:
        return " | ".join(s.text for s in self.segments)


@dataclass
class Table:
    id: str
    page: int
    bbox: tuple[int, int, int, int] | None
    headers: list[str]
    rows: list[list[str]]
    has_header: bool
    malformed: list[str] = field(default_factory=list)
    header_inferred: bool = False  # first row promoted to header by _build_table's heuristic


@dataclass
class KeyValueCandidate:
    label: str
    value: str
    page: int
    label_line: str
    value_line: str
    relation: str  # inline | same_row | below


@dataclass
class PageMeta:
    number: int
    source: str
    confidence: float | None


@dataclass
class Unit:
    """Smallest piece of model input. `source_texts` is the document text
    this unit carries -- the grounding check (grounding.py) only accepts an
    extracted value that occurs in the source texts of the chunk it came
    from. A table unit keeps its `table` and `row_range`, so chunking can
    split it by rows (header repeated) when it doesn't fit one chunk."""

    page: int
    kind: str  # line | table | kv
    text: str
    source_texts: list[str]
    table: Table | None = None
    row_range: tuple[int, int] | None = None


@dataclass
class NormalizedDocument:
    pages: list[PageMeta]
    lines: list[Line]
    tables: list[Table]
    key_values: list[KeyValueCandidate]
    warnings: list[dict]
    include_bboxes: bool
    low_confidence_threshold: float

    @property
    def is_empty(self) -> bool:
        return not any(s.text.strip() for line in self.lines for s in line.segments) and not any(
            cell for t in self.tables for row in [t.headers, *t.rows] for cell in row
        )

    def units(self) -> list[Unit]:
        """Per page: lines (in reading order) with tables placed at their
        vertical position, then that page's key/value hints."""
        units: list[Unit] = []
        segments_per_line = {line.id: len(line.segments) for line in self.lines}
        for page in self.pages:
            positioned: list[tuple[float, int, Unit]] = []
            for order, line in enumerate(l for l in self.lines if l.page == page.number):
                y = line.bbox[1] if line.bbox else math.inf
                positioned.append((y, order, Unit(page=page.number, kind="line", text=self.render_line(line), source_texts=[s.text for s in line.segments])))
            for order, table in enumerate(t for t in self.tables if t.page == page.number):
                y = table.bbox[1] if table.bbox else math.inf
                unit = table_unit(table, (0, len(table.rows)))
                positioned.append((y, 100_000 + order, unit))
            positioned.sort(key=lambda entry: (entry[0], entry[1]))
            units.extend(unit for _, _, unit in positioned)
            for kv in (k for k in self.key_values if k.page == page.number and self._worth_hinting(k, segments_per_line)):
                units.append(
                    Unit(
                        page=page.number,
                        kind="kv",
                        text=f'{kv.label_line}->{kv.value_line}: "{kv.label}" = "{kv.value}"',
                        source_texts=[kv.label, kv.value],
                    )
                )
        return units

    @staticmethod
    def _worth_hinting(kv: KeyValueCandidate, segments_per_line: dict[str, int]) -> bool:
        """Only relations the line text doesn't already make explicit: a
        value on the line below its label, or a pair inside a row of three
        or more parts (a multi-column form row). "Label: value" and a
        two-part "Label: | value" row are left to the line itself -- a hint
        would just repeat it and spend context."""
        if kv.relation == "below":
            return True
        return kv.relation == "same_row" and segments_per_line.get(kv.label_line, 0) >= 3

    def render_line(self, line: Line) -> str:
        confidence = line.min_confidence
        marker = "?" if confidence is not None and confidence < self.low_confidence_threshold else ""
        position = f" @{line.bbox[0]},{line.bbox[1]}" if self.include_bboxes and line.bbox else ""
        prefix = {
            "title": "# ",
            "heading": "## ",
            "header": "[header] ",
            "footer": "[footer] ",
            "caption": "[caption] ",
            "footnote": "[footnote] ",
            "list_item": "- ",
        }.get(line.kind, "")
        return f"{line.id}{marker}{position}: {prefix}{line.text}"

    def to_dict(self) -> dict:
        """Debug/inspection view of the whole representation."""
        return {
            "pages": [{"page_number": p.number, "source": p.source, "confidence": p.confidence} for p in self.pages],
            "lines": [
                {"id": l.id, "page": l.page, "kind": l.kind, "bbox": list(l.bbox) if l.bbox else None, "confidence": l.min_confidence, "segments": [s.text for s in l.segments]}
                for l in self.lines
            ],
            "tables": [
                {"id": t.id, "page": t.page, "headers": t.headers, "rows": t.rows, "has_header": t.has_header, "malformed": t.malformed}
                for t in self.tables
            ],
            "key_values": [kv.__dict__ for kv in self.key_values],
            "warnings": self.warnings,
        }


def table_unit(table: Table, row_range: tuple[int, int]) -> Unit:
    start, end = row_range
    rows = table.rows[start:end]
    part = "" if (start, end) == (0, len(table.rows)) else f' rows="{start + 1}-{end}"'
    malformed = ' repaired="true"' if table.malformed else ""
    header_note = ' header="inferred"' if table.header_inferred else ("" if table.has_header else ' header="none-detected"')
    lines = [f'<table id="{table.id}" page="{table.page}" cols="{len(table.headers)}"{part}{header_note}{malformed}>']
    lines.append(_md_row(table.headers))
    lines.append("|" + "---|" * len(table.headers))
    lines.extend(_md_row(row) for row in rows)
    lines.append("</table>")
    headers = [c for c in table.headers if c] if table.has_header else []
    source = headers + [c for row in rows for c in row if c]
    return Unit(page=table.page, kind="table", text="\n".join(lines), source_texts=source, table=table, row_range=row_range)


def _md_row(cells: list[str]) -> str:
    return "| " + " | ".join(c.replace("|", "\\|") for c in cells) + " |"


# ----------------------------------------------------------------------
# Building
# ----------------------------------------------------------------------
def build_normalized_document(pages: list[dict], *, include_bboxes: bool = True, low_confidence_threshold: float = 0.8) -> NormalizedDocument:
    metas: list[PageMeta] = []
    lines: list[Line] = []
    tables: list[Table] = []
    key_values: list[KeyValueCandidate] = []
    warnings: list[dict] = []
    line_counter = 0
    table_counter = 0

    for page in pages:
        number = int(page.get("page_number") or len(metas) + 1)
        source = page.get("method") or "unknown"
        metas.append(PageMeta(number=number, source=source, confidence=page.get("confidence")))

        page_tables: list[Table] = []
        for raw_table in page.get("tables") or []:
            table_counter += 1
            table = _build_table(raw_table, table_id=f"T{table_counter}", page=number)
            if table is None:
                warnings.append({"code": "MALFORMED_TABLE", "message": f"table T{table_counter} on page {number} had no usable cells and was skipped"})
                continue
            if table.malformed:
                warnings.append({"code": "MALFORMED_TABLE", "message": f"table {table.id} on page {number} was repaired: {', '.join(table.malformed)}"})
            page_tables.append(table)
        tables.extend(page_tables)

        if page.get("lines"):
            segments = _native_line_segments(page["lines"], page.get("blocks") or [], page.get("tables") or [])
        else:
            segments = _segments_outside_tables(page.get("blocks") or [], page.get("tables") or [])
        page_lines: list[Line] = []
        rows = _group_rows(segments)
        median_height = _median_line_height(rows)
        for row in rows:
            line_counter += 1
            line = Line(id=f"L{line_counter}", page=number, segments=row, kind=_line_kind(row, source, median_height))
            page_lines.append(line)
        lines.extend(page_lines)
        key_values.extend(_key_value_candidates(page_lines))

    return NormalizedDocument(
        pages=metas,
        lines=lines,
        tables=tables,
        key_values=key_values,
        warnings=warnings,
        include_bboxes=include_bboxes,
        low_confidence_threshold=low_confidence_threshold,
    )


def _as_bbox(raw) -> tuple[int, int, int, int] | None:
    if not raw or len(raw) != 4:
        return None
    try:
        x0, y0, x1, y1 = (int(round(float(v))) for v in raw)
    except (TypeError, ValueError):
        return None
    return (min(x0, x1), min(y0, y1), max(x0, x1), max(y0, y1))


def _clean(text) -> str:
    return re.sub(r"\s+", " ", str(text or "")).strip()


def _segments_outside_tables(blocks: list[dict], raw_tables: list[dict]) -> list[Segment]:
    """pipeline.py re-inserts every table cell into `blocks` for plain-text
    consumers; the table is rendered separately here as a real grid, so its
    cells are dropped from the line stream to avoid sending them twice."""
    table_boxes = [b for b in (_as_bbox(t.get("bbox")) for t in raw_tables) if b]
    cell_texts = {_clean(c.get("text")) for t in raw_tables for c in t.get("cells") or [] if _clean(c.get("text"))}
    segments: list[Segment] = []
    for block in blocks:
        text = _clean(block.get("text"))
        if not text:
            continue
        bbox = _as_bbox(block.get("bbox"))
        if raw_tables:
            if bbox is None and text in cell_texts:
                continue
            if bbox is not None and any(_center_inside(bbox, box) for box in table_boxes) and text in cell_texts:
                continue
        confidence = block.get("confidence")
        segments.append(Segment(text=text, bbox=bbox, confidence=float(confidence) if confidence is not None else None, label=block.get("label")))
    return segments


def _native_line_segments(lines: list[dict], blocks: list[dict], raw_tables: list[dict]) -> list[Segment]:
    """native_text pages: PyMuPDF's per-visual-line text (pipeline.py's
    PageResult.lines) instead of Docling's paragraph blocks, which merge a
    borderless label/value region into one paragraph. Docling still
    supplies the structural role: a line whose text is exactly a labelled
    Docling block (a heading, page header, caption...) takes that label.
    Lines inside a recognized table are dropped -- the table is rendered as
    a grid instead."""
    labels = {_clean(b.get("text")): b["label"] for b in blocks if b.get("label") and b["label"] != "text"}
    table_boxes = [b for b in (_as_bbox(t.get("bbox")) for t in raw_tables) if b]
    segments: list[Segment] = []
    for line in lines:
        text = _clean(line.get("text"))
        bbox = _as_bbox(line.get("bbox"))
        if not text or (bbox is not None and any(_center_inside(bbox, box) for box in table_boxes)):
            continue
        segments.append(Segment(text=text, bbox=bbox, confidence=1.0, label=labels.get(text)))
    return segments


def _center_inside(inner, outer) -> bool:
    cx, cy = (inner[0] + inner[2]) / 2, (inner[1] + inner[3]) / 2
    return outer[0] <= cx <= outer[2] and outer[1] <= cy <= outer[3]


def _group_rows(segments: list[Segment]) -> list[list[Segment]]:
    """Visual rows: short blocks whose vertical extents overlap by at least
    half the smaller block's height are one row, ordered left to right.
    Blocks without a bbox keep their original order after the positioned
    ones (no position to place them by)."""
    positioned = sorted((s for s in segments if s.bbox), key=lambda s: (s.bbox[1], s.bbox[0]))
    unpositioned = [s for s in segments if not s.bbox]
    rows: list[list[Segment]] = []
    for segment in positioned:
        target = None
        if len(segment.text) <= _MAX_ROW_SEGMENT_CHARS:
            for row in rows[-2:]:
                if _row_accepts(row, segment):
                    target = row
                    break
        if target is None:
            rows.append([segment])
        else:
            target.append(segment)
            target.sort(key=lambda s: s.bbox[0])
    rows.extend([s] for s in unpositioned)
    return rows


def _row_accepts(row: list[Segment], segment: Segment) -> bool:
    if any(len(s.text) > _MAX_ROW_SEGMENT_CHARS for s in row) or not _heading_mix_allowed(row + [segment]):
        return False
    top = min(s.bbox[1] for s in row)
    bottom = max(s.bbox[3] for s in row)
    overlap = min(bottom, segment.bbox[3]) - max(top, segment.bbox[1])
    smaller = min(bottom - top, segment.bbox[3] - segment.bbox[1]) or 1
    if overlap < 0.5 * smaller:
        return False
    # Must not overlap horizontally with an existing segment (that would be
    # two stacked lines of the same block, not side-by-side columns).
    return all(segment.bbox[0] >= s.bbox[2] - 2 or segment.bbox[2] <= s.bbox[0] + 2 for s in row)


def _heading_mix_allowed(segments: list[Segment]) -> bool:
    """A heading normally stands alone on its row. The exception is a
    heading-labelled block beside a value: Docling labels totals such as
    "Total net item value excl tax" as section headers (verified on a real
    purchase order), and the amount at the far right of the same row is
    that label's value -- splitting them would orphan the amount."""
    headings = [s for s in segments if s.label in HEADING_LABELS]
    if not headings:
        return True
    others = [s for s in segments if s.label not in HEADING_LABELS]
    return len(headings) == 1 and bool(others) and all(any(ch.isdigit() for ch in s.text) for s in others)


def _line_kind(row: list[Segment], source: str, median_height: float | None = None) -> str:
    if len(row) == 1:
        label = row[0].label
        if label == "title":
            return "title"
        if label == "section_header":
            return "heading"
        if label in ("page_header", "page_footer"):
            return "header" if label == "page_header" else "footer"
        if label in ("caption", "footnote", "list_item"):
            return label
        if label is None and source != "native_text" and _looks_like_heading(row[0].text):
            return "heading"
        if label in (None, "text") and median_height and _is_tall_short_line(row[0], median_height):
            return "heading"
    return "text"


def _is_tall_short_line(segment: Segment, median_height: float) -> bool:
    """A section title without a Docling label (every OCR page, and
    headings Docling classed as plain text): short, no colon or value, and
    set in visibly larger type than the page's typical line. Verified on
    PaddleOCR and PyMuPDF output: 13pt headings over 11pt body text come
    back ~1.2-1.4x taller."""
    if not segment.bbox:
        return False
    text = segment.text
    height = segment.bbox[3] - segment.bbox[1]
    return (
        height >= 1.15 * median_height
        and len(text.split()) <= 6
        and ":" not in text
        and not any(c.isdigit() for c in text)
        and any(c.isalpha() for c in text)
    )


def _median_line_height(rows: list[list[Segment]]) -> float | None:
    heights = sorted(s.bbox[3] - s.bbox[1] for row in rows for s in row if s.bbox and s.bbox[3] > s.bbox[1])
    return heights[len(heights) // 2] if len(heights) >= 5 else None


def _looks_like_heading(text: str) -> bool:
    """OCR pages carry no Docling labels. Only the unambiguous case is
    treated as a heading: a short ALL-CAPS line with no digits or colon."""
    letters = [c for c in text if c.isalpha()]
    return (
        len(letters) >= 3
        and len(text.split()) <= 8
        and text.upper() == text
        and not any(c.isdigit() for c in text)
        and ":" not in text
    )


def _is_label(text: str) -> bool:
    words = text.split()
    return 0 < len(words) <= _MAX_LABEL_WORDS and any(c.isalpha() for c in text)


def _label_only(text: str) -> str | None:
    match = _LABEL_ONLY_RE.match(text)
    if match and _is_label(match.group(1)):
        return match.group(1).strip()
    return None


def _key_value_candidates(page_lines: list[Line]) -> list[KeyValueCandidate]:
    candidates: list[KeyValueCandidate] = []
    for index, line in enumerate(page_lines):
        segments = line.segments
        for j, segment in enumerate(segments):
            inline = _INLINE_KV_RE.match(segment.text)
            if inline and _is_label(inline.group(1)) and not inline.group(1).strip().isdigit():
                candidates.append(KeyValueCandidate(inline.group(1).strip(), inline.group(2).strip(), line.page, line.id, line.id, "inline"))
                continue
            label = _label_only(segment.text)
            if label is None:
                continue
            next_value = segments[j + 1].text if j + 1 < len(segments) else None
            # "Signature: | Date: 25/08/2026" -- the next segment is its own
            # label/value pair, not this label's value.
            if next_value is not None and _label_only(next_value) is None and not _INLINE_KV_RE.match(next_value):
                candidates.append(KeyValueCandidate(label, next_value, line.page, line.id, line.id, "same_row"))
                continue
            below = _value_below(segment, page_lines[index + 1] if index + 1 < len(page_lines) else None)
            if below is not None:
                below_line, below_segment = below
                candidates.append(KeyValueCandidate(label, below_segment.text, line.page, line.id, below_line.id, "below"))
        # Two-box row without a colon ("Invoice Number    INV-0042"): a
        # short label-like box followed by a box containing a digit.
        if len(segments) == 2 and ":" not in segments[0].text and _is_label(segments[0].text) and len(segments[0].text.split()) <= 4:
            first, second = segments
            if any(c.isdigit() for c in second.text) and not any(c.isdigit() for c in first.text):
                candidates.append(KeyValueCandidate(first.text, second.text, line.page, line.id, line.id, "same_row"))
    return candidates


def _value_below(label_segment: Segment, next_line: Line | None) -> tuple[Line, Segment] | None:
    """The segment of the next line that sits in this label's column --
    "Line Manager: | Work Location:" over "Nimal Fernando | Colombo Head
    Office" pairs each label with the value under IT, not with the next
    line's first part."""
    if next_line is None or not label_segment.bbox:
        return None
    height = max(label_segment.bbox[3] - label_segment.bbox[1], 1)
    lx0, lx1 = label_segment.bbox[0], label_segment.bbox[2]
    for candidate in next_line.segments:
        box = candidate.bbox
        if not box or _label_only(candidate.text) is not None or _INLINE_KV_RE.match(candidate.text):
            continue
        gap = box[1] - label_segment.bbox[3]
        same_column = box[0] < lx1 and box[2] > lx0 - height and abs(box[0] - lx0) <= max(3 * height, (lx1 - lx0))
        if 0 <= gap <= 2 * height and same_column:
            return next_line, candidate
    return None


def _mostly_numeric(text: str) -> bool:
    chars = [c for c in text if not c.isspace()]
    return bool(chars) and sum(c.isdigit() for c in chars) >= len(chars) / 2


def _build_table(raw: dict, *, table_id: str, page: int) -> Table | None:
    """Rebuilds the grid from cells. Repairs, rather than rejects, what
    TableFormer/Docling can hand back: num_rows/num_cols that disagree with
    the cells, negative or missing offsets, two cells claiming one slot."""
    cells = raw.get("cells") or []
    problems: list[str] = []
    placed: list[tuple[int, int, int, int, str, bool]] = []
    for cell in cells:
        try:
            row = int(cell.get("row"))
            col = int(cell.get("col"))
        except (TypeError, ValueError):
            problems.append("cell without row/col position dropped")
            continue
        if row < 0 or col < 0:
            problems.append("cell with negative position dropped")
            continue
        row_span = max(int(cell.get("row_span") or 1), 1)
        col_span = max(int(cell.get("col_span") or 1), 1)
        placed.append((row, col, row_span, col_span, _clean(cell.get("text")), bool(cell.get("column_header"))))
    if not placed:
        return None

    declared_rows = int(raw.get("num_rows") or 0)
    declared_cols = int(raw.get("num_cols") or 0)
    num_rows = max(declared_rows, max(r + rs for r, _, rs, _, _, _ in placed))
    num_cols = max(declared_cols, max(c + cs for _, c, _, cs, _, _ in placed))
    if (num_rows, num_cols) != (declared_rows, declared_cols):
        problems.append(f"declared {declared_rows}x{declared_cols} grid resized to {num_rows}x{num_cols}")

    grid = [["" for _ in range(num_cols)] for _ in range(num_rows)]
    header_flags = [[False] * num_cols for _ in range(num_rows)]
    collisions = 0
    for row, col, row_span, col_span, text, is_header in placed:
        # Text goes in the span's origin slot only; header flags cover the
        # whole span so a spanning group header labels every column under it.
        if grid[row][col] and text and grid[row][col] != text:
            collisions += 1
            grid[row][col] = f"{grid[row][col]} {text}"
        elif text:
            grid[row][col] = text
        for r in range(row, min(row + row_span, num_rows)):
            for c in range(col, min(col + col_span, num_cols)):
                header_flags[r][c] = header_flags[r][c] or is_header
                if is_header and (r, c) != (row, col) and not grid[r][c]:
                    grid[r][c] = text
    if collisions:
        problems.append(f"{collisions} overlapping cells merged")

    header_row_count = 0
    for r in range(num_rows):
        filled = [c for c in range(num_cols) if grid[r][c]]
        # TableFormer can flag the first data row as a second header row
        # (verified on a real purchase order: item number and quantity ended
        # up merged into the headers as "Item# / 30043697"). A later "header"
        # row holding numeric cells is data.
        if r > 0 and any(_mostly_numeric(grid[r][c]) for c in filled):
            break
        if filled and all(header_flags[r][c] for c in filled):
            header_row_count += 1
        else:
            break
    # TableFormer often leaves column_header unset (verified on a plain
    # two-column salary table). A first row of pure text above rows that
    # contain numbers is a header row; anything less clear-cut stays a
    # data row with placeholder headers.
    header_inferred = False
    if header_row_count == 0 and num_rows >= 2:
        first = [c for c in grid[0] if c]
        rest = [c for row in grid[1:] for c in row if c]
        if first and not any(ch.isdigit() for c in first for ch in c) and any(ch.isdigit() for c in rest for ch in c):
            header_row_count = 1
            header_inferred = True
    rows = [row for row in grid[header_row_count:] if any(row)]
    if header_row_count:
        headers = []
        for c in range(num_cols):
            parts: list[str] = []
            for r in range(header_row_count):
                if grid[r][c] and grid[r][c] not in parts:
                    parts.append(grid[r][c])
            headers.append(" / ".join(parts))
    else:
        headers = [f"col_{c + 1}" for c in range(num_cols)]
    return Table(
        id=table_id,
        page=page,
        bbox=_as_bbox(raw.get("bbox")),
        headers=headers,
        rows=rows,
        has_header=bool(header_row_count),
        malformed=problems,
        header_inferred=header_inferred,
    )
