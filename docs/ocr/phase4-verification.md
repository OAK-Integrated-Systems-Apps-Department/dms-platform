# Phase 4 — Outputs and UX

Delivery plan item 4: *searchable PDF rendition, review queue,
field-highlight review UI, correction capture* (plus friendly error
messages for every failure code).

Written as it was built. Where something was measured, the number is
here; where something was decided, the alternative that was rejected is
here too.

---

## 4a — Searchable PDF rendition

See `backend/workers/ocr-worker/README.md` § "Searchable PDF rendition"
for the shipped behaviour. The four corrections made after the first cut:

1. **No silent fallback.** A rendition that cannot be built records
   `flags.searchable_pdf_failed` with a reason rather than quietly
   producing nothing. The eval now treats that flag as a failure and
   reports a per-corpus success rate, which is how the next regression in
   this area gets noticed.
2. **The image layer is the source page at its native resolution**, not
   the 1.81 MP OCR render. The OCR render exists to feed an engine; a
   human reading the rendition should not inherit that ceiling.
3. **Native and hybrid pages are copied from the original PDF unchanged**,
   with no duplicate text layer. A page that already had real text keeps
   exactly that text — overlaying a second, invisible copy would double
   every search hit and every copy-paste.
4. **The text layer is LINE-level, not word-level** — stated plainly in
   the README, because it determines what a PDF reader's selection
   granularity will be.

### Rotated pages

The overlay origin was swept over 4 rotations × 8 corner choices against
real fixtures. The mapping that survived is in `searchable_pdf.py`; the
worst residual offset is **1.2pt** at 90°, **8.9pt** at 180°.

**Known limitation:** the 8.9pt 180° offset is close to one line height
at small type. It was checked against the worst real case in the corpus —
an 8.9pt line — and the overlaid text still falls within its own line's
box rather than the neighbouring one, so selection picks the right line.
It is recorded here rather than fixed because closing the remaining
offset means a per-glyph baseline correction for a case that currently
selects correctly.

---

## 4b — Review queue

`GET /api/v1/documents/review-queue` — every latest version in the tenant
that failed OCR or came back flagged, newest first, each with the reason.

### The reason is the feature

A queue that lists documents and nothing else makes the reviewer open
every one of them to find out what is wrong, which is most of the work
the queue exists to save. So every row carries one sentence a human can
act on: a failed document gets the friendly message for its classified
error code, a flagged one gets the rule that fired and the fields or
pages it fired on.

`ReviewQueueMapperTest` pins the two cases where a row would most easily
end up blank — a failed version (which has no `review_reasons` at all,
because it never got far enough to compute any) and a version whose
reasons cannot be parsed. Both still produce a sentence.

### Why `requires_review` became a column (V41)

It was computed in Phase 3 but lived only in the MinIO output artifact and
the `.completed` event payload. Both are write-once records of a moment,
not queryable state: building a queue from them would mean fetching every
document's artifact out of object storage to filter in memory, or keeping
a parallel projection that can drift from the artifact it mirrors.
Neither is a queue.

It is written **unconditionally**, including the false case — a re-run
that fixes a document has to take it out of the queue, and leaving the old
`true` behind would strand it there forever. `test_pipeline_transaction.py`
covers exactly that: clean document not queued, repaired file queued with
its reason, re-run removes it.

The partial index `ix_document_versions_review_queue` covers exactly the
query's predicate; the repository uses native SQL so a JPQL rewrite cannot
silently drift off it and start scanning every version in the tenant.

---

## 4c — Review screen

`/documents/{documentId}/review`: the page preview with extracted-field
highlights, each field's value / confidence / status, click-to-scroll,
accept / edit / rubber-band re-extract, and the line items read-only.

### Everything is in normalised coordinates

