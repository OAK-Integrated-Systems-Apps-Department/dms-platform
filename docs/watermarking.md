# Document Watermarking

Phase 1. Produces a watermarked **copy** of a document in its **own format**.
Nothing is converted and the original is never modified.

Feature flag: `dms.watermark.enabled`, **off by default** and intended to stay
off in deployed environments until switched on deliberately per environment.
While it is off the endpoints are not mapped at all, so they answer 404 - the
feature does not exist rather than existing and refusing.

---

## 1. Architecture

```
POST /api/v1/documents/{id}/watermark
        |
        v
WatermarkController  --rate limit-->  WatermarkRateLimiter (Redis, fails open)
        |
        v
WatermarkService            request-time validation
        |                   - access check (docm.documents.view + folder view,
        |                     + dvc.view-version-history.view when versionId given)
        |                   - format supported?            -> 415
        |                   - size within the format cap?  -> 413
        |                   - script renderable?           -> 422
        |                   - placeholders resolved and SNAPSHOTTED
        v
watermark_jobs row (status=pending, request_snapshot=WatermarkSpec)
        |
        v
WatermarkJobStarter.startAfterCommit    queues only after the row is committed
        |                               queue full -> row failed + 503 Retry-After
        v
WatermarkJobWorker          (job executor: 2 threads, bounded queue)
        |
        +-- JobIdentity.bind         runs as the requester, roles read live
        +-- DocumentContentReader    re-checks access NOW, not at request time
        +-- FileTypeInspector        bytes must still match the recorded type
        +-- draw()                   on the FORMAT executor, with a timeout
        |        |
        |        v
        |   WatermarkServiceFactory -> Pdf | Docx | Xlsx | Pptx | Image service
        |        |                         (all share WatermarkRenderer + FontChoice)
        |        v
        |   OoxmlGuards (OOXML only): OLE2? signed? zip bomb? truncated?
        |
        +-- store()                  staging location, encrypted,
        |                            {tenantId}/tmp/watermarks/{jobId}/{filename}
        v
status=completed, expires_at = now + output-ttl
        |
GET /api/v1/watermark-jobs/{id}/result   re-checks access and expiry, then streams
        |
        v
WatermarkOutputSweeper (scheduled)  deletes the file, keeps the row (status=expired)
```

### Why the mark is rendered once, as an image

PDF, Word, Excel, PowerPoint and JPEG have five unrelated ways of putting text
on a page, each with its own font embedding and its own encoding limits.
`WatermarkRenderer` draws the text once onto a transparent PNG and every format
embeds that same image. A non-Latin name therefore works everywhere without
embedding a font subset five different ways, and a new placement is written
once instead of five times.

The cost is honest: the mark is raster, so it is neither selectable nor
searchable in the output. `dms.watermark.limits.render-dpi` is the dial.

Text is laid out with `java.awt.font.TextLayout`, **not** `createGlyphVector`,
because the latter performs no complex-script processing - Arabic came out as
isolated unjoined letters and Hebrew left-to-right, with no missing glyph
anywhere to warn us.

---

## 2. Configuration

All under `dms.watermark.*`. Defaults are in `application.yml`; the V68 CHECK
constraints are deliberately **wider** - the database stops nonsense, these stop
bad watermarks, and only these are meant to be tuned.

| Property | Default | Notes |
|---|---|---|
| `enabled` | `false` | Off means the endpoints are not mapped (404). |
| `output-ttl` | `PT24H` | How long a produced copy is kept. |
| `max-active-jobs-per-user` | `2` | Hard cap; counted in the database. |
| `format-timeout` | `PT60S` | Then the job fails and its format thread is abandoned. |
| `stuck-after` | `PT15M` | A job still `processing` this long at startup is failed, not resumed. |
| `sweep-interval` | `PT15M` | `spring.task.scheduling.pool.size` is deliberately **not** changed. |
| `font-families` | DejaVu Sans, Noto Sans, Liberation Sans, Arial, SansSerif | First family that can display the whole text wins. |

