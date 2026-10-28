# OCR columns in bulk export, and what they mean for re-import

## What was added

`ExportArchiveBuilder` now writes two extra columns in the export's
`metadata.csv` / `metadata.xlsx` sheet:

| Column       | Source                              | Values                                       |
| ------------ | ----------------------------------- | -------------------------------------------- |
| `OCR status` | `document_versions.ocr_status`      | `pending`, `processing`, `completed`, `failed` |
| `OCR review` | `document_versions.requires_review` | `Reviewed`, `Needs review`                     |

Both are read off the `DocumentVersion` the row already loads, so the export
costs exactly what it did before — no extra query, no extra round trip per
document.

Rules the cells follow:

- A version that was never OCR'd (`ocr_status` null or `not_required`) leaves
  **both cells empty**. The literal string `not_required` never reaches a
  sheet. A tenant running with `dms.ocr.enabled=false` therefore gets two
  empty columns rather than a column full of jargon.
- `OCR review` is filled **only** for `completed`. A `failed` or still-running
  version has no extracted fields, so neither "Reviewed" nor "Needs review"
  would be true of it; the status column alone tells that story.

### Why there is no "OCR values" column

The user requirement was "OCR status and its reviewed field values". The
reviewed field values are **already in the sheet**, in the per-field metadata
columns the export has always written:

- `DocumentMetadataService#applyOcrFields` writes what OCR extracted into
  `document_metadata_values` (driven by `OcrEventsConsumer`, with
  `OcrFieldsReconciler` as the catch-up path).
- `DocumentServiceImpl#saveReview` writes a reviewer's corrections to the
  same place.

So the `Amount`, `Invoice number`, … columns for an OCR'd document hold the
post-review values. A separate "OCR values" column would be a second copy of
the same facts that can drift out of step with the first. `OCR review` is
what tells a reader whether those values have been signed off yet.

## Column position

The two columns are appended **after** `Note`, i.e. after every base column
that existed before, and still before the per-field metadata columns. Nothing
anyone already reads by position moves.

## Re-import safety

### The short answer

Adding these columns cannot break re-import, because **an exported archive was
never a valid import template** — before this change or after it. They are two
different file formats that happen to both be spreadsheets.

### Why

| | Bulk **import** template | Bulk **export** metadata sheet |
| --- | --- | --- |
| XLSX shape | two sheets: `_meta` (document type id, schema version, column keys) and `Import` | one sheet, `Documents` |
| CSV shape | `#` marker lines carrying `documentTypeId=` / `schemaVersion=`, then a header row | a plain header row, UTF-8 BOM, no markers |
| First two columns | must be the keys `filename`, `title` | `Document ID`, `Title` |
| Column identity | **field keys** from `_meta` / the marker lines | **human labels** (`Amount`, `Tags`) |

`ImportTemplateWorkbook#parse` throws `IMPORT_TEMPLATE_INVALID` when the
`_meta` sheet is missing, and `ImportTemplateCsv#parse` throws the same when
the marker lines are missing or the first two column keys are not
`filename`/`title`. An export fails all of those checks on its first line.

The supported route from an export back into the system is unchanged:
download a fresh template for the document type (`GET` the import template),
and paste the values across. Column **order** in the export does not matter to
that route, because the import matches by field key from its own `_meta`, not
by anything in the export.

### The one real collision risk, and how it is closed

`checkHeader` rejects an import template with two identical column keys. The
export has the matching hazard in its own sheet: a tenant metadata field
*labelled* `OCR status` would have produced two columns with the same heading,
and a human copying columns across could carry that ambiguity into a template.

`ExportArchiveBuilder#addMetadata` already disambiguates a label that collides
with a base column, rewriting it as `Label (field_key)`. Because `OCR status`
and `OCR review` were added to `BASE_COLUMNS` — not special-cased elsewhere —
that existing mechanism now covers them with no new code. A tenant field
labelled `OCR status` exports as `OCR status (their_field_key)`.

## Test

`ExportArchiveBuilderTest#ocrColumns_reportStatusAndReviewState_andStayEmptyWhenOcrNeverRan`
covers all four states (reviewed, needs review, failed, never OCR'd) and
`#zipHoldsCleanFilesNumbered_andAMetadataRowPerDocument` pins the column order
as `… Note, OCR status, OCR review, <field columns>`.
