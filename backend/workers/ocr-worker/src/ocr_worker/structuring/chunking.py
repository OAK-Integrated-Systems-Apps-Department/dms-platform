"""Packs a NormalizedDocument's units into chunks that fit the model's
context window, page by page.

Budgeting is by estimated tokens (chars / LLM_CHARS_PER_TOKEN, deliberately
conservative) because the tokenizer depends on whichever model is
configured. A line is never split across chunks unless it alone exceeds the
budget; a table is kept whole when it fits, otherwise split by rows with its
header repeated in every part, so every part is still a self-describing grid.
"""
from __future__ import annotations

import math
from dataclasses import dataclass, field

from .document_model import NormalizedDocument, PageMeta, Unit, table_unit

# <page ...> ... </page> wrapper cost per page present in a chunk.
_PAGE_OVERHEAD_TOKENS = 24


def estimate_tokens(text: str, chars_per_token: float) -> int:
    return math.ceil(len(text) / max(chars_per_token, 0.5)) + 1


@dataclass
class Chunk:
    units: list[Unit]
    pages: dict[int, PageMeta]
    chars_per_token: float
    depth: int = 0  # how many times this chunk was re-split after an incomplete answer
    _rendered: str | None = field(default=None, repr=False)

    @property
    def page_numbers(self) -> list[int]:
        return sorted({u.page for u in self.units})

    @property
    def source_texts(self) -> list[str]:
        return [text for unit in self.units for text in unit.source_texts]

    @property
    def estimated_tokens(self) -> int:
        return estimate_tokens(self.render(), self.chars_per_token)

    def render(self) -> str:
        if self._rendered is None:
            out: list[str] = []
            for page in self.page_numbers:
                meta = self.pages.get(page)
                source = meta.source if meta else "unknown"
                confidence = f' ocr_confidence="{meta.confidence:.2f}"' if meta and meta.confidence is not None and source != "native_text" else ""
                out.append(f'<page n="{page}" source="{source}"{confidence}>')
                page_units = [u for u in self.units if u.page == page]
                out.extend(u.text for u in page_units if u.kind != "kv")
                hints = [u.text for u in page_units if u.kind == "kv"]
                if hints:
                    out.append("<key_values>")
                    out.extend(hints)
                    out.append("</key_values>")
                out.append("</page>")
            self._rendered = "\n".join(out)
        return self._rendered

    def can_split(self) -> bool:
        if len(self.units) > 1:
            return True
        unit = self.units[0]
        return unit.kind == "table" and unit.row_range is not None and unit.row_range[1] - unit.row_range[0] > 1

    def split(self) -> tuple["Chunk", "Chunk"]:
        if len(self.units) > 1:
            middle = len(self.units) // 2
            halves = (self.units[:middle], self.units[middle:])
        else:
            unit = self.units[0]
            start, end = unit.row_range
            middle = (start + end) // 2
            halves = ([table_unit(unit.table, (start, middle))], [table_unit(unit.table, (middle, end))])
        return tuple(Chunk(units=list(h), pages=self.pages, chars_per_token=self.chars_per_token, depth=self.depth + 1) for h in halves)


def plan_chunks(document: NormalizedDocument, *, budget_tokens: int, chars_per_token: float) -> list[Chunk]:
    pages = {p.number: p for p in document.pages}
    units: list[Unit] = []
    for unit in document.units():
        units.extend(_fit_unit(unit, budget_tokens, chars_per_token))

    chunks: list[Chunk] = []
    current: list[Unit] = []
    current_tokens = 0
    current_pages: set[int] = set()
    for unit in units:
        cost = estimate_tokens(unit.text, chars_per_token) + (0 if unit.page in current_pages else _PAGE_OVERHEAD_TOKENS)
        if current and current_tokens + cost > budget_tokens:
            chunks.append(Chunk(units=current, pages=pages, chars_per_token=chars_per_token))
            current, current_tokens, current_pages = [], 0, set()
            cost = estimate_tokens(unit.text, chars_per_token) + _PAGE_OVERHEAD_TOKENS
        current.append(unit)
        current_tokens += cost
        current_pages.add(unit.page)
    if current:
        chunks.append(Chunk(units=current, pages=pages, chars_per_token=chars_per_token))
    return chunks


def _fit_unit(unit: Unit, budget_tokens: int, chars_per_token: float) -> list[Unit]:
    limit = budget_tokens - _PAGE_OVERHEAD_TOKENS
    if estimate_tokens(unit.text, chars_per_token) <= limit:
        return [unit]
    if unit.kind == "table" and unit.table is not None and len(unit.table.rows) > 1:
        return _split_table(unit, limit, chars_per_token)
    return _split_text(unit, limit, chars_per_token)


def _split_table(unit: Unit, limit: int, chars_per_token: float) -> list[Unit]:
    table = unit.table
    parts: list[Unit] = []
    start = 0
    total = len(table.rows)
    while start < total:
        end = start + 1
        # Grow the part row by row while it still fits (header included).
        while end < total and estimate_tokens(table_unit(table, (start, end + 1)).text, chars_per_token) <= limit:
            end += 1
        part = table_unit(table, (start, end))
        parts.extend([part] if estimate_tokens(part.text, chars_per_token) <= limit else _split_text(part, limit, chars_per_token))
        start = end
    return parts


def _split_text(unit: Unit, limit: int, chars_per_token: float) -> list[Unit]:
    """Last resort for a single line/row bigger than a whole chunk: split at
    word boundaries. Each piece keeps its own slice as grounding source."""
    max_chars = max(int(limit * chars_per_token) - 16, 64)
    words = unit.text.split(" ")
    pieces: list[str] = []
    buffer = ""
    for word in words:
        candidate = f"{buffer} {word}" if buffer else word
        if len(candidate) > max_chars and buffer:
            pieces.append(buffer)
            buffer = word
        else:
            buffer = candidate
        while len(buffer) > max_chars:
            pieces.append(buffer[:max_chars])
            buffer = buffer[max_chars:]
    if buffer:
        pieces.append(buffer)
    total = len(pieces)
    return [
        Unit(page=unit.page, kind="line", text=f"[part {i + 1}/{total}] {piece}", source_texts=[piece])
        for i, piece in enumerate(pieces)
    ]