### Per-format input caps and the heap arithmetic

Checked against `document_versions.size_bytes` **before a byte is read**.
Per format because the cost is not the file, it is what the library does with it.

| Format | `max-bytes` | Rough peak heap while processing |
|---|---|---|
| pdf | 25 MB | PDFBox is lazy; ~2-3x the file. |
| docx | 25 MB | ~3-4x; bulk is media, which POI holds as byte arrays. |
| pptx | 15 MB | ~4x; every slide part is a DOM. |
| xlsx | 10 MB | **~10-15x** - XSSF builds a full in-memory object model, and a 10 MB workbook is a few million cells. This is why xlsx is capped lowest. |
| image | 25 MB | Decoded size is what matters; see `max-image-pixels`. |

With the format pool at 4 threads, the worst realistic concurrent case is
4 x 25 MB docx ~ 400 MB, or 4 x 10 MB xlsx ~ 600 MB. Size the container heap
with that headroom, or lower `executor.format-max-size`.

### Limits

| Property | Default | Why |
|---|---|---|
| `limits.max-pages` | 2000 | PDF. |
| `limits.max-sheets` | 200 | xlsx. |
| `limits.max-slides` | 500 | pptx. |
| `limits.max-sections` | 100 | docx. **There is no page cap for docx and cannot be**: a .docx stores no page count - pagination is decided by whatever lays the document out. Sections are what actually cost work. |
| `limits.max-image-pixels` | 50 000 000 | Checked from the image header BEFORE decoding; a 50 MP decode is ~200 MB of `int[]`. |
| `limits.max-distinct-page-sizes` | 4 | Distinct geometries that get their own rendered tile; beyond this the largest is reused, scaled. |
| `limits.render-dpi` | 150 | What the mark is rasterised at. |
| `limits.max-render-pixels` | 16 000 000 | Hard ceiling on one rendered tile. |
| `limits.max-sheet-watermark-columns/rows/pixels` | 30 / 60 / 1600 | How far an Excel mark may stretch. |

### OOXML package guards, and ZipSecureFile

`OoxmlGuards` makes **one bounded pass** over the package before POI opens
anything, counting the bytes the inflater actually produces and stopping at a
cap. It never trusts the sizes declared in the zip headers, which an attacker
writes.

| Property | Default |
|---|---|
| `limits.min-inflate-ratio` | `0.001` |
| `limits.max-zip-entry-size` | `256MB` |
| `limits.max-uncompressed-bytes` | `300MB` |
| `limits.max-zip-entries` | `2000` |

**POI's own `ZipSecureFile` limits are deliberately left alone.** They are
JVM-wide statics shared with `DocumentFingerprints` (which opens every uploaded
Office file) and the import templates, and they are not safe to mutate per call
either - two format threads would race on the same field. POI 5.5.1's effective
defaults are a minimum inflate ratio of **0.01** (at most 100:1 expansion), a
maximum entry size of 4 GB and at most 1000 entries, and they stay in force
underneath our own pass.

Note that 0.01 is *stricter* than the `0.001` above, so applying our value
globally would have **loosened** the protection for every other feature. What
POI's defaults do not bound tightly is total inflated size - at 100:1 a 25 MB
upload may still reach 2.5 GB - and `max-uncompressed-bytes` is what covers
that.

---

## 3. Supported formats, and what `first-page-only` means in each

