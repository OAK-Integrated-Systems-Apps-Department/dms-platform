# ocr-worker

Python microservice implementing Oak DMS's OCR & Scanning module
(requirement-registry.md section 2.4, OS-FR-001..006 / OS-NFR-001..006):
full-document OCR, zone OCR, rubber-band OCR, and structured field
extraction, stored as searchable metadata.

## Auth & service exposure -- internal-only

ocr-worker has **no login of its own** and is **never reachable from
outside the docker network** -- there is no published host port for it in
`infra/local/docker-compose.yml`. The access model is:

```
frontend -> dms-core (Keycloak-authenticated) -> ocr-worker (internal-only)
```

- ocr-worker does not validate JWTs or read cookies. It trusts
  `tenant_id`/`user_id`/`document_version_id` passed explicitly by the
  caller -- dms-core in production; for now, whatever calls the dev-trigger
  endpoint (`scripts/dev/smoke_test_ocr.py`, `scripts/dev/publish_fake_scan_event.py`).
- **Since Phase 1 the production trigger is not an HTTP call at all.**
  dms-core's upload/rerun writes a `document.version.ocr.requested` outbox
  event in the same transaction as the `document_versions` row, and
  ocr-worker consumes it off `ocr-worker.q.ocr-requested`. `POST
  /ocr/process/{version_id}` survives only as a dev-trigger and for the
  synchronous zone/rubber-band routes. See
  `docs/events/ocr-scanning-events.md` for the wire contract.
- Every `/ocr/*` route additionally requires an `X-Internal-Token` header
  matching `INTERNAL_SERVICE_TOKEN` (see `infra/secrets/ocr-worker.secrets.env`).
  This is defense in depth, **not** an authentication scheme -- the real
  boundary is the missing published port. `/healthz`/`/readyz` don't
  require it (container-orchestration probes only, low risk).
- dms-core's `document`/`storage` modules and the frontend upload/view page
  now exist, so the full `frontend -> dms-core -> queue -> ocr-worker` path
  is real. Anything that talks to ocr-worker directly still has to run *on
  the docker network*, not from the bare host, because there is no
  published port to reach from outside it.

## Native-text vs. PaddleOCR decision logic

Docling and PaddleOCR are not redundant with each other -- each page is
routed at OCR time:

1. Load the document with Docling (`DocumentConverter`, its own OCR
   backends disabled -- see below).
