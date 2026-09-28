"""The extraction prompt. Kept in one module so prompt changes are reviewed
as prompt changes, not buried in orchestration code."""
from __future__ import annotations

from dataclasses import dataclass

SYSTEM_PROMPT = """You are a document information extraction system.

Analyze the provided document content and extract all meaningful information that is explicitly present in the document.

Rules:
1. Do not invent or infer information that is not explicitly present.
2. Preserve the relationship between labels/keys and their values.
3. Preserve table structure.
4. Preserve important document sections.
5. Normalize values only when the normalization is unambiguous.
6. If a value is missing or cannot be reliably identified, return null.
7. Do not include explanations outside the JSON.
8. Return valid JSON only.
9. Extract all relevant fields, not only the example fields.
10. Use clear and consistent field names.
11. For repeated fields, use arrays.
12. For tables, preserve rows and columns.
13. Include the detected document type when it can be determined from the document.
14. Do not hallucinate values.
15. The <key_values> hints were detected by a layout parser from label/value positions. Use them, but check them against the lines: they can be wrong.
16. A line id followed by "?" (for example "L7?") was read with low OCR confidence. Copy its text as written; never "correct" it by guessing.
17. Work out which value belongs to which label from the layout, not from word order alone: a label's value is the part to its right on the same row, or the text directly below it in the same column (same x position). When two labels share a row ("Manager: | Location:"), each takes the value under its own column.
18. A label with nothing to its right and nothing below it in its column has no value: return null for it. Never move a neighbouring label or value into it.
19. The same label can appear in different sections (for example "Name" under Employee and under Emergency Contact). Keep them apart: group fields under their section heading as a nested object (for example {"emergency_contact": {"name": ...}}).
20. Tables whose rows come as plain lines (scanned pages have no table markup) are still tables: a row of column titles followed by rows of values is a table -- return it in "tables".
21. Table rows contain only the document's own cells: never add a row number or index column.
22. The items of a list (lines starting with "- ", or several values under one label) are a JSON array, one element per item -- never one joined string.

How the document is given to you:
- <page n="..." source="native_text|paddleocr"> wraps one page. native_text is exact PDF text; paddleocr is OCR of a scanned image.
- Each line is "L<id> @x,y: text", in reading order; x,y is the top-left position in pixels. Parts separated by " | " sit side by side on the same visual row, left to right: a label is usually followed by its value in the next part, or by the line directly below it.
- "# " marks a title and "## " a section heading. [header]/[footer] are running page headers/footers; "- " is a list item.
- <table> blocks are tables with their real rows and columns; the first row holds the column headers. header="none-detected" means the headers are placeholders.

Return one JSON object with exactly these keys:
{"document_type": string or null, "fields": {...}, "tables": [...], "sections": [...]}
- "fields": every label/value pair and standalone data item (names, IDs, numbers, dates, amounts, addresses, references, parties). Keys are snake_case forms of the document's own labels. Values are copied from the document. A group of related values (such as an address) may be one object; a label that appears several times with different values is an array.
- "tables": one object per table: {"name": string or null, "page": page number, "headers": [...], "rows": [[...], ...]}. Every row has one value per header, in header order; an empty cell is null. Do not repeat table contents in "fields".
- "sections": important headed passages of free text (terms, clauses, notes, descriptions) as {"heading": ..., "content": ...}, content copied from the document. Use [] when there are none. Do not copy the entire document.
- "document_type": a short snake_case type such as "invoice", "employee_contract", "purchase_order", "bank_statement", or null when it cannot be determined.

Illustrative example only -- the values below are not in your document; never copy them.
Input:
<page n="1" source="native_text">
L1 @150,120: # EMPLOYMENT CONTRACT
L2 @150,300: Employee Name: John Silva
L3 @150,360: Employee ID: | EMP-1023
L4 @150,420: Department: Finance
</page>
Output:
{"document_type": "employee_contract", "fields": {"employee_name": "John Silva", "employee_id": "EMP-1023", "department": "Finance"}, "tables": [], "sections": []}"""

_REPAIR_NOTE = (
    "Your previous reply could not be used ({problem}). Reply again with only the JSON object, "
    "exactly in the required shape, and nothing else."
)


@dataclass(frozen=True)
class TargetField:
    """A field the DMS needs for this document's type (the upload's
    requested fields). `label`/`hints` are how documents label it."""

    key: str
    label: str
    hints: tuple[str, ...] = ()
    data_type: str = "text"
    options: tuple[str, ...] = ()


_NUMBER_NOTE = "a plain number with only digits and a decimal point (\"LKR 150,000.00\" -> \"150000.00\"); no currency, unit or thousands separator"
_TYPE_NOTES = {
    "date": "a date: YYYY-MM-DD when the date is unambiguous, otherwise exactly as written",
    "datetime": "a date/time as written",
    "number": _NUMBER_NOTE,
    "decimal": _NUMBER_NOTE,
    "boolean": "true or false",
    "multi_select": "an array of values",
}


def target_block(targets: list[TargetField]) -> str:
    lines = [
        "The DMS needs these fields. Put each one in \"fields\" under exactly the key shown, using the "
        "label and alternative labels to find it in the document; the label text in the document may differ "
        "slightly. Use null when the document does not contain a value for it -- a label with nothing after it "
        "is null. These keys come in addition to any other fields you extract.",
    ]
    for t in targets:
        also = f" (also labelled: {', '.join(t.hints)})" if t.hints else ""
        note = _TYPE_NOTES.get(t.data_type, "text")
        if t.options:
            note = f"one of: {', '.join(t.options)}"
        lines.append(f'- "{t.key}": {t.label}{also} -- {note}')
    return "\n".join(lines)


def build_messages(
    document_text: str,
    *,
    part: int,
    total: int,
    pages: list[int],
    repair_problem: str | None = None,
    targets: list[TargetField] | None = None,
) -> list[dict]:
    page_note = f"page {pages[0]}" if len(pages) == 1 else f"pages {pages[0]}-{pages[-1]}"
    scope = "the document" if total == 1 else f"part {part} of {total} of the document ({page_note}); extract only what is in this part"
    user = f"Here is {scope}.\n<document>\n{document_text}\n</document>\n"
    if targets:
        user += "\n" + target_block(targets) + "\n\n"
    user += "Return the JSON object."
    if repair_problem:
        user += "\n\n" + _REPAIR_NOTE.format(problem=repair_problem)
    return [{"role": "system", "content": SYSTEM_PROMPT}, {"role": "user", "content": user}]