| Format | How the mark is applied | `first-page-only` |
|---|---|---|
| **PDF** | Appended to each page's content stream; existing content never rewritten. One `PDImageXObject` per page geometry (size + `/Rotate`), embedded once. | Page 1. |
| **Word (.docx)** | An anchored, behind-text picture in the header of **every section**, for every header type in use (default, first, even), creating a header where none exists. Each section measured from its own `w:pgSz`/`w:pgMar`. | Page 1, by turning on the section's `w:titlePg`. **The existing default header's content, including its images, is copied into the new first-page header** - without that, page 1 silently loses its letterhead. |
| **Excel (.xlsx)** | One floating picture per worksheet, anchored `DONT_MOVE_AND_RESIZE` so a row insert or a filter cannot move or fold it. Existing drawings, charts and images preserved. Chart sheets skipped (no cell grid). Hidden sheets **included**. | The first **visible** worksheet - a spreadsheet has no pages until it is printed. |
| **PowerPoint (.pptx)** | On the slide master and every layout, behind content - one shape marks a 500-slide deck. Slides that set `showMasterSp="false"` or are covered by an opaque full-bleed shape get their own picture, inserted **above** the covering shape but below the slide's own content. | Slide 1 only, and deliberately **not** the master. |
| **JPEG / PNG** | Composited onto the image. EXIF orientation is honoured. | The whole image - one page by definition. |

Everything else, including legacy `.doc`/`.xls`/`.ppt`, is **415**. The refusal
message is built from the registered services, so a format is advertised
exactly when something can produce it.

### Placeholders

Usable in a template's default text or in `customText`. Every one is resolved
**once, at request time**, and snapshotted onto the job — see §6.

| Token | Resolves to |
|---|---|
| `{username}` | The requester's **display name**, falling back to their login username only when the display name is blank. Not the login name: on a Keycloak-backed deployment that is frequently a 36-character UUID, which identifies nobody on a page and crowds out the rest of the line. |
| `{userEmail}` | The requester's email address. |
| `{date}` | Request time, `yyyy-MM-dd HH:mm UTC`. Always UTC and always labelled — a watermark is read by someone who has no idea what timezone the server was in. |
| `{documentId}` | The document's UUID. |
| `{documentVersion}` | The version number being watermarked. |
| `{watermarkId}` | The first 8 characters of the job id, so a leaked copy can be traced back to the audit entry that produced it. Visible text, never hidden. |

An unrecognised token is a **400** naming it and listing the valid ones — never
printed literally, because a typo like `{usernmae}` would otherwise be stamped
onto somebody's document and the person would believe they had identified
themselves when they had not.

### Placements

`tiled` (default), `centered`, `corner`, `header-footer` - the last draws a
line at the top margin **and** one at the bottom, because a mark only at the top
is lost the moment somebody photographs the lower half of a page.

Tiling is anchored to the surface rather than centred on it: the step is rounded
down to a whole number of intervals across the page so tile centres land on the
edges, and alternate rows are staggered by half a pitch. Centring instead left
the phase to chance, and on A4 it produced a clean unmarked strip down each
side - a border to crop to.

---

## 4. Error codes

| Code | Status | Meaning |
|---|---|---|
| `WATERMARK_UNSUPPORTED_TYPE` | 415 | Not a Phase 1 format. |
| `WATERMARK_TOO_LARGE` | 413 | Over the format's `max-bytes`, or too many pages/sheets/slides/sections. |
| `WATERMARK_TEXT_TOO_LONG_FOR_PAGE` | 422 | Would have to shrink below the readable floor. |
| `WATERMARK_NOT_RENDERABLE` | 422 | No installed font can draw this script. Never drawn as empty boxes. |
| `WATERMARK_PDF_ENCRYPTED` / `_SIGNED` / `_RESTRICTED` / `_DAMAGED` / `_EMPTY` | 422 | |
| `WATERMARK_OOXML_ENCRYPTED` | 422 | Password-protected (an OLE2 container). |
| `WATERMARK_OOXML_SIGNED` | 422 | Has an `_xmlsignatures/` part; any rewrite breaks the signature. |
| `WATERMARK_OOXML_DAMAGED` | 422 | Truncated, not a package, or POI could not read it. |
| `WATERMARK_OOXML_EXPANDS_TOO_FAR` | 422 | Zip bomb. |
| `WATERMARK_OOXML_TOO_MANY_PARTS` | 422 | |
| `WATERMARK_XLSX_EMPTY` / `_NO_WORKSHEET`, `WATERMARK_PPTX_EMPTY` | 422 | |
| `WATERMARK_QUEUE_FULL` | 503 | With `Retry-After`. The job row is failed so it does not count against the active cap. |
| `WATERMARK_UNAVAILABLE` | 503 | Font probe failed at startup. |
| `WATERMARK_IDENTITY_UNAVAILABLE` | - | Worker-side, retryable: Keycloak could not confirm the requester's roles. **Not** reported as lost access. |
| `WATERMARK_ACCESS_LOST` | - | Worker-side: access really was lost, or the document was deleted. |
| `WATERMARK_TIMED_OUT` / `WATERMARK_FORMAT_CAPACITY` | - | Worker-side; the second is retryable. |