2. **Route the page** on the quality and coverage of its text layer, not
   merely on whether one exists. `native_text` (Docling's text, used
   as-is, confidence `1.0`, PaddleOCR never called), `paddleocr` (no
   layer, or a junk layer), or `hybrid` (a good layer plus image regions
   it does not explain -- text kept, those regions OCR'd and merged).
   **See "Per-page routing" below for the checks, the thresholds and what
   is recorded.** Until Phase 2 Step 7 this step was "any text at all
   means native_text", which returned confident nonsense for a scan
   carrying an invisible `(cid:NN)` layer and silently dropped the body
   of a typed-header-over-scan page.
4. **Zone OCR and rubber-band OCR always run PaddleOCR** on the cropped
   region, regardless of whether the underlying page has a native text
   layer -- the user asked for OCR on a specific visual region, not a
   text-layer lookup, so the native-text shortcut never applies to those
   two flows.
5. Docling's own built-in OCR backends are disabled (`do_ocr=False`) --
   PaddleOCR is the only character-recognition engine in this system.
   Verified live: left at its default, Docling downloads and runs RapidOCR
   on every image-only page on top of what this service does with
   PaddleOCR, duplicating the work for nothing.
6. Docling's table-structure model (TableFormer, `do_table_structure=True`)
   IS enabled -- unlike RapidOCR, it doesn't duplicate PaddleOCR's job,
   and it's the only way a `native_text` page's tables come back as a real
   grid instead of flat, ungrouped text lines. Only applies to
   `native_text` pages: a page routed to PaddleOCR (no text layer at all)
   can't have its table cells structured, since TableFormer has no text to
   work with either (PaddleOCR's own output has no table awareness).

Per-page detection is real, not heuristic: it checks whether Docling's own
converted `DoclingDocument.texts` has any entries anchored to that page
(`ProvenanceItem.page_no`) -- verified against the real installed
`docling-core` with an actual native-text PDF (2 real text items) and an
actual image-only PDF (0 text items). See `src/ocr_worker/layout.py`'s
`native_text_blocks_for_page`.

**No silent fallback on a Docling/PaddleOCR failure.** If Docling's
`DocumentConverter` can't be constructed, or `.convert()` throws, or
PaddleOCR throws, that exception propagates all the way to
`OcrPipeline`'s outer handler, which records it as a **real failure**:
`document_versions.ocr_status='failed'` and the actual exception message
in `processing_jobs.last_error`. There is no code path that catches one of
these errors and returns placeholder/empty OCR-shaped data instead.

## Preprocessing

Applied to a rasterized page before PaddleOCR sees it, individually
toggleable, and recorded in the output's `pages[].preprocessing` (see
"Output contract"). Only the first of production prompt section 3.4's
five steps exists so far; deskew, perspective correction, denoising and
CLAHE are Phase 2 step 6.

### Image quality assessment (`OCR_QUALITY_ENABLED`, default on)

Production prompt section 3.3. Every **rasterized** page is measured into
`pages[].quality`: `effective_dpi`, `blur_score`, `contrast`,
`skew_angle`, plus the `thresholds` they were judged against so a reason
can be read back without this worker's config. A native-text page is
never rasterized and so has no quality block -- `measured: false` and a
missing block are different facts, and only the second means "nobody
looked".

Below a threshold the page also gets `flags.poor_image_quality: true` and
`flags.poor_image_quality_reasons`, drawn from `LOW_RESOLUTION`,
`BLURRY`, `LOW_CONTRAST`. **A page is never rejected for quality** --
3.3 is explicit, and the flag exists to drive the "please re-scan at a
higher resolution" message and Phase 3's `requires_review` rule.

Four decisions worth knowing:

- **`effective_dpi` comes from the source raster, not the render.** A 300
  DPI scan whose render was capped by `OCR_RENDER_MAX_MP` would otherwise
  be reported as low resolution, telling the user to re-scan a page whose
  scan is already excellent.
- **It averages both axes.** A standard fax is 204x98 DPI; reporting only
  the width would call it fine.
- **Contrast is RMS (std-dev), not Michelson.** Peak-to-peak is decided
  entirely by the single brightest and darkest pixel, so one speck of
  dust pins a uniformly grey page near 1.0 -- exactly the washed-out scan
  this check is for.
- **Skew is reported but never raises the flag.** It is an input to
  deskewing, not a quality verdict: a sharp, well-lit, high-resolution
  page sitting at 1.5 degrees is not one anybody should be asked to
  re-scan. It is measured *after* orientation correction, so it is the
  residual angle, not 90 degrees for a sideways page.

### `/Rotate` normalisation (`OCR_NORMALIZE_PAGE_ROTATION`, default on)

A page's `/Rotate` attribute is baked into its content before Docling
sees the file. This is not about what we extract -- PyMuPDF already
applies `/Rotate` for rendering and text, and normalisation was verified
to move every word by **0.000pt**. It is about what Docling's layout
model does with a page stored sideways: **138.9s against 2.3s** on the
same page, because it invents a table that is not there. See
docs/ocr/phase2-verification.md Step 7.

### The table-structure bound (`OCR_TABLE_TIMEOUT_PER_PAGE_S`, default 20s)

TableFormer's cost follows the grid it predicts, and nothing in Docling's
API bounds that prediction. Measured: a **378x2** grid on a page holding
24 cells, 87 seconds, and all 24 cells then dropped for matching no row
or column band -- 87 seconds that produced nothing.

On the deadline the page keeps all its text, loses only table structure,
and carries `flags.table_structure_skipped: true`. **Abandoned is the
honest word**: a thread running native torch code cannot be killed, so it
keeps burning CPU until it finishes on its own. What the bound buys is
that the *document* stops waiting for it. Bounding the process is the
pool worker's hard-kill ceiling, which is separate and still applies.

Each page gets its own executor, deliberately -- sharing one meant an
abandoned prediction still owned the only worker thread, so the next page
queued behind the work we had just refused to wait for and timed out too.
One bad page silently cost every page after it
(`test_one_slow_page_does_not_cost_the_others`).

### Whole-page orientation (`OCR_ORIENTATION_ENABLED`, default on)

A page that arrives sideways or upside-down is very nearly a total loss
without this. Measured on the eval corpus
(`eval/reports/baseline-2026-09-25.json`, recorded before this existed):
the same documents scored **95.3%** field accuracy upright and
**11.7-14.7%** rotated 90/180/270, with CER rising from 0.03 to ~0.82.
Sideways scans and phone photos are not an edge case in a DMS.

`src/ocr_worker/orientation.py` classifies each rasterized page with
PaddleOCR 3.x's own `PP-LCNet_x1_0_doc_ori`, rotates the raster upright,
runs OCR on it, and then **maps every bbox back into the page's own
coordinate space** before it leaves the worker.

That last part is why this does not simply set
`PaddleOCR(use_doc_orientation_classify=True)`. Inside PaddleOCR's own
pipeline the doc preprocessor rotates the page and returns every box in
the *rotated* image's space -- which would silently invalidate
`bbox`/`bbox_norm` against the page a viewer actually renders and a
review UI draws highlights over. Here the rotation and its inverse are
both ours, so a consumer never has to know a rotation happened.

Internally the split is: `lines_by_page` (what spatial field matching
works from) stays in **reading** space, because "the value is to the
right of this label" only means anything upright; `blocks[].bbox` and a
matched field's stored `source_bbox` are mapped back to the **page's**
space. `tests/test_orientation.py` pins both directions against real PIL
rotations.

Cost, measured in the running container: **1 MiB** marginal RSS per pool
worker (the weights are a ~7MB PP-LCNet, and paddle's framework import is
already paid for by the OCR engine) and **0.02-0.07s** per page against
~20s for that page's actual OCR.

The decision is gated on `OCR_ORIENTATION_MIN_CONFIDENCE` because the
error is asymmetric: correcting a rotated page is worth ~83 accuracy
points and rotating an upright one costs the same. Below the floor the
prediction is recorded with `"applied": false` and the page is OCR'd
as-is -- never rejected. Note that the model's score carried little
discriminative signal on the corpus (~0.92 for every page regardless of
condition), so the default is a floor against a degenerate page, not a
calibrated precision/recall trade-off.

**Not applied to zone or rubber-band OCR.** A rubber-band box is drawn by
the user on the page as displayed, so reorienting underneath it would
change what their own selection means. Zone templates are authored
upright and arguably should be applied in reading space, but no document
type in this app has zone rows configured (see "Zone / structured-field
templates"), and the eval corpus does not exercise that path -- so it is
recorded as carried-forward work rather than changed unverified.

### The render budget

`render_page` caps the rendered **area** of a page at `OCR_RENDER_MAX_MP`
(1.81 MP), not its longest side. Two separate things drive that.

**Why a cap at all.** Measured, not chosen
(`scripts/dev/step4_resolution_probe.py`,
`scripts/dev/step4_fineprint_probe.py`; full write-up in
`docs/ocr/phase2-verification.md` Step 4):

- **~6 megapixels per inference call is a hard ceiling** at the current
  `mem_limit`. A 6.0 MP page and a 5.6 MP *crop* are both OOM-killed
  (`OOMKilled=true`, exit 137) with one process at 8.5 GiB — and
  production runs two. Memory grows ~0.7 GiB per megapixel.
- **The accuracy gain measured zero.** 137 and 200 DPI produce
  byte-identical output on a full page (same CER, same character count).
  On the 6pt fine print — the region the eval corpus carries specifically
  to be resolution-sensitive — character error rate is **0.0000 at 137,
  200 and 300 DPI alike**, for 3× the time.

The reason is in the models: PaddleX detection is `limit_type: min`,
`limit_side_len: 64`, so it never downscales and really does pay for
every extra pixel — but recognition resizes each line crop to a fixed
height regardless, so an 11px line and a 25px line arrive at the
recogniser the same size.

This does **not** say resolution is useless on a real scan — the eval
corpus is synthetic, so it can veto the change but not prove that. It is
the first thing to re-test when a real labelled corpus exists.

**Why area rather than longest side.** A longest-side cap spends the
memory budget badly on anything that is not roughly A4-shaped. A
2000×500pt landscape page hit the old 1600px limit at 1600×400 = 0.64 MP
— a quarter of what an A4 page was allowed, for no reason but its aspect
ratio. Memory is driven by pixels, so the cap is denominated in pixels.

`max_megapixels=None` means the default budget, **not** "uncapped". That
was a real bug for one commit: with None meaning uncapped, a caller that
simply forgot rendered a 3000×4000pt page at 12500×16667 = 208 MP. The
safe value is the one you get by forgetting.

### Discarded scanner pixels are reported

The budget means a genuine 300- or 600-DPI scan is downsampled on
arrival. That is a legitimate trade against a measured ceiling, but doing
it silently is not: a high-DPI scan that reads badly is
indistinguishable from a bad scan unless the output says so.

When a page carries a raster larger than what was rendered, the worker
logs it with both sizes and the budget, and the page's `geometry` gains:

```json
"downsampled_from": {"width_px": 2480, "height_px": 3508, "megapixels": 8.7, "scale": 0.7014}
```

Absent means nothing was discarded — a vector page, or a raster rendered
at or above its own size — not that nobody checked. Detection uses
PyMuPDF's `get_image_info()`, which sees both a scan wrapped in a PDF and
a bare JPEG/TIFF; `get_images()` returns nothing for the latter, i.e. for
every phone photo and single-image scan, which is most of the population
this exists for.

### EXIF orientation

Camera images are handled, but not by code here: fitz/MuPDF applies a
JPEG's EXIF `Orientation` tag when it rasterizes. Verified live for all
of Orientation 1/3/6/8 and pinned by `tests/test_layout.py`, because it
is load-bearing behaviour of a dependency that nothing else asserts.

The one path that did **not** get it for free was WEBP/HEIC/HEIF, which
Pillow decodes and re-encodes to PNG -- and PNG has no orientation tag,
so anything not applied there was lost permanently.
`ImageOps.exif_transpose` now runs on that path. (HEIC did not need it; pillow-heif
applies the rotation on open. WEBP did: verified live, a WEBP written
with `Orientation=6` opens un-rotated with the tag still set.)

## Per-page routing

Replaces "Docling found any text on this page, so use it" with three
measured checks per page. That old rule cost two entire eval conditions,
both stuck at 0.0% field accuracy: a scan carrying an **invisible**
`(cid:NN)` text layer (returned confident nonsense) and a typed header
over a scanned body (the body was silently dropped).

Each page takes one of three routes, recorded with every number the
decision was made from in `pages[].routing`:

| route | when | what runs |
|---|---|---|
| `native_text` | good text layer, no large unexplained image | Docling text only; PaddleOCR never called |
| `paddleocr` | no text layer, or the layer is junk | the whole page is rasterized and OCR'd |
| `hybrid` | good text layer **plus** image regions it does not explain | text kept as-is; only the uncovered regions are cropped and OCR'd, then merged in reading order |

```json
"routing": {"route": "hybrid", "reason": "91% of the page is image with no text layer over it",
            "text_coverage": 0.04, "image_coverage": 0.94, "uncovered_image_coverage": 0.91,
            "garbage_ratio": 0.0, "plausibility": 1.0, "native_chars": 28, "ocr_regions": 1}
```

A rasterized page also carries its measured quality and any flags:

```json
"quality": {"measured": true, "effective_dpi": 96.0, "blur_score": 184.31,
            "contrast": 42.87, "skew_angle": -1.2,
            "thresholds": {"min_dpi": 150.0, "min_blur": 100.0, "min_contrast": 35.0}},
"flags": {"poor_image_quality": true, "poor_image_quality_reasons": ["LOW_RESOLUTION"]}
```

`flags` carries only what is **true**, so an empty dict reads as a clean
page and an absent key means false. `quality` is absent entirely on a
native-text page, which is never rasterized — distinct from
`{"measured": false}`, which means the page was rasterized and could not
be measured.

On a hybrid page each block carries `"source": "native_text" | "paddleocr"`, and
**only the uncovered regions are sent to the engine** — sending the whole
page would read the header a second time and duplicate it in `text`,
`blocks` and everything downstream that consumes them.

Order matters: garbage is checked **before** coverage. A junk text layer
produces junk *boxes* too, and those would otherwise count as explaining
the image beneath them — hiding the one page that most needs OCR.

Every threshold is asymmetric on purpose. Re-OCRing a good native page
trades exact text for a guess, so each check is reluctant to override a
text layer: a few unmapped glyphs from a logo font do not condemn a real
layer, and a letterhead logo does not make an ordinary native PDF pay for
a second inference pass on every upload.

### Deviation: plausibility is a shape heuristic, not a dictionary

Section 3.6 asks for "the ratio of dictionary-like tokens for the
configured languages". This implementation scores token *shape* instead
(a word has a vowel; it does not run five consonants without one).

A dictionary would be wrong on exactly the documents this system handles.
A real invoice is mostly `INV-2026-05426`, `PO-74300`, `CC-4450`,
`Contoso Supplies PLC`, `80gsm` — identifiers, vendor names, part codes
and units. None of them is in a dictionary, all of them are correct, and
a dictionary-ratio metric would score a perfectly good text layer as
implausible and send it to be re-OCR'd. It would also have to be shipped
and versioned per configured language, with Tamil a stated target
(section 3.7), for a check that is a tie-breaker rather than a primary
signal — `garbage_ratio` already catches the real case, a scanner junk
layer.

What the deviation costs: this cannot detect a text layer that is
well-formed but simply *wrong*. Neither could a dictionary.

### Thresholds are provisional

`OCR_ROUTING_*` were tuned on synthetic fixtures and have never been seen
against a real document. See `docs/ocr/phase2-verification.md`, which
lists them alongside the resolution decision as the things to re-measure
first once a real labelled corpus exists.

## Output contract

Every OCR result (full-document, zone, or rubber-band) is **one JSON
object**, stored at the MinIO key referenced by
`ocr_outputs.extracted_text_ref`, and returned directly (same shape) in
`POST /ocr/process/{version_id}`'s HTTP response body:

```json
{
  "schema_version": 2,
  "document_version_id": "...",
  "text": "... flattened plain text, all pages joined ...",
  "pages": [
    {
      "page_number": 1,
      "method": "native_text",
      "text": "...",
      "confidence": 1.0,
      "preprocessing": {},
      "geometry": {
        "width_pt": 595.0, "height_pt": 842.0,
        "width_px": 1131, "height_px": 1600,
        "render_scale": 1.900238
      },
      "blocks": [{"text": "...", "bbox": [x0, y0, x1, y1], "bbox_norm": [0.1, 0.2, 0.4, 0.22], "confidence": 1.0}],
      "tables": [
        {
          "bbox": [x0, y0, x1, y1],
          "bbox_norm": [0.1, 0.3, 0.9, 0.5],
          "num_rows": 2,
          "num_cols": 3,
          "cells": [
            {"row": 0, "col": 0, "row_span": 1, "col_span": 1, "text": "Item", "bbox": [x0, y0, x1, y1], "bbox_norm": [0.1, 0.3, 0.3, 0.33], "column_header": true, "row_header": false}
          ]
        }
      ]
    }
  ],
  "fields": {
    "invoice_number": {"value": "INV-4471", "confidence": 0.95, "bbox": [x0, y0, x1, y1], "bbox_norm": [0.4, 0.12, 0.6, 0.14]}
  },
  "flags": {"low_confidence": false, "requires_review": false}
}
```

### `schema_version`

`2` since Phase 2. v1 carried **no version key at all**, so a consumer
reading an older stored artifact should treat a missing key as `1`.
Unrelated to `events.py`'s `SCHEMA_VERSION`, which versions the RabbitMQ
event payloads.

v2 is purely additive (per the production prompt's section 3.11): it adds
`bbox_norm` to every block, table, cell and field, `geometry` to every
page, and `preprocessing` to every page. Every v1 key keeps its meaning
and its type, so dms-core's `applyOcrFields` works against v2 unchanged.

Phase 2 is one unreleased unit, so keys added by later steps within it
(`preprocessing` arrived in Step 3) are part of v2 rather than each
bumping the number -- a version that changed every few days would tell a
consumer nothing. The number moves again at the next phase boundary.

### `preprocessing`

What was done to a page's raster before OCR read it, per production
prompt section 3.4 ("each step individually toggleable in config and
recorded in the output"). `{}` for a `native_text` page, which is never
rasterized and so never preprocessed, and `{}` when the corresponding
toggle is off -- deliberately distinguishable from "the stage ran and
decided to do nothing", so a misconfigured deployment does not look
identical to a working one.

```json
"preprocessing": {"orientation": {"angle": 90, "confidence": 0.93, "applied": true}}
```

`angle` is how far **clockwise** the page arrived rotated; `applied` says
whether the correction was actually made (see "Whole-page orientation"
below). A recorded-but-unapplied decision is a real and expected state.

### Coordinates

Every bbox is reported twice:

- **`bbox`** -- integer pixels in the full page's raster space. Kept for
  backward compatibility. Its magnitude depends on the render scale, so it
  changes meaning if `OCR_RENDER_DPI` or the render cap changes.
- **`bbox_norm`** -- `[x0, y0, x1, y1]` as fractions of page width/height
  in `[0, 1]`. **This is the form every consumer should use** (zones,
  rubber-band, highlights, spatial matching): it is independent of render
  resolution, so it survives a DPI change.

`bbox_norm` is `null` when there is no geometry to normalize against --
for a regex-derived field (a match over concatenated text has no single
page or position) and for a `.docx`, which reflows and is never
rasterized, so its one block carries a `(0,0,0,0)` placeholder. It is
deliberately `null` rather than a zero box, which a review UI would draw
as a real highlight in the page's corner.

`geometry` gives the page's own size in points and pixels plus the
`render_scale` between them, so a consumer can convert either way without
reproducing this worker's arithmetic. It is `null` for a `.docx`.

**All bboxes, from every method, really are in one coordinate space now.**
Until Phase 2 they were not: `render_page` capped its zoom at
`_MAX_RENDER_DIMENSION_PX / longest_side_pt` while the three native-text
producers scaled by the uncapped `dpi / 72`, so on an ordinary A4 page at
the default 200 DPI a native-text bbox sat ~46% further down the page than
the text it described. Nothing had noticed because page routing is
exclusive -- a page is either `native_text` or `paddleocr`, so the two
spaces never appeared together. `layout.PageGeometry` is now the single
source of that scale and `tests/test_coordinates.py` pins it.

`fields` is `{}` when the document type has no configured zone/field
template -- OS-FR-005's structured extraction still runs a best-effort
regex pass over the flattened text in that case (invoice numbers, dates,
amounts, IDs), but never fabricates a field it didn't actually find.
`bbox` is `null` for a regex-derived field (a match over concatenated text
has no single spatial location); zone-derived fields always carry a real
bbox (the zone's own pixel box). See "Coordinates" above for the
normalized form and the space both live in.

`tables` is only ever non-empty for a `native_text` page, and only when
Docling's table-structure model actually found a table on it -- real
rows/columns/cells (with `row_span`/`col_span` for merged cells, and
`column_header`/`row_header` flags), not the flat reading-order guess
`blocks` uses everywhere else. **A table's cell text is also present in
`blocks`/`text`, in row-then-column order** (see
`pipeline.py`'s `_merge_native_text_and_tables`) -- `tables` is additive
structure for a consumer that wants the real grid, not the only place a
table's content appears. A cell's `bbox` can be `null` if Docling
reasoned about the cell structurally without anchoring it to real page
geometry (e.g. a removed spanned cell).

The RabbitMQ event (`document.version.ocr.completed`/`.failed`) is
deliberately slimmer than this -- see `docs/events/ocr-scanning-events.md`
-- it carries `extracted_text_ref` and `flags` so a downstream consumer
knows to go fetch the detail, not a copy of the whole JSON.

## Zone / structured-field templates

Stored in Postgres (`document_type_ocr_zones` --
`docs/database/V11__create_ocr_templates.sql`), one row per named zone,
keyed by `document_type_id`. A zone's `field_name` **is** its
structured-field mapping (OS-FR-005), so this one table covers both
OS-FR-003 (zone OCR) and OS-FR-005 -- no separate "field template" table.
Coordinates are fractions of page width/height (0.0-1.0), not pixels, so a
template defined once applies regardless of scan resolution.

A document type with no rows in this table falls back to full-document OCR
(the alternate flow "document type does not match any configured zone OCR
template").

## Known upstream gap

**`backend/workers/scan-worker/` does not exist yet.** Section 2.2 requires
OCR to never run on a version until it has been scanned clean, and the
`document.version.scan.completed` consumer is built against that
precondition -- but nothing in this codebase actually scans. dms-core's
`uploadDocument` sets `scan_status='clean'` directly at upload, which is
what makes the version eligible today; `get_version_for_processing` still
refuses anything not `clean`, so wiring a real scanner in later is a
producer change, not a change here. See `docs/events/ocr-scanning-events.md`
for the full contract (routing keys, wire format, DLQ convention), where
that one event is flagged as assumed pending scan-worker's implementation.

Two things exercise the pipeline without scan-worker -- both have to
run on the docker network (no published host port, see above):

1. **`scripts/dev/publish_fake_scan_event.py`** -- bootstraps a fixture
   document/version, uploads a sample file to MinIO, and publishes
   `document.version.scan.completed` by hand.
2. **`POST /ocr/process/{version_id}`** -- runs the identical pipeline
   synchronously, bypassing RabbitMQ, returning the full output JSON
   directly.

Both funnel through the exact same `OcrPipeline` code as the real queue
consumer -- the trigger source never changes the processing logic.

## Local setup

```bash
cd backend/workers/ocr-worker
python -m venv .venv && source .venv/bin/activate   # or .venv\Scripts\activate on Windows
pip install -e ".[dev]"
```

Bring up the stack (no ocr-worker host port, so you can't curl `localhost:8090`
from the bare host -- see "Auth & service exposure"):

```bash
cd infra/local
docker compose up -d postgres rabbitmq minio ocr-worker
```

## Required verification

The real deliverable, per the task this was built against: proof
PaddleOCR and Docling are actually invoked, not a description of expected
output.

### 1. Sanity-check the call sites exist

```bash
grep -r "PaddleOCR(" backend/workers/ocr-worker/src
grep -r "DocumentConverter(" backend/workers/ocr-worker/src
```

Both must return real, non-test call sites (`src/ocr_worker/ocr_engine.py`
and `src/ocr_worker/layout.py` respectively) -- zero results means
something regressed into a stub.

### 2. Run the smoke test against the three real fixtures

`tests/fixtures/` has three real files (not 1x1 placeholders): a
native-text PDF (`native_text.pdf`), a scanned/image-only PDF
(`scanned_image_only.pdf`, a real raster embedded with no text layer at
all), and a plain scanned image (`scanned_image.png`).

```bash
docker run --rm --network local_default \
  -v "$(pwd)/tests/fixtures:/fixtures" \
  -v "$(pwd)/../../../scripts:/scripts" \
  -e INTERNAL_SERVICE_TOKEN=<value from infra/secrets/ocr-worker.secrets.env> \
  python:3.12-slim bash -c "
    pip install --quiet psycopg[binary] minio requests
    python /scripts/dev/smoke_test_ocr.py --fixtures-dir /fixtures
  "
```

This prints the **real** output JSON for each fixture to stdout --
`native_text.pdf`'s pages should show `"method": "native_text"` with
`"confidence": 1.0` and zero PaddleOCR calls; the two scanned fixtures
should show `"method": "paddleocr"` with real detected text/bboxes/
confidence scores from actually running the model.

### 3. Phase 1 exit criteria -- the queue path under load

`scripts/dev/smoke_test_ocr.py` and `scripts/dev/repro_batch.py` both drive
the **dev-trigger HTTP endpoint**, which is no longer how production
triggers OCR. `scripts/dev/phase1_verification.py` drives the real path:
it stages N versions in Postgres + MinIO, inserts
`document.version.ocr.requested` rows into `outbox_events` so dms-core's
own `OutboxRelay` publishes them, and then waits on the database for each
version's terminal state.

```bash
docker run --rm --network local_default \
  -v "$(pwd)/../../..:/repo" -v "<repro-fixtures>:/fixtures" \
  ocr-worker-test:latest \
  python /repo/scripts/dev/phase1_verification.py \
    --fixtures-dir /fixtures --total 50 --burst 10 \
    --report-json /fixtures/phase1-run.json
```

Fixtures come from `scripts/dev/build_fixtures.py` (it expands the three
committed fixtures into the 20-file mixed set: HEIC, multi-page and CCITT
G4 TIFF, rotated scans, 35-page PDFs, encrypted, corrupt, DOCX). The run
deliberately lies about `Content-Type` on a few items so magic-byte
sniffing is exercised on the live path too.

Use `--trigger direct` to publish straight to RabbitMQ with pika, which
isolates ocr-worker by bypassing the relay. Do **not** make that the
default: publishing that way is precisely how an earlier version of this
harness passed while dms-core's relay was double-encoding every payload
and both directions of the queue path were dead.

### 3b. Phase 1 close-out -- fault injection and the field path end to end

`scripts/dev/phase1_closeout_verification.py` covers what the load run
could not: selectable field extraction through the real upload API (with
dms-core's poll-time re-apply switched off, so only the event path can be
responsible for the result), a pool worker SIGKILLed mid-document, and a
per-page budget the document cannot meet. See
`docs/ocr/phase1-closeout.md` for the results.

Each check needs its own compose overlay, and refuses to run against the
wrong one rather than producing a pass for the wrong reason:

```bash
cd infra/local
docker compose -f docker-compose.yml -f docker-compose.closeout-fields.yml  up -d dms-core
docker compose -f docker-compose.yml -f docker-compose.closeout-reaper.yml  up -d ocr-worker
docker compose -f docker-compose.yml -f docker-compose.closeout-timeout.yml up -d ocr-worker
```

```bash
docker run --rm --network local_default \
  -v "<repo>:/repo" -v /var/run/docker.sock:/var/run/docker.sock \
  -e KEYCLOAK_ADMIN_PASSWORD=... \
  -e VERIFY_DSN=postgresql://postgres:...@postgres:5432/dms_core_db \
  ocr-worker-test:latest \
  python /repo/scripts/dev/phase1_closeout_verification.py fields \
    --report-json /repo/docs/ocr/phase1-closeout-fields.json
```

The docker socket mount is what lets the harness read pool-worker PIDs out
of the ocr-worker container and kill one -- it runs *inside* a container
(it has to, to resolve `dms-core`/`postgres` by service name) and that
container has no docker CLI. Put the stack back with plain
`docker compose -f docker-compose.yml up -d dms-core ocr-worker` when
done; the overlays are fault-injection settings, not ones to leave running.

### 4. Unit tests

```bash
pip install -e ".[dev]"
pytest
```

Covers, among others: (a) the native-text path never calls PaddleOCR, (b)
the image-only path calls PaddleOCR with real image data and produces
non-empty `blocks`, (c) confidence below threshold sets
`flags.requires_review`, (d) the DB transaction + outbox write happens
atomically (mocked DB/MinIO/RabbitMQ; the transactional-write tests run
against a real in-memory SQLite database via SQLAlchemy, so that sequence
is actually exercised, not just asserted against mocks). None of the unit
tests need a live scan-worker, RabbitMQ, MinIO, Postgres, or the real
PaddleOCR/Docling models -- they run in well under a second.

### On-demand zone / rubber-band OCR

```bash
curl -X POST http://ocr-worker:8090/ocr/zone \
  -H "X-Internal-Token: $INTERNAL_SERVICE_TOKEN" -H 'content-type: application/json' \
  -d '{"version_id": "<version_id>", "zone_names": ["invoice_number", "invoice_date"]}'

curl -X POST http://ocr-worker:8090/ocr/rubber-band \
  -H "X-Internal-Token: $INTERNAL_SERVICE_TOKEN" -H 'content-type: application/json' \
  -d '{"version_id": "<version_id>", "page": 0, "x0": 0.1, "y0": 0.05, "x1": 0.4, "y1": 0.12}'
```

(From a container on the same docker network -- e.g. the same
`docker run --network local_default ...` pattern as the smoke test.)
`x0/y0/x1/y1` are fractions of page width/height (0.0-1.0), not pixels, so
a selection made against a preview image at any resolution maps correctly
onto the page PyMuPDF actually renders.

## Configuration

See `src/ocr_worker/config.py` for the full list; the ones worth knowing about:

| env var | default | meaning |
|---|---|---|
| `MIN_CONFIDENCE_THRESHOLD` | `0.95` | OS-NFR-001; below this, `flags.low_confidence`/`requires_review` are set rather than silently accepted -- never dropped |
| `FIELD_LOW_CONFIDENCE_THRESHOLD` | `0.6` | per-field bar (`extracted` vs. `low_confidence`), far lower than the document-level one above; the value is returned either way |
| `PREFETCH_COUNT` | *(= `OCR_WORKER_PROCESSES`)* | OS-NFR-004: how many deliveries RabbitMQ may have unacked with this worker at once. See "Prefetch" below |
| `OCR_WORKER_PROCESSES` | `2` | size of the `pebble` **process** pool that actually runs inference; one PaddleOCR + one Docling instance per process, never shared across threads |
| `OCR_WORKER_MAX_TASKS_PER_PROCESS` | `50` | recycle a pool worker after this many documents, bounding any per-call native-library leak |
| `OCR_TIMEOUT_BASE_S` / `OCR_TIMEOUT_PER_PAGE_S` | `60` / `45` | per-document budget is `base + per_page * the document's REAL page count`. Per-page raised 25 -> 45 on 2026-09-29 against a measured 29.2s/page under load. See "Per-document timeout" below |
| `OCR_TIMEOUT_HARD_GRACE_S` | `30` | how long after the budget the in-worker hard watchdog fires, killing that one pool worker |
| `OCR_MAX_PAGES` | `50` | `TOO_MANY_PAGES` above this; sized so one document can't monopolize a pool worker for the better part of an hour |
| `OCR_MAX_FILE_SIZE_BYTES` | `104857600` | `FILE_TOO_LARGE` above this |
| `OCR_LEASE_DURATION_S` | `2700` | how long a claimed `processing_jobs` row's lease is valid before the reaper may reclaim it. Raised 1800 -> 2700 on 2026-09-29 because the per-page timeout rise put the worst-case document at 2370s, above the old lease. Also the grace the reaper gives a `processing` row that has *no* lease at all before treating it as expired — see "Every `processing` row has a lease" below |
| `OCR_REAPER_INTERVAL_S` | `30` | how often the lease reaper sweeps for expired leases |
| `OCR_REAPER_BACKOFF_BASE_S` | `30` | linear backoff: a job's Nth requeue waits `N * this` before becoming claimable again |
| `MAX_DELIVERY_ATTEMPTS` | `5` | total attempts for a failure cause with no specific policy below. The reaper then dead-letters the job and sets `ocr_status='failed'` |
| `OCR_RETRY_ATTEMPTS_BY_CODE` | `TIMEOUT=1,ENCRYPTED_PDF=0,CORRUPT_FILE=0,UNSUPPORTED_FORMAT=0,TOO_MANY_PAGES=0,FILE_TOO_LARGE=0,WORKER_CRASH=3,ENGINE_ERROR=3` | retry budget **per failure cause**. The number is RETRIES after the first try, so `0` means "try once, never again". Parsed and validated at startup -- an unknown code or a non-integer **fails the process**. See "Retries are per cause" |
| `OCR_MAX_WORKER_CRASHES_PER_VERSION` | `2` | a version that has killed a pool worker this many times is dead-lettered immediately with a message saying it exceeded worker memory |
| `OCR_RENDER_DPI` | `200` | page raster DPI for OCR. Honoured for any page that fits the area budget below; above it the budget wins. Was inert before Phase 2 Step 4 (the old 1600px cap bound far below it on every ordinary page) |
| `OCR_RENDER_MAX_MP` | `1.81` | hard ceiling on the rendered area of one page, in megapixels. 1.81 is the exact area the old 1600px longest-side cap produced on A4, so an ordinary page renders pixel-identically to before -- see "The render budget" |
| `OCR_ROUTING_ENABLED` | `true` | per-page `native_text` / `paddleocr` / `hybrid` routing. Off restores the old "any native text wins" rule exactly |
| `OCR_ROUTING_MAX_GARBAGE_RATIO` | `0.30` | above this fraction of `(cid:NN)`/replacement/control characters the text layer is junk and the page is OCR'd. **Provisional** -- tuned on synthetic fixtures |
| `OCR_ROUTING_MIN_PLAUSIBILITY` | `0.50` | below this fraction of word-shaped tokens, likewise. **Provisional**, and a documented deviation from section 3.6 -- see "Per-page routing" |
| `OCR_ROUTING_MIN_UNCOVERED_IMAGE` | `0.15` | a good text layer plus this much unexplained image area makes the page `hybrid`. **Provisional** |
| `OCR_LANGUAGES` | `en` | PaddleOCR language set |
| `OCR_ORIENTATION_ENABLED` | `true` | whole-page orientation correction before OCR -- see "Preprocessing". Off means a sideways page is read sideways, which measured 11.7-14.7% field accuracy against 95.3% upright |
| `OCR_ORIENTATION_MIN_CONFIDENCE` | `0.6` | below this the predicted rotation is recorded but not applied. A floor against a degenerate page, not a calibrated threshold -- see "Preprocessing" |
| `OCR_TABLE_TIMEOUT_PER_PAGE_S` | `20.0` | per-page bound on Docling's table-structure pass. On the deadline the page keeps its text, loses table structure, and gets `flags.table_structure_skipped`. `0` disables. Set against measurement: a real 5x4 ruled table costs ~4s, the pathological case cost 87s |
| `OCR_NORMALIZE_PAGE_ROTATION` | `true` | bake `/Rotate` into page content before Docling. Verified to move every word by 0.000pt while taking a rotated page from 138.9s to 2.3s |
| `OCR_QUALITY_ENABLED` | `true` | per-page quality assessment into `pages[].quality` + `flags.poor_image_quality`. Never rejects a page |
| `OCR_QUALITY_MIN_DPI` | `150.0` | below this effective DPI the page is flagged `LOW_RESOLUTION` |
| `OCR_QUALITY_MIN_BLUR` | `100.0` | variance-of-Laplacian floor; below it, `BLURRY`. **Provisional** — never measured against a real blurred photograph |
| `OCR_QUALITY_MIN_CONTRAST` | `35.0` | RMS contrast floor (std-dev, 0-255); below it, `LOW_CONTRAST`. **Provisional** |
| `OCR_OFFLINE_MODELS` | `false` | pin model loaders to the local cache, removing the one outbound call this worker makes. Default off because model caches are volumes populated on first use, not baked into the image — see "No LLM, by design" |
| `INTERNAL_SERVICE_TOKEN` | (see `infra/secrets/ocr-worker.secrets.env`) | required `X-Internal-Token` header value on every `/ocr/*` request |
| `DB_URL` | points at `dms_core_db` | see "Database access" below |

### Memory — measured, and now bounded

`docker stats` sampled every 3s across the full 50-upload Phase 1 run (368
samples, `OCR_WORKER_PROCESSES=2`):

| min | median | p95 | **peak** |
|---|---|---|---|
| 3.04 GiB | 4.60 GiB | 5.77 GiB | **6.89 GiB** |

`infra/local/docker-compose.yml` sets **`mem_limit: 8704m` (8.5 GiB)** on
`ocr-worker` — that peak plus ~25% headroom. Before this the container ran
with `HostConfig.Memory=0`: nothing bounded it at all, and it had already
been OOM-killed once during load testing (which is why the service also
carries `restart: unless-stopped`). A cgroup limit turns "the host runs out
of memory and the kernel picks a victim" into "this one container is killed
and restarted".

Each pool worker holds its own Docling layout model plus PaddleOCR det/rec
models resident, so budget roughly **3.4 GiB per `OCR_WORKER_PROCESSES`
plus a ~1.5 GiB shared/parent floor** and raise the limit with the pool
size. Re-measure rather than extrapolating if you change `OCR_RENDER_DPI`
or the model set.

### Prefetch

`PREFETCH_COUNT` defaults to **`OCR_WORKER_PROCESSES`**, not to a fixed
number. Phase 1 shipped a flat `4` against a pool of `2`, which the exit-
criteria run confirmed live: RabbitMQ held exactly 4 deliveries unacked
throughout. Two of those were messages this worker had no free pool process
to start — held, invisible to any other consumer, for as long as the two
running documents took (up to ~1000s for a 35-page scan in that run).
Prefetch above the pool size buys no throughput, because the pool and not
the queue is the bottleneck; it only delays redistribution. An explicit
`PREFETCH_COUNT` still overrides the default if you want a deliberate
buffer.

One caveat: `ScanCompletedConsumer` and `OcrRequestedConsumer` each set QoS
on their own channel, so with both connected the process-wide ceiling is
`2 x PREFETCH_COUNT`. That is currently moot — nothing publishes
`document.version.scan.completed` until scan-worker exists (see "Known
upstream gap") — but it is the thing to revisit when it does.

### Per-document timeout

The budget is `OCR_TIMEOUT_BASE_S + OCR_TIMEOUT_PER_PAGE_S × the
document's real page count`, read the instant PyMuPDF has opened the file
and **before** Docling's conversion runs.

|  | old | new (2026-09-29) |
|---|---|---|
| formula | `60 + pages × 25` | `60 + pages × 45` |
| 1-page photo | 85s | **105s** |
| 35-page scan | 935s | **1635s** |
| 50-page document | 1310s | **2310s** |

**Why it was raised.** The old 25s/page came from a quiet-machine rate of
15.6s/page plus ~60% headroom, and it was wrong about the case that
matters. Under a 10-way concurrent burst on two pool workers,
`large_35page_scanned.pdf` took **1020.9s for 35 pages = 29.2s/page** —
already past the 25s allowance before any headroom at all — and was
killed at its 935s budget. It was not hanging. It was slow.

`1.5 × 29.2 = 43.8`, rounded to **45**. The 35-page budget becomes 1635s
against the 1020.9s that document really took.

**This budget exists to catch a hang, not to cap a slow document.** A
too-tight budget turns slow documents into failures, and now that
`TIMEOUT` carries a retry, into *repeated* failures costing a pool worker
each. An actual wedged native call is bounded by layer 2 below, whose
grace is unchanged.

Phase 1 sized one uniform ceiling from `OCR_MAX_PAGES` instead — 1310s for
*every* document, one-page photos included — because the dispatching side
cannot know a page count without doing the pool worker's own job first.
With `OCR_WORKER_PROCESSES=2`, a single hung one-page upload therefore took
half the service's capacity out for 22 minutes. Three layers replace it
(`deadline.py`):

1. **Soft**, checked between pages. Raises `OcrTimeoutError`, which rides
   the ordinary failure path: `TIMEOUT: ...` in `processing_jobs.last_error`,
   `ocr_status='failed'`, a `.failed` event — and the pool worker survives
   to take the next document.
2. **Hard**, a watchdog thread armed alongside the soft deadline and firing
   `OCR_TIMEOUT_HARD_GRACE_S` later. A single wedged native
   PaddleOCR/Docling call never returns to a soft check and Python cannot
   interrupt it, so the watchdog writes the same `TIMEOUT` record itself and
   then `os._exit`s. `pebble` replaces that one worker; the rest of the pool
   is untouched. The exit code (`OCR_TIMEOUT_EXIT_CODE`) is what lets the
   parent tell this apart from an OOM kill, where no record was written and
   the reaper still owns the lease.
3. **The `pebble` `schedule(timeout=...)` ceiling**, sized above the largest
   budget layer 2 can possibly arm. It only fires if the watchdog thread
   never got scheduled at all.

**`OCR_LEASE_DURATION_S` must stay larger than
`OCR_TIMEOUT_BASE_S + OCR_MAX_PAGES × OCR_TIMEOUT_PER_PAGE_S + 2 ×
OCR_TIMEOUT_HARD_GRACE_S`** (`60 + 50 × 45 + 2 × 30` = **2370s** against
the default **2700s** lease), or the
reaper stops backing the worker up and starts competing with it: the lease
expires mid-inference, the reaper requeues, the retry restarts the same
document from scratch, and a document that was never failing can livelock
until `MAX_DELIVERY_ATTEMPTS` dead-letters it. ocr-worker logs a warning at
startup when that does not hold. (Observed for real during Phase 1
close-out, against a deliberately shortened lease.)

### Retries are per cause

`max_delivery_attempts` alone was blind to *why* a job died: the reaper
retried anything left `processing` with an expired lease up to 5 times,
on the assumption that a death is transient. For a document that is
simply too large or too slow it never is, and the cost was measured --
the 50-upload concurrent run at `OCR_RENDER_MAX_MP=4.0` spent roughly 40
of its 60 minutes retrying one 35-page scan five times, each attempt
burning the full 935s budget before dying identically.

| cause | retries | why |
|---|---|---|
| `TIMEOUT` | **1** | one retry covers a transient contention spike; a second cannot help, because the document's page count and size are constant |
| `ENCRYPTED_PDF`, `CORRUPT_FILE`, `UNSUPPORTED_FORMAT`, `TOO_MANY_PAGES`, `FILE_TOO_LARGE` | **0** | properties of the file. Every attempt produces the same answer |
| `WORKER_CRASH`, `ENGINE_ERROR` | **3** | a crash genuinely can be transient — an OOM caused by a *neighbour's* page, not this one |
| unknown / unrecorded | **same as `WORKER_CRASH`** | "no cause recorded" is usually a crash one level up — a container-level OOM kills the *parent* too, so nothing survives to write the cause. The reaper already dead-letters such a job labelled `WORKER_CRASH`, so the budget matches the label; otherwise the same physical failure would be retried a different number of times depending only on whether the process that would have described it also died. Falls back to `MAX_DELIVERY_ATTEMPTS` only if `WORKER_CRASH` has no policy |

Three supporting details matter more than they look:

**The policy applies to a timeout the worker recorded, not only to one
the reaper inferred.** It used to be consulted only by the reaper, which
sees jobs whose worker *died* — so `TIMEOUT=1` meant one retry when the
pool ceiling killed the worker, and **zero** when the in-worker deadline
fired first. Same cause, different budget, decided by which of two timers
won. An in-worker `TIMEOUT` inside its budget now goes back to `queued`
with a backoff instead of terminal `failed`.

Two constraints on that, both learned the hard way. It emits **no event**
— the same rule as `reap_requeue`, for the same measured reason: the
relay polls every 2s, so an event published here always lands inside the
backoff window, `claim_job`'s `next_attempt_at <= now` guard refuses it,
the consumer acks and skips, and nothing ever publishes again. The
reaper's `publish_due_retry` re-requests it once the backoff has elapsed.
And it is **`TIMEOUT` and nothing else** — extending it to every code was
tried and reverted: an in-worker failure is one the worker survived long
enough to *classify*, and those are deterministic. A missing MinIO object
(`ENGINE_ERROR`) fails identically on all four attempts, so the retries
buy nothing and cost a pool worker each.

**The parent records the cause.** When a pool worker is OOM-killed or the
outer pebble ceiling fires, the worker never gets to write anything — the
row was left `processing` with **no `last_error` at all**, so the reaper
had no cause to reason about and retried it as a transient crash. The
parent now writes `TIMEOUT:`/`WORKER_CRASH:` before leaving the lease
(`consumer.py`'s `_record_death_cause`), guarded by a CAS so it can never
overwrite a worker's own completed failure record.
`tests/test_consumer_death_cause.py` covers each branch, because the
symptom of losing this again is a queue that is quietly slower than it
should be rather than anything visibly failing.

**A version that kills a worker twice is dead-lettered immediately**, not
after its full crash budget, with a message saying the document exceeded
worker memory. The count is `processing_jobs.attempts` used as a proxy —
a requeue reuses the same row and overwrites `last_error`, so there is no
durable per-cause history to count without a new column. For the case
that matters (a document too big for this worker) that is the same
population, since such a document kills a worker on every attempt. A
`processing_job_attempts` history table would replace the proxy with a
real count; it is on the pending list, not built.

### Every `processing` row has a lease

`processing_jobs.lease_expires_at` is NULL for every status *except*
`processing`: a `queued` row has no lease yet, and a completed/failed/
dead-lettered one has none any more. A `processing` row without one is a
bug, and `V37__processing_jobs_require_lease.sql` enforces that with
`CHECK (status <> 'processing' OR lease_expires_at IS NOT NULL)`.

The constraint exists because such a row is **invisible to the reaper**:
its scan is `lease_expires_at < now()`, and in SQL a comparison against
NULL is NULL, not true. So the row never gets requeued, never gets
dead-lettered, and keeps holding V36's `uq_processing_jobs_active_ocr_ref`
slot — which blocks every future OCR job for that version. Four such rows
had accumulated in the local dev database, all written by one test
fixture; `claim_job`, the only production writer of that status, has
always set the lease in the same UPDATE.

The reaper also treats a leaseless `processing` row older than
`OCR_LEASE_DURATION_S` (by `COALESCE(started_at, created_at)`) as expired,
and logs a `WARNING` naming the row. That is a backstop for anything that
predates the constraint, not a supported state.

`WORKER_POOL_SIZE` no longer exists. It configured a `ThreadPoolExecutor`
sharing one PaddleOCR and one Docling instance across threads, which
`docs/ocr/failure-analysis.md` #2 measured at a 2.7x slowdown under only 4
concurrent requests (GIL + native-library contention). `OCR_WORKER_PROCESSES`
replaces it, and the two are not interchangeable: it sizes real OS
processes, so raising it multiplies resident model memory.

## Database access

ocr-worker connects directly to the same Postgres instance/database as
dms-core (`dms_core_db`) to read/write `document_versions` (the `ocr_status`
column only), `document_type_ocr_zones` (read-only), and to write
`ocr_outputs`, `processing_jobs`, and `outbox_events` -- reusing dms-core's
existing outbox + `OutboxRelay` mechanism rather than publishing completion
events to RabbitMQ directly.

**Known limitation:** local dev config defaults to a dedicated
`ocr_worker_user` credential, but no migration/init script in this repo
actually creates that role or grants it table-level access yet -- today it
reuses `dms_core_user`'s credentials. Flagging this rather than silently
defaulting to the shared credential without comment.

## Secrets

Own file, `infra/secrets/ocr-worker.secrets.env` (plaintext, dev-only),
distinct from `infra/secrets/dms-core.secrets.env` -- ocr-worker needs none
of dms-core's secrets directly. Contains `INTERNAL_SERVICE_TOKEN`. No
encrypted counterpart exists yet (that requires the `bao` CLI and a token
against the real OpenBao transit key, neither available in this
environment) -- whoever has that access should run
`scripts/openbao-encrypt-secrets.sh infra/secrets/ocr-worker.secrets.env infra/secrets/ocr-worker.secrets.enc.env`
once.

`MINIO_ROOT_USER`/`MINIO_ROOT_PASSWORD` (for the `minio` compose service,
which this repo also didn't have before) still live in
`infra/secrets/dms-core.secrets.env`, since MinIO itself is shared
infrastructure, not ocr-worker-specific.

## Docker

```bash
docker build -t oakdms-ocr-worker -f backend/workers/ocr-worker/Dockerfile backend/workers/ocr-worker
```

See `infra/local/docker-compose.yml`'s `ocr-worker` service entry --
no `ports:` (internal-only, see "Auth & service exposure"), its own
`env_file`, and named volumes for the PaddleOCR/Docling model caches
(`~/.paddlex`, `~/.cache/huggingface`) so they survive container restarts
instead of re-downloading from Hugging Face every time.

## No LLM, by design

**There is no LLM tier in this pipeline, and there will not be one.**
Production prompt section 3.8 describes an optional
`EXTRACTION_LLM_PROVIDER=none|local|anthropic` structuring stage as tier 5
of field extraction. That stage is **excluded from scope** -- it is not
pending work, not a backlog item, and not behind a flag. No setting, stub
or code path for it exists in this worker.

The reason is the property it would cost: **document content never leaves
this worker.** ocr-worker is the only component that reads what a
document actually says, and every model it uses -- PaddleOCR detection
and recognition, the orientation classifier, Docling's layout and table
models -- runs locally on CPU, in-process. An LLM tier means shipping the
text of customer invoices, contracts and HR records to a third party,
which is a different product with a different compliance posture, and not
one this can be quietly extended into later: "only for fields the
deterministic tiers left `not_found`" is still every field on every badly
scanned document.

What replaces it is deterministic and already built: zone templates,
anchor-relative matching, spatial label/value matching, pattern tiers and
validation rules (section 3.8 tiers 1-4, `extraction.py` and
`validation.py`). Where those cannot read a field, the honest output is
`not_found` with a low confidence and a review flag -- see Phase 4's
review queue -- rather than a plausible guess from a model that never saw
the page.

### The outbound-call posture, verified

Checked at three levels, on 2026-09-29:

| what | result |
|---|---|
| Source imports | No `requests`, `httpx`, `urllib`, `aiohttp`, `boto3` or `huggingface_hub` anywhere in `src/ocr_worker/` |
| Docling options | `enable_remote_services=False`, `do_picture_description=False`, `do_picture_classification=False`, `do_code_enrichment=False`, `do_formula_enrichment=False` -- all defaults, none turned on. `do_ocr=False` is set explicitly |
| Live container logs | Exactly one external host contacted: `huggingface.co`. (`github.com` appears only inside a PaddlePaddle warning *message*, not a request.) |

That one call is HuggingFace **revision resolution** at converter
construction -- `GET /api/models/docling-project/docling-layout-heron/revision/main`
-- which sends a model name and no document content, and happens even
when the weights are already cached.

It is not required. Verified by running a full Docling conversion under
`docker run --network none -e HF_HUB_OFFLINE=1`, which succeeds from the
mounted cache alone. `OCR_OFFLINE_MODELS=true` makes that the running
configuration and removes the call entirely.

It defaults to **false** because the model caches are named volumes
populated on first use, not baked into the image -- enabling it before the
first successful warm-up would leave a fresh deployment unable to fetch
weights at all. Turn it on once the volumes are warm, or anywhere egress
is actually restricted.

## What's out of scope here

- **An LLM extraction tier** (see "No LLM, by design" above) -- excluded,
  not deferred.
- **scan-worker** itself (see "Known upstream gap" above).
- dms-core's passthrough `/ocr/*` routes and the real frontend upload/view
  page (see "Auth & service exposure") -- they depend on dms-core's
  `document`/`storage` modules, which don't exist yet.
- Owning the search index (OS-FR-006 says extracted data becomes searchable
  metadata; ocr-worker writes `ocr_outputs` + emits
  `document.version.ocr.completed` for a future indexing-worker to consume,
  it does not index anything itself).
- A template *editor* for `document_type_ocr_zones` -- the table exists,
  but nothing in this repo writes to it yet outside test fixtures/manual
  SQL.