The stored `bbox_norm` values are fractions of page width and height.
The viewer places every highlight with percentage offsets, so a box at
`x0 = 0.25` sits at `left: 25%` whatever the preview rendered at and
whatever the browser has scaled the image to. Nothing is re-measured on
resize, and a region dragged at one zoom means the same region at
another. `review-page-viewer.test.tsx` pins this, including the two ways
a drag goes wrong: dragged upward-left (must still produce x0 < x1), and
dragged off the edge of the page (clamped, so a few pixels of overshoot
do not cost the reviewer a 422 they did not earn).

### Two new read-only worker routes, and why they are new

`/ocr/page-image/{version_id}` and `/ocr/rubber-band/preview`. Neither
claims a job, writes a row, or publishes an event.

The existing `/ocr/rubber-band` **finalizes** — it replaces the version's
entire field set and page list with whatever the one crop produced.
Pointing the review screen at it would wipe every other field on a
document each time a reviewer re-checked one of them.
`test_review_readonly_pipeline.py` counts rows in `processing_jobs`,
`outbox_events`, `ocr_outputs` and `document_ocr_field_results` before and
after both operations and asserts they are unchanged, so a future change
that "just reuses" the finalizing path fails there rather than in
production.

### `document_versions.page_count` (V42)

The screen offers a page selector before it fetches a single page. The
count previously existed only as the length of the `pages` array inside
the MinIO artifact — so reading it meant fetching a whole document's JSON
out of object storage to learn one integer, and for a **failed** document,
where there is no artifact at all, it could not be learned at any price.
That is exactly the document a reviewer most needs to page through.

The alternative considered and rejected: ask ocr-worker for page 0's image
purely to read its `X-Page-Count` header and throw the pixels away.

Only a full-document run writes it. A zone or rubber-band run visits a
subset of pages and passes nothing, rather than shrinking a 3-page
document to "1" — pinned by
`TestPageCount::test_a_zone_run_does_not_shrink_it`.

### The field list is driven by what was requested, not what came back

A mandatory field OCR never found is the single most important thing on
this screen, and it is exactly the field with no result row to iterate
over. Driving the list off the rows would drop it silently and show the
reviewer a tidy screen of everything that worked, with no sign of the
thing that sent them here. So `ReviewFieldResponse` is built from the
version's `requested_fields`, joined to the rows, with anything OCR found
that was never requested appended rather than hidden.

### Transactions do not span the worker call

`getPageImage` and `readRegion` are deliberately **not** `@Transactional`:
the tenant check is one indexed primary-key read, and then the call blocks
on ocr-worker rendering a page — seconds, sometimes. Holding a pooled
connection open across that is the long-transaction pattern Phase 1
removed from the OCR path in the first place.

### Line items are read-only, on purpose

A line has four values and a position, and "wrong" often means two rows
were merged or one was split rather than a number being misread. Shipping
an editor for that before there is evidence of what reviewers actually
need to fix would be guessing. Showing them — so a reviewer can see that
a bad total came from a bad line — is not.

---

## 4d — Correction capture

`POST /api/v1/documents/{documentId}/review`, into
`document_ocr_corrections` (V43).

### Why a separate table

Overwriting `document_ocr_field_results` with the corrected value would
make every screen look right and destroy the only record of how often OCR
was wrong — which is the number the eval corpus can only estimate and
real usage can actually measure. Both values survive, together, with the
confidence OCR had at the time.

### Acceptances are recorded too

A table of only corrections has no denominator: forty corrections means
nothing without knowing whether fifty fields were reviewed or fifty
thousand. `action` carries the distinction, so "extracted-status
precision, as measured in production" is one query rather than an
estimate. And "accepted at 0.62" is the observation that says the
low-confidence threshold is set too low.

### `source` separates two different defects

`typed` means the extraction was wrong. `region` means OCR could read the
text perfectly well once pointed at the right part of the page — a
localisation failure, not a recognition one. Collapsing them would hide
which half of the pipeline to fix.

### Three ways the measurement could have been quietly ruined