---

## 5. Audit events and metrics

**Audit** (module `DW`):

| Action | When |
|---|---|
| `WATERMARK_TEMPLATE_CREATED` / `_UPDATED` / `_DELETED` | Template CRUD. |
| `WATERMARK_REQUESTED` | A job is accepted. |
| `WATERMARK_COMPLETED` / `WATERMARK_FAILED` | The worker finishes. |
| `WATERMARK_RESULT_DOWNLOADED` | **HIGH severity** - a copy was actually taken. Written only on a successful download; an expired (410) or unfinished (400) attempt writes nothing. |

Permission denials are **not** a watermark action. The endpoints are
`@PreAuthorize`-guarded, so a denial goes through `GlobalExceptionHandler` to
`AccessDeniedAuditor` and is recorded as the generic `ACCESS_DENIED`
(objectType `ENDPOINT`, no objectId), exactly as for every other endpoint.

**Metrics** - tags are `format`, `outcome` and `reason` only, all closed sets.
Never tenant, user, document or job id: on a multi-tenant system tagging by
tenant makes the cardinality the number of customers, and tagging by document
makes it unbounded.

| Metric | Type | Tags |
|---|---|---|
| `watermark.jobs.started` | counter | `format` |
| `watermark.job.duration` | timer | `format`, `outcome` |
| `watermark.jobs.failed` | counter | `format`, `reason` (from the closed `WatermarkFailure` enum) |
| `watermark.format.threads.abandoned` | gauge | none |

`watermark.format.threads.abandoned` is the one to alert on. POI and PDFBox do
not check `Thread.interrupted()`, so a job that overruns its timeout cannot be
stopped - the thread is abandoned, not recovered. **If this gauge reaches
`executor.format-max-size`, watermarking has stopped working and only a restart
will fix it.**

---

## 6. Security model

- **Request time**: `docm.documents.view` + folder access, plus
  `dvc.view-version-history.view` when a `versionId` is named. TENANT_ADMIN
  bypasses the fine-grained checks as everywhere else.
- **Worker time**: the job runs as the requester via `JobIdentity.bind`, with
  their roles read **live** - access is re-checked when the copy is produced,
  not just when it was asked for. A Keycloak outage fails the job retryably
  rather than quietly running with less access than the user really has.
- **Download time**: access and expiry are re-checked again before a byte is
  served, so losing access to the document also ends access to copies made
  from it.
- Outputs are stored **encrypted** through `ObjectStorageServiceFactory`, in the
  tenant's own staging location, and swept on expiry.
- Templates: tenant admins manage their own; system templates
  (`tenant_id IS NULL`) are read-only and enforced in the service.
- The watermark text is sanitised (bidi and invisible controls stripped, ZWJ and
  ZWNJ kept) and placeholders are resolved **once** at request time and
  snapshotted into `watermark_jobs.request_snapshot`. Nothing downstream
  re-resolves them, which is what makes the audit entry trustworthy: the text
  recorded when the job was requested is necessarily the text on the page, even
  if the user was renamed or the template rewritten in between.

