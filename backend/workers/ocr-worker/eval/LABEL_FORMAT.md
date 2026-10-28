# Label format for real documents

A label is ground truth: somebody read a real document and typed what it
says. It is also the **ruler** the pipeline is measured against, which is
why the format is exact. A malformed label does not fail loudly — it
scores the pipeline wrong, quietly, in the direction of "the pipeline is
broken", and the natural response to that is to change the pipeline.

That has already happened once here. `metrics.py`'s own docstring records
`amount` reading **0% across the whole corpus**, looking exactly like a
product defect. It was the ruler that was wrong.

Run **`python -m eval.check_labels`** when you are done. It reports
malformed values and **never edits anything**.

---

## The shape of a label

```jsonc
{
  "reviewed": true,                    // false until you have checked every field
  "doc_id": "acme-invoice-001",        // leave as drafted
  "filename": "acme-invoice-001.pdf",  // leave as drafted
  "type_code": "INVOICE",              // INVOICE | CONTRACT | POLICY
  "mime_type": "application/pdf",      // leave as drafted
  "expected_page_count": 2,
  "degraded": null,
  "reference_text": "",                // optional, see below
  "fields": { ... },
  "line_items": [ ... ]                // optional, invoices only
}
```

`type_code` decides which fields are asked for, **so a wrong one makes
every field in the label wrong**. It is the first thing to check, and the
drafter's guess is deliberately crude so you do not trust it.

---

## Field values

### Dates — `YYYY-MM-DD`

```jsonc
"invoice_date": "2026-04-03"
```

ISO, always, regardless of what the document prints. `extraction.py`
parses whatever format the page uses and emits `.isoformat()`, so a label
written in the document's own format is compared as a string against the
ISO form and always loses.

`03/04/2026` is rejected, and the checker will **not** guess for you — it
offers both readings and asks which number is the day.

### Amounts — digits only

```jsonc
"amount": "1234.50",
"contract_value": "812079.95"
```

- A **string**, not a JSON number, so trailing zeros survive (`1234.50`,
  not `1234.5`).
- `.` as the decimal separator.
- **No currency symbol and no code.** `"$1,234.50"` and `"LKR 120,000.00"`
  are errors — `metrics._as_decimal` is anchored end to end, so anything
  it cannot parse falls back to a string comparison and is scored wrong
  even when the pipeline read the number perfectly.
- Thousands separators (`"1,234.50"`) do score correctly, because the
  comparison is numeric. The checker warns rather than errors, and the
  canonical form has none.
- A leading `-` is fine for a credit note.

### Currency — a code, in its own field

```jsonc
"currency": "USD"
```

One of **`USD`, `EUR`, `GBP`, `LKR`** (`metadata_field_options`, V30),
uppercase. The currency never appears in `amount`.

### Dropdowns

| field | allowed values |
|---|---|
| `classification_level` | `public`, `internal`, `confidential`, `restricted` |
| `retention_class` | `short_term`, `medium_term`, `long_term`, `permanent` |
| `currency` | `USD`, `EUR`, `GBP`, `LKR` |

`language`, `project_code` and `cost_centre` are dropdowns that V30 seeds
**no** options for, so any value is accepted and the checker leaves them
alone.

### Multi-select — a JSON list

```jsonc
"tags": ["audited", "renewal"],
"keywords": ["policy", "hr"]
```

`"audited, renewal"` is **wrong**: it folds to one string containing a
comma and never equals the two-element list the pipeline produces. Even a
single value is a list.

Order is not scored — `metrics.normalize_field_value` sorts before
comparing.

### Text

`invoice_number`, `vendor_supplier_name`, `contract_party`,
`reference_number`, `description`, `author`, `employee_id`,
`purchase_order_number` — exactly as printed. Case and surrounding
whitespace are folded away by the scorer, so do not fuss over them.

---

## A field the document does not contain: **delete the key**

```jsonc
// The document has no PO number:
"fields": {
  "invoice_number": "INV-4471"
  // purchase_order_number simply is not here
}
```

Not `null`, not `""`, not `"N/A"` — **delete it**. The scorer counts
recall over "fields the label has a value for", so an empty string is a
claim that the document shows an empty value, and it is scored as a
value the pipeline failed to find.

This applies to **mandatory** fields too. A mandatory field the document
genuinely lacks is a real and interesting case — it is exactly what
`requires_review` exists for. The checker warns so you can confirm it was
deliberate, and never errors.

---

## Line items — optional, and not scored yet

```jsonc
"line_items": [
  {
    "description": "Widget, blue",
    "quantity": "2",
    "unit_price": "10.00",
    "line_total": "20.00"
  }
]
```

- Exactly those four keys; any other key is an error.
- In the order they print down the page.
- Every number follows the **amount** rules above: a string, digits only,
  no symbol.
- Any of the four may be omitted if the document does not show it.
- Delete the whole key for a document with no line items.

**Nothing scores these today.** They are captured because typing them
while the document is already open in front of you is far cheaper than a
second pass over every document later, and `check_labels` validates them
now so what gets captured is at least well-formed. It also checks
`quantity × unit_price` against `line_total` and warns when they
disagree by more than a cent — the one error you cannot catch by reading.

---

## `reference_text` — optional

Leave it `""` unless you want character/word error rates for this
document, in which case paste the document's full text. It is scored only
where present.

---

## Finishing a label

1. Fix `type_code` first.
2. Correct every field against the document.
3. Delete every field the document does not contain.
4. Set `"reviewed": true`.
5. Delete `_README` and `_draft_notes` — nothing reads them, but leaving
   them in a reviewed label usually means the pass was not finished, and
   the checker says so.
6. Run `python -m eval.check_labels`.

```
[check] 24 reviewed label(s), 0 awaiting review

[check] reviewed labels -- 0 error(s), 2 warning(s):
  WARN   acme-invoice-001.amount: '1,234.50' has thousands separators; ...

[check] OK -- 2 warning(s), none of which change a score.
```

Exit code is **1** if there is any ERROR, **0** otherwise.
`--strict` fails on warnings too; `--include-pending` also reports on
unreviewed drafts, as information only.

An unreviewed label is never scored — `eval.run` counts it as pending.
Scoring against the pipeline's own draft output would measure nothing.