All three are pinned in `ReviewDecisionApplierTest`:

1. **An edit that changed nothing counted as a correction.** Opening the
   editor and closing it would otherwise count against OCR's measured
   accuracy. `effectiveAction` downgrades it to an acceptance.
2. **The client's echo of the OCR value trusted on an acceptance.** A
   client that echoed it wrongly would store a correction as an
   acceptance. The server reads its own copy.
3. **The queue emptying itself on the first save.** A document leaves the
   queue only when every field that actually needed attention has been
   settled — and a decision that leaves a mandatory field blank settles
   nothing. A queue that empties itself is worse than a long queue,
   because it is lying.

Corrected values are also written back to `document_metadata_values` with
`enforceMandatory=false`: a reviewer fixing three of four missing fields
must be able to save the three. The document stays queued for the fourth,
which is the honest outcome — refusing the save would lose their work
instead.

---

## 4e — Friendly errors

### A real defect this turned up

`OcrWorkerClient` translates ocr-worker's status codes into sentences and
throws `ResponseStatusException`. Spring renders those as a
`ProblemDetail`, whose text lives in `detail` — but every client in this
repo reads `message` (see the frontend's `api-fetch`). The carefully
chosen sentence would have been dropped on the floor and the user would
have got the generic fallback, which is the exact failure mode the
uniform error shape exists to prevent.

Fixed with `GlobalExceptionHandler#handleResponseStatus`, which emits the
standard `ErrorResponse` shape.

### A drift guard, in the language the codes are defined in

The nine error codes are defined in Python (`errors.py`); the sentences
live in Java (`OcrErrorMessages`). Nothing links the two files, so adding
a failure mode and forgetting the message is a one-line change with no
visible symptom — the user just gets "OCR could not process this
document" for a problem whose exact cause was known.

`tests/test_error_messages_cover_every_code.py` reads the Java file from
the Python suite. That is unusual and deliberate: a Java test reading
`errors.py` has the same awkwardness and catches the drift a release
later, on the side that is not changing. It also asserts no message leaks
an internal name (`paddle`, `docling`, `fitz`, `Traceback`, …) and that
each one reads as a sentence.

### Nothing else renders a raw error

`GlobalExceptionHandler`'s catch-all returns "An unexpected error
occurred" and logs the stack trace server-side. A grep of the frontend for
`.stack`, `String(err)` and `JSON.stringify(err)` returns nothing; every
call site uses the message from the uniform error body or its own
fallback sentence.

### A failed document is not a dead end

The review screen offers "Try OCR again" for a failed document whose code
is retryable. `UNSUPPORTED_FORMAT` gets no button rather than a false
promise — a format we cannot read will never succeed on a retry.

---


---

## Three defects found while wiring this, all silent

Neither would have failed a test or logged anything; both would have
produced a screen that looked right.

### A corrected `multi_select` value would have become one tag

`document_metadata_values` holds one row per selected option. The review
screen joins a multi-select value with `", "` for display, and the
correction log stores it joined — one text column, one reviewer decision.
Handing that joined string to `saveValues` would have created a single
tag literally named `finance, q3`.

Fixed by carrying `dataType` on `ReviewFieldResponse` and splitting on
the way back out (`DocumentServiceImpl#splitIfMultiSelect`). Pinned by
three tests, including the one that matters in the other direction: a
comma inside ordinary text is just a comma, and a `text` field must not
be split.

### `raw_cells` would always have come back empty

`readJson` coerces to `List<String>` and swallows the failure as an empty
list. `raw_cells` is a JSON **object** keyed by column name (ocr-worker's
`line_items.py`), so every line item's raw cells would have arrived as
"no raw cells" rather than as an error — exactly backwards for a field
whose whole job is to show the reviewer what the parse lost. Fixed with
`readAnyJson`, which preserves the shape and logs when it genuinely
cannot read one.

---


### A reviewer's correction was stored with its field rules unchecked

`saveReview` calls `saveValues(..., enforceMandatory=false)`, because a
reviewer fixing three of four missing fields must be able to save the
three. But that flag gates the **whole** `MetadataValidationService.validate()`
call inside `DocumentMetadataServiceImpl#writeValues`, not just its
mandatory arm — the name says one thing and the code does another. So a
corrected value went in without its configured rules (option membership,
max length, patterns, ranges) ever running. Type coercion still applied
(`buildRow` throws on an unparseable date or number, and `source="manual"`
means `bestEffort` is false so it propagates), which is why nothing looked
broken.

Fixed with `DocumentServiceImpl#validateCorrections`, which runs
`validate()` explicitly on the submitted values before anything is
written, and drops exactly two classes of result: `MANDATORY_FIELD_MISSING`
(because `validate` walks the whole schema, so a partial submission always
reports every mandatory field the reviewer did not touch — the situation
this endpoint exists to work through), and errors about fields not in this
submission (the reviewer is answerable for what they typed).

Rejecting before any write, rather than per field, is deliberate: the save
is one transaction, so a mid-batch failure would roll back corrections
already recorded and the reviewer would not know which survived.

---

## Tenant isolation on the review endpoints

`ReviewEndpointTenantIsolationTest` — one test per endpoint, tenant B
asking for tenant A's document:

| endpoint | result |
|---|---|
| `GET /{documentId}/review` | 404 |
| `GET /{documentId}/pages/{page}/image` | 404, **and ocr-worker is never called** |
| `POST /{documentId}/read-region` | 404, **and ocr-worker is never called** |
| `POST /{documentId}/review` | 404, and no correction row, no metadata write, no version save |
| `GET /review-queue` | scoped to the caller's tenant; empty page, never another tenant's rows |

The "never called" assertions are the ones that matter. `getPageImage`
and `readRegion` forward a version id to ocr-worker, which has no notion
of who is asking — it answers for **any** version id presented with the
internal token. The check in `DocumentServiceImpl` is the only thing
between one tenant and another tenant's page images, so the test asserts
the worker was not reached at all rather than merely that the caller got
an error.

404 rather than 403, identically to "no such document": a 403 would
confirm the id exists in some other tenant. Two further tests pin that —
an unknown id fails identically, and the message names neither the owning
tenant nor the document title.

## Corrections are `manual`, and OCR cannot overwrite them

`ReviewCorrectionsAreManualTest`, plus the existing
`DocumentMetadataServiceApplyOcrFieldsTest#manualOverrideIsNeverOverwrittenByAFreshOcrResult`.

- A correction is written through `saveValues`, which is
  `writeValues(..., source="manual", ...)`.
- `applyOcrFields` skips any field whose stored source is `manual`.
- All three OCR write paths go through that one method —
  `OcrEventsConsumer` (the event), `OcrFieldsReconciler` (the backstop),
  and `DocumentServiceImpl`'s poll-time re-apply. There is no fourth.
- An **acceptance** is deliberately not written back at all: re-saving an
  unchanged value would touch `document_metadata_values` and its audit
  trail on every review, and would flip an OCR-sourced value to `manual`
  that nobody actually changed. An edit that changed nothing is treated
  the same way.

---

## Test counts

| suite | tests |
|---|---|
| ocr-worker (`pytest`) | 746 passed, 2 deselected |
| dms-core (`mvn test`) | 109 passed |
| frontend (`vitest`) | 45 passed |

New in Phase 4b–4e: `ReviewQueueMapperTest` (7),
`ReviewDecisionApplierTest` (16), `OcrWorkerClientTest` (3),
`test_review_endpoints.py` (12), `test_review_readonly_pipeline.py` (9),
`test_error_messages_cover_every_code.py` (4),
`review-queue-table.test.tsx` (4), `review-page-viewer.test.tsx` (8),
`review-field-panel.test.tsx` (13).