---

## 7. Limitations

1. **The mark can be removed.** In Word, Excel and PowerPoint it is an ordinary
   picture, so a recipient who opens the file can delete it. Phase 1 makes
   provenance visible; it does not make it tamper-proof. Nothing short of a
   flattened render could, and that would stop being an Office document.
2. **The in-app preview may not show it.** SheetJS and docx-preview do not
   render header anchors or floating pictures the way Office does. Check the
   downloaded file in desktop Office or LibreOffice.
3. **In Excel the mark floats above the cells.** Excel offers nothing behind
   them, which is why the minimum opacity is a floor rather than something a
   user can set to zero. A floating picture in desktop Excel can also take the
   click when somebody selects a cell underneath it, so a recipient may need to
   click past it or drag it aside to edit those cells.
4. **JPEG output loses EXIF**, including GPS, because the image is re-encoded.
5. **Signed documents are refused** (422). A watermarked copy whose signature no
   longer verifies is worse than no copy: it looks tampered with, because it is.
6. **Password-protected files are refused** (422).
7. **The mark is raster**, so it is not selectable or searchable in the output.
8. **Large spreadsheets are slow.** See below.

### Measured latency at the per-format ceiling

Measured through the real services including the `OoxmlGuards` pass, on a
developer machine; median of 3 runs.

| Format | Size | Median | Of which guard |
|---|---|---|---|
| docx | 25.3 MB | **0.7 s** | 11 ms |
| pptx | 15.5 MB | **0.8 s** | 6 ms |
| xlsx | 9.9 MB | **17.3 s** | 94 ms |

The guard pass is negligible everywhere (≤0.5%). docx and pptx are comfortably
inside the 5 s p95 target.

**xlsx at its 10 MB cap is ~3.4x over that target**, and the time is almost
entirely POI's XSSF parse and serialise of a few million cells - not
watermarking, which is one picture per sheet. Worth knowing, but it is not a
user-facing wait: the POST returns 202 in milliseconds and the copy becomes
ready later. What it does affect is throughput - the job pool is 2 threads, so
two large workbooks occupy it for ~17 s each. If that matters, lower
`max-bytes.xlsx` rather than raising `format-timeout`.

---

## 8. Phase 2 seams

Deliberately left in place, unused:

- **`WatermarkService.apply(documentId, request, idempotencyKey)` is the single
  entry point.** Auto-watermarking on download, rule-based watermarks and bulk
  watermarking all call this rather than reimplementing the pipeline.
- **`watermark_jobs.source`** already exists with a CHECK constraint currently
  allowing only `USER_REQUEST`. Adding `AUTO_ON_DOWNLOAD` or `RULE` is a
  migration that widens the constraint, not a schema change.
- **`document_types.requires_watermark`** is untouched in behaviour - it is
  stored and returned but **not enforced anywhere yet**. Phase 2 enforcement
  reads it; nothing today depends on it being false.
- `WatermarkFormatService` is a plain interface and the factory advertises only
  registered implementations, so a new format is one class.

Explicitly **out of scope** for Phase 1: auto-watermark on
upload/view/download/print/export, blocking un-watermarked downloads,
rule/audience-based watermarks, expiring text, invisible or forensic
watermarking, translation, logo/image watermarks, bulk watermarking, and any
frontend work.

---

## 9. Looking at the output

`tools/watermark-samples.sh` produces one watermarked file per format,
placement and page scope, converts the Office ones to PDF through the project's
Gotenberg container and rasterises every page into PNGs outside the repository.

It is not part of CI on purpose. Every watermark bug that mattered - blank
tiling, unjoined Arabic, Hebrew running the wrong way, a squashed Excel mark, a
font that resolved differently under load - passed every assertion and was
obvious within two seconds of looking at a page. Gating CI on image comparison
would instead mean a golden-image suite that breaks on every font package
update, so the evidence is produced on demand and a person judges it.
