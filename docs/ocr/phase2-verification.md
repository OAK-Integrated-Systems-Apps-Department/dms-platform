# Phase 2 (Accuracy foundation) — progress report

Companion to `docs/ocr/phase1-closeout.md`. Covers the production
prompt's section 6 phase 2: *"eval harness and baseline, 300 DPI
rendering with tiling, normalized coordinates, quality assessment,
preprocessing, per-page routing including hybrid"* — plus the scheduled
reconciler that replaces the poll-time re-apply.

**Status: steps 0, 1, 2, 3, 5, 7 and 8 complete. Step 4 researched and vetoed. Step 6 partial — coordinates done, the four remaining preprocessing steps not built.**

Step 7 closed out on 2026-09-29 with a 50-upload / 10-concurrent run (43
completed, 7 failed, 0 stuck, every failure on one attempt). The rotation
defect that run surfaced is now fixed (Step 7 follow-up), Step 5 is built,
and the LLM tier is excluded by decision rather than deferred.

**Phase 2 is closed.** The confirming 50-upload / 10-concurrent run
(3c313b34, 2026-09-29) came back clean: 46 completed, 4 failed with
exactly the expected codes, 0 stuck, 0 TIMEOUT, 0 WORKER_CRASH. See
"Phase 2 summary" at the end of this document.

> ## ⚠ Every accuracy number in this document is SYNTHETIC
>
> **The real set scores nothing yet.** As of 2026-09-29 `eval/corpus/real/`
> holds 24 supplied documents and `eval/labels/real/` holds 24 **draft**
> labels — but every draft is `"reviewed": false`, and an unreviewed
> document is never scored. A draft is the pipeline's own output, so
> scoring against it would measure the pipeline against itself. Every run
> in this document reports `real: 0 reviewed, 24 awaiting review`, and
> every accuracy number below is still synthetic-only.
>
> The corpus is generated and labelled by the same process being
> measured, on synthetic documents with a modelled scanner degradation.
> It is a **regression instrument**: it can prove a change did not break
> what already worked, and it can veto a change that regresses. It is
> **not** evidence of real-world accuracy and it is **not** evidence for
> OS-NFR-001's ≥95% field-accuracy bar. "78.7% field accuracy" means
> 78.7% on this generator's output, nothing more.
>
> `eval/compare.py` enforces this itself: with no real data it exits
> `VETO-ONLY: nothing regressed, but a synthetic-only result cannot
> justify keeping the change`.
>
> **What would change this:** real documents dropped in
> `eval/corpus/real/`, labelled via `python -m eval.draft_labels`, each
> label corrected by hand and marked `"reviewed": true`. Until then no
> number here should be quoted to anyone as this system's accuracy.

Field accuracy on the synthetic corpus is **60.4% → 93.9%**: Step 3
(orientation) took it to 79.4%, Step 7 (routing) to 93.9%.

| step | what | state |
|---|---|---|
| 0 | Rebuild images so the containers match the source | **done** |
| 1 | Eval harness + recorded baseline | **done** |
| 2 | One coordinate space, normalized coordinates, `schema_version: 2` | **done** |
| 3 | Whole-page orientation correction | **done** — rotated conditions 11.7–14.7% → 92.7%, nothing else moved |
| 4 | 300 DPI rendering with tiling | **vetoed on synthetic evidence** — 0 measured accuracy gain, OOM-kill above ~6 MP/inference. Shipped instead: area-based `OCR_RENDER_MAX_MP`, and `downsampled_from` so scanner pixels are not discarded silently. **Re-test first when the real corpus exists** |
| 5 | Per-page quality assessment | **done** — `quality.py`: effective DPI, blur, contrast, skew; `flags.poor_image_quality` with reasons. Thresholds provisional |
| 6 | Preprocessing toggles, one at a time | **partial** — normalized coordinates verified complete; `/Rotate` normalisation added. Deskew, perspective, denoise, CLAHE **not built** |
| 7 | Per-page hybrid routing | **done**, closeout run 2026-09-29 (43/7, 0 stuck, all attempts=1, peak 6.796 GiB) — `garbage_text_layer` 0.0% → 93.5%, `hybrid_page` 0.0% → 95.3%, overall 79.4% → 93.9%, nothing else moved |
| 8 | dms-core scheduled reconciler (+ V38 marker) | **done**; retirement of the poll re-apply still gated on the 50-upload run |

---

## Step 0 — the running containers did not match the source

Both images predated code that was already committed:

| image | built | source newer than it |
|---|---|---|
| `local-ocr-worker` | 12:21 | `reaper.py`, `models.py`, `repository.py` @ 13:09 — the leaseless-reap fix |
| `local-dms-core` | 10:27 | `V37__processing_jobs_require_lease.sql` @ 13:09 |

So the live stack was missing V37's CHECK constraint and the reaper's
`_reapable` fix. Any measurement taken against it would have described
code that no longer exists.

Rebuilt both, recreated the containers (verified by image digest, not by
`up -d` output), confirmed `flyway_schema_history` at V37, `/readyz`
green, `RestartCount=0` and `OOMKilled=false` on both.

Baseline suites, all green before any Phase 2 change:

| suite | result |
|---|---|
| ocr-worker `pytest` | 183 passed, 2 deselected (`-m model`) |
| dms-core `mvn test` | 41 passed |
| dms-core `*IT` (named explicitly) | 2 passed |
| frontend `vitest` | 20 passed |

Note for whoever runs the dms-core suite next: `contextLoads` boots the
real context and Flyway, so it needs `DB_USERNAME`/`DB_PASSWORD` as well
as `DB_URL`, on the compose network. Without them it fails on a
connection refused that has nothing to do with the code under test.

---

## Step 1 — the eval harness

`backend/workers/ocr-worker/eval/`, per section 5.

```
eval/
  schema.py      V30's seeded document-type schemas, mirrored
  corpus.py      deterministic generator -- document AND label from one call
  conditions.py  the degradation matrix
  harness.py     staging + driving the real OcrPipeline
  metrics.py     CER/WER, per-field precision/recall, extracted-precision, percentiles
  rss.py         peak memory from the container's own cgroup
  run.py         python -m eval.run
  compare.py     python -m eval.compare -- the CI gate
  labels/        committed ground truth, one JSON per document
  reports/       committed JSON + markdown
```

**117 documents**: 3 seeded types (INVOICE, CONTRACT, POLICY) x 13
conditions x 3 replicas — 39 per type, meeting section 5's ">= 30 per
type". Ground truth is exact by construction: `build_base_pdf` returns
the PDF bytes and the label dict from the same call and the same value
table, which is the only arrangement in which the two cannot drift.

One source document per (type, replica) is rendered under *every*
condition, so `invoice-native_pdf-00` and `invoice-rotated_90-00` are the
same invoice. Seeding per condition instead would confound every
cross-condition comparison with the documents also being different.

The runner drives the real `process_version` — claim, run, commit — with
the real `LayoutEngine` and `PaddleOcrEngine`. Only the infrastructure is
swapped (in-memory SQLite + in-memory object store), so a run needs no
Postgres, MinIO or RabbitMQ, and no image rebuild.

### Four defects the harness found in itself

Recorded because each one produced a plausible-looking number that was
wrong, and any of them would have silently mis-graded every later step.
Three of the four looked like product defects.

**1. `extracted`-status precision read 0% for every field.** Not a
product result: `requested_fields_to_output_dict` emits only
`value`/`confidence`/`bbox`, and the per-field status lives solely in
`document_ocr_field_results`. The harness was reading a key that is never
written. Now read from the table. Pinned by
`test_status_comes_from_the_field_results_table_not_the_output_json`.

**2. Every multi_select field scored 0%.** ocr-worker JSON-encodes a
multi_select value and dms-core's `applyOcrFields` decodes it with an
ObjectMapper — that *is* the contract, so `'["a","b"]'` is correct. The
comparator was scoring the conformant value as a miss, which would have
invited someone to "fix" a working contract.

**3. `amount` scored 0% across the entire corpus.** This looked exactly
like a real product defect, and was written up as one before being
checked: the pipeline emits `"41,252.70"` for a `decimal` field, with the
thousands separator the document printed. But that is correct — the
output contract keeps the raw OCR string, and dms-core's `parseNumber`
already strips the comma before storing it, a bug it found and fixed live
against a real invoice's `"2,479,475.00"`. The comparator was the thing
that was wrong. Numeric values now compare numerically, and only
fully-numeric ones, so `PO-74300` and `CC-1042` are still compared as the
identifiers they are.

This one is the argument for `test_eval_metrics.py` existing at all: a
harness bug does not announce itself, it just reports a number, and that
number is indistinguishable from a finding.

**4. The corpus could not measure resolution at all.**
`_images_to_image_only_pdf` hardcoded 200 DPI when computing page size,
so a 300-DPI scan of an A4 page was declared to be an 893x1263pt page
rather than 595x842. The renderer's 1600px cap then scaled every scan
condition to the same raster, and `clean_scan_300`, `low_dpi_scan_100`
and `fax_g4_tiff` returned **byte-identical text and byte-identical CER**
— a corpus that scores a 72-DPI fax exactly like a 300-DPI scan cannot
answer the question Step 4 exists to ask.

### A real limitation, stated plainly

Fixing the page-size bug was not enough. Vector text rasterized straight
from a PDF is cleaner than anything a scanner has produced, and PP-OCRv6
read it essentially perfectly at every degradation level — the conditions
were *still* byte-identical. Two further changes were needed before the
corpus discriminated at all:

- **6pt fine print.** At 9pt, resolution simply does not matter to this
  engine. 6pt is ~11px at the renderer's true ~137 DPI and ~25px at a
  real 300 DPI, and it is what real documents use for terms, remittance
  details and tax identifiers.
- **A scanner degradation model** — optical blur scaled to a fixed
  *physical* size plus resolution-independent sensor noise, applied to
  the scan, fax and photo conditions.

Even so: **this is a synthetic corpus, and its absolute accuracy numbers
are not real-world accuracy.** It is a reliable instrument for
*comparing* a change against the baseline on the failure modes it models,
which is what Phase 2 needs. It is not evidence for OS-NFR-001. A real
holdout set, supplied and labelled separately, is what would be.

Known gap: there is no Tamil or Sinhala condition. The runtime image
carries no system fonts at all — only PDF's base-14 — so `fc-list` is
empty and PyMuPDF has nothing to shape Indic script with. Drawing Tamil
would emit empty boxes and produce a "non-English" fixture containing no
non-English text. Testing the `ta` model and the Sinhala
script-detection path needs a font added to the image first, and is
carried to Step 7.

### Baseline

Recorded on the Step 0 code with no accuracy changes:
`eval/reports/baseline-2026-09-25.json` and `.md`. Every later step is
judged against that file by `python -m eval.compare`.

| metric | baseline |
|---|---|
| field accuracy (OS-NFR-001) | **60.4%** |
| `extracted`-status precision | 74.3% |
| CER mean / p95 | 0.4465 / 1.9798 |
| WER mean | 0.4648 |
| latency p50 / p95 per document | 19.93s / 29.37s |
| peak memory | 3.653 GiB (limit 8.5 GiB) |
| statuses | 117 completed, 0 failed |

60.4% against OS-NFR-001's ≥95% bar, and the shortfall is not spread
evenly — it is four conditions:

| condition | field exact | CER | what it is |
|---|---|---|---|
| `rotated_90` | 11.7% | 0.8264 | Step 3 |
| `rotated_180` | 14.7% | 0.8120 | Step 3 |
| `rotated_270` | 14.7% | 0.8090 | Step 3 |
| `garbage_text_layer` | 0.0% | 1.9576 | Step 7 (routing) |
| `hybrid_page` | 0.0% | 0.9143 | Step 7 (routing) |
| *every other condition* | 83.6–95.3% | 0.03–0.15 | — |

That is the argument for the step ordering: five of thirteen conditions
account for essentially the whole gap, and nine upright, non-hybrid
conditions are already at or near the bar. Raising DPI or adding
preprocessing to the conditions that already score 95% cannot move the
headline; reading the sideways and hybrid pages at all can.

---

## Step 2 — one coordinate space

Done before any accuracy change, and expected to move no accuracy number.

**The bug.** `render_page` capped its zoom at
`_MAX_RENDER_DIMENSION_PX / longest_side_pt`, while
`native_text_blocks_for_page`, `native_text_lines_for_page` and
`tables_for_page` all scaled by the *uncapped* `dpi / 72`. At the default
200 DPI the cap is always active on an ordinary page, so a native-text
bbox sat roughly **46% further down the page than the text it
described** — against a README that claimed "all bboxes, from every
method, are in the same page-pixel coordinate space".

Nothing had noticed because page routing is exclusive: a page is either
`native_text` or `paddleocr`, so the two spaces never appeared together.
Step 7's hybrid routing makes them appear together deliberately, and
normalizing on top of the disagreement would have baked the error into
the output contract permanently.

**The fix.** `layout.PageGeometry` is now the single source of a page's
point size and its one point-to-pixel scale, and all four producers
derive from `render_scale_for`.

**Schema v2**, additive per section 3.11: `bbox_norm` (0–1 page
fractions) on every block, table, cell and field; `geometry` on every
page; `"schema_version": 2` at the top level. Every v1 key keeps its
meaning and type, so `applyOcrFields` needs no change. v1 had no version
key at all, so a missing key means 1.

`bbox_norm` is `null`, never a zero box, where there is nothing to
normalize against — a regex match over concatenated text, or a `.docx`,
which reflows and is never rasterized. A zero box would be drawn by a
review UI as a real highlight in the page corner.

Also fixed: `_spatial_value_on_page`'s "value stacked below the label"
search radius was a hardcoded `40` pixels. That is ~2.5% of an A4 page at
today's scale, so it is now expressed as that fraction — identical
behaviour now, and still meaning the same thing when Step 4 raises the
render resolution, where a literal 40px would silently shrink to ~1.1% of
the page and start missing values.

### A finding that changes Step 4's framing

`OCR_RENDER_DPI` is **currently inert for an ordinary page**. The 1600px
cap binds far below it: an A4 page gets `min(dpi/72, 1600/842) = 1.900`
at 200 DPI, at 300 DPI, and at 600. So "raise `OCR_RENDER_DPI` to 300",
which the README already advertises, changes *nothing at all* on its own.

The real change in Step 4 is removing the cap and replacing it with
tiling; the DPI setting only becomes meaningful once that happens. Today's
effective resolution is ~137 DPI for A4, not the 200 the config says nor
the ~180 the code comment claimed. Pinned by
`test_ocr_render_dpi_is_currently_inert_for_an_ordinary_page`, which is
designed to fail the moment Step 4 lands.

---

## Step 3 — whole-page orientation correction

The single largest item in the baseline's gap, and the cheapest to close.

| | baseline (2026-09-25) | with orientation (2026-09-28) |
|---|---|---|
| field accuracy (OS-NFR-001) | 60.4% | **78.7%** |
| `extracted`-status precision | 74.3% | **94.5%** |
| CER (mean) | 0.4465 | **0.2658** |
| WER (mean) | 0.4648 | **0.2498** |
| peak memory | 3.653 GiB | 3.521 GiB |
| failures | 0 / 117 | 0 / 117 |

| condition | baseline | now |
|---|---|---|
| `rotated_90` | 11.7% | **92.7%** |
| `rotated_180` | 14.7% | **92.7%** |
| `rotated_270` | 14.7% | **92.7%** |
| every other condition | — | **identical, to the digit** |

That last row is the one worth dwelling on. `clean_scan_300`,
`fax_g4_tiff`, `low_dpi_scan_72`, `multipage`, `native_pdf`,
`phone_photo_perspective`, `shadowed_photo`, `skewed_2deg`,
`garbage_text_layer` and `hybrid_page` all report exactly the same field
accuracy, CER and WER as they did before — not "within noise", the same
numbers. The corpus is deterministic, so a change that touched anything
outside the rotated pages would show here, and none did. The classifier
called all 63 upright pages upright.

`python -m eval.compare eval/reports/baseline-2026-09-25.json
eval/reports/step3-orientation-2026-09-28.json` passes, with its own
standing caveat that a synthetic-only result can veto a change and never
justify one (see "The real set", below).

### Why not just set `use_doc_orientation_classify=True`

PaddleOCR 3.x has this stage built in, one constructor kwarg away, and
using it would have been wrong.

Inside PaddleOCR's pipeline the doc preprocessor rotates the page and
every detected box comes back in the **rotated** image's coordinate
space. `bbox` and `bbox_norm` would then describe a page that exists
nowhere — not the stored raster a viewer renders, not the page a review
UI draws highlights over — and nothing in the output would say so. That
is precisely the class of silent coordinate disagreement Step 2 existed
to remove, reintroduced one step later.

So `src/ocr_worker/orientation.py` drives the same model
(`PP-LCNet_x1_0_doc_ori`) directly: classify, rotate, OCR, and map every
box back through the exact inverse before it leaves the worker. A
consumer never learns that a rotation happened, which is the only version
of this feature that is backward compatible.

The inverse map is derived empirically against PIL, not from first
principles, and `tests/test_orientation.py` round-trips a marked page
through `correct_image` and back through `to_original` for all three
angles: a box found in the corrected page has to land on the pixels it
started from.

### Two coordinate spaces, on purpose

Correcting the page splits the pipeline's internal geometry in two, and
conflating them would have quietly broken field extraction on exactly the
pages this step exists to fix:

- **Reading space** (the corrected, upright raster) is where
  `lines_by_page` stays. Spatial field matching asks "what is to the
  right of this label" and "what is stacked below it" — questions that
  only mean anything upright. On a page that arrived sideways, "right of"
  in the stored raster is "below" to a reader.
- **Page space** (the raster as stored) is what `blocks[].bbox`,
  `bbox_norm`, and a matched field's `source_bbox` are expressed in,
  because that is what a viewer and the review UI see.

`_upright_page_heights` follows from the same split: extraction's
"stacked below the label" radius is a fraction of page height, and a
quarter turn swaps a page's dimensions, so feeding it the portrait height
for a page OCR read as landscape would have made that radius wrong by the
page's aspect ratio — in both directions.

### What the residual 2.6 points actually is

A rotated page scores 92.7% against 95.3% for the *same document*
upright. Chasing that gap found one thing and nothing else: across all 27
rotated documents, the only field that differs from its upright twin is
`description`, which reads `eguipment` for `equipment` — a single
`q`→`g` recognition error on one word of one source description,
replicated across the 9 documents that share it. CER on those conditions
is 0.0326 against the upright 0.0322.

So the residual is a recognition artifact of the rotated raster, not a
geometry error and not a pipeline defect. Recorded rather than fixed:
there is nothing here to fix short of Step 4's resolution work.

### The false-rotation rate — the number that decides whether this is safe

Correcting a rotated page is worth ~81 field-accuracy points. Rotating an
upright one costs the same. So the per-condition table above is the
*benefit* side only, and on its own it cannot say whether this stage is
safe to leave on. The number that can is how often an upright page got
rotated.

Measured over the whole corpus by `scripts/dev/step3_orientation_audit.py`,
which walks every document through **the same routing the pipeline uses**
— a page with a native text layer is never rasterized and never
classified; only a page bound for PaddleOCR is:

| | |
|---|---|
| documents | 117 (135 pages) |
| pages never classified (native-text) | **54** |
| pages classified | **81** |
| upright pages wrongly rotated | **0 / 54 — a 0.00% false-rotation rate** |
| rotated pages not corrected | **0 / 27** |

```
confusion (truth -> what was APPLIED, 0 = left alone):
    0 ->   0   x54
   90 ->  90   x9
  180 -> 180   x9
  270 -> 270   x9
```

Perfect in both directions on this corpus, including `skewed_2deg` —
a 2° skew is not a quarter turn, and all 9 of those were correctly left
alone rather than snapped to 90°.

The 54 never-classified pages are the routing claim, measured rather than
asserted: `native_pdf` (9), `garbage_text_layer` (9), `hybrid_page` (9)
and `multipage` (27, three pages each) all carry a text layer, so they
are never rasterized and the classifier never sees them. Pinned in the
unit suite too, by asserting the classifier was never *called* — not
merely that nothing was recorded — including on a mixed document where
page 1 has a text layer and page 2 does not.

**And a warning the confidence numbers make unavoidable.** Across all 81
classified pages the score ranged from **0.9205 to 0.9284** — a total
spread of 0.008, on pages ranging from a clean native raster to a 72-DPI
fax to a page upside down. This model's score is very nearly a constant.
It cannot be calibrated against, and `OCR_ORIENTATION_MIN_CONFIDENCE`
must not be presented as a tuned precision/recall trade-off: at any
threshold below ~0.92 every prediction is applied, and at any threshold
above ~0.93 none is. It is an on/off switch wearing a float's clothing.

This is also why the eval report now carries the orientation accounting
itself (`preprocessing.orientation` in the JSON, a section in the
markdown), rather than leaving it to a one-off script: a stage that
silently started rotating good pages would otherwise appear only as a
diffuse accuracy drop with nothing pointing at the cause. The arithmetic
has its own tests in `test_eval_metrics.py`.

### What the eval could not prove, and how that gap was closed

`eval/run.py` drives the real `OcrPipeline` with real models, but it
builds that pipeline itself, in its own process, against in-memory SQLite
and an in-memory object store. So it never touches `worker_pool.py`'s
pool-worker initializer, the RabbitMQ path, or what actually lands in
`ocr_outputs` — and all three are places a change like this can be wired
in wrongly while every number still looks right.

Closed two ways.

**The image was rebuilt and the container recreated**, verified by image
digest rather than by `up -d` output (same discipline as Step 0). Both
pool workers logged `warmed up`, `/readyz` went 503 → 200, health
`healthy`, `RestartCount=0`, `OOMKilled=false`. That is the only
execution of `PaddleDocOrientationClassifier.warm_up()` in a real pool
worker anywhere in this step's evidence.

**One rotated page was driven through the real queue path**
(`scripts/dev/step3_orientation_live_check.py`): staged in Postgres +
MinIO, published as an `outbox_events` row so dms-core's own OutboxRelay
relays it, then the stored output JSON read back out of MinIO.

```
ocr_status=completed  last_error=None
schema_version=2
method=paddleocr  geometry={width_px: 1600, height_px: 1131, render_scale: 0.912071}
preprocessing={"orientation": {"angle": 90, "confidence": 0.9278, "applied": true}}
blocks=50   blocks outside the stored page: 0
first block: "Contoso Supplies PLC"  bbox [1474, 104, 1501, 374]
text: Contoso Supplies PLC / INVOICE / 15 Harbour Road, Colombo 03 / ...
      Invoice Number: INV-2026-05426 ...
```

The first block's bbox is the detail worth reading. It is **27px wide and
270px tall, hard against the right edge** of a landscape 1600×1131 page —
which is exactly what a line of text looks like when the page is stored
sideways. OCR read that line upright; the box came back describing where
it really is on the page a viewer renders. All 50 blocks land inside the
stored page.

### Cost, measured

| | |
|---|---|
| marginal RSS per pool worker | **1 MiB** |
| classification time per page | **0.02–0.07s** (against ~20s for that page's OCR) |
| model | `PP-LCNet_x1_0_doc_ori`, ~7MB, already in the `ocr_worker_models` volume |

The 1 MiB is the number that matters and it is easy to measure wrongly. A
standalone probe attributes **618 MiB** to this classifier — which is
paddle's own framework import, already resident in any process that holds
PaddleOCR. Measured the way production actually loads it (PaddleOCR
first, then the classifier on top, in one process), it is 1 MiB, flat
across 10 further classifications. No change to `mem_limit` is warranted;
the eval run's own peak in fact came in slightly *below* the baseline's.

Classifier accuracy on the corpus, measured directly before any of this
was wired in: **90/90**, every rotated page right and no false positive
on any of the 63 upright ones.

### The confidence floor, and what it is not

`OCR_ORIENTATION_MIN_CONFIDENCE` (0.6) gates whether a predicted rotation
is applied. The error here is asymmetric — correcting a rotated page is
worth ~81 points and rotating an upright one costs the same — so "unsure"
has to mean "leave it alone". Below the floor the prediction is recorded
with `"applied": false` and the page is OCR'd as-is, never rejected.

Stated plainly because the number invites more confidence than it
deserves: `PP-LCNet_x1_0_doc_ori` returned ~0.92 for **every** page in
the corpus, regardless of condition and regardless of whether it was
right. Its score carries little discriminative signal to calibrate
against. 0.6 is a floor against a degenerate page (blank, near-black, a
scanner separator sheet) whose score does collapse — not a calibrated
precision/recall trade-off, and it should not be described as one until
there is real data to calibrate on.

### EXIF orientation — already handled, now pinned, and one real bug

Section 3.2 requires applying EXIF orientation for camera images, and
nothing in the worker did it explicitly. That looked like a plain gap. It
is not: **fitz/MuPDF applies it itself during rasterization.** Verified
live for all of Orientation 1/3/6/8, by where a marked corner lands
rather than merely by whether the page dimensions swapped — dimension
parity alone cannot answer the 180° case at all.

It is now pinned by `tests/test_layout.py`, because it is load-bearing
behaviour of a dependency that nothing in this codebase asserted. A
future move away from fitz would otherwise have started feeding OCR
sideways phone photos with nothing failing.

One path genuinely did not get it for free. `_decode_via_pil_to_png`
handles WEBP/HEIC/HEIF by decoding with Pillow and re-encoding as PNG —
and PNG carries no orientation tag, so anything not applied there is lost
permanently. Verified live: a WEBP written with `Orientation=6` opens at
its raw 400×200 with tag 274 still set, i.e. Pillow does not apply it,
and the page reached OCR sideways. `ImageOps.exif_transpose` now runs on
that path. HEIC turned out not to need it (pillow-heif applies the
rotation on open and resets the tag), but the call is a no-op on an
already-upright image, so the path stays uniform rather than carrying a
per-format exception.

### `pages[].preprocessing`

New in the output contract, per section 3.4 ("each step individually
toggleable in config and recorded in the output"):

```json
"preprocessing": {"orientation": {"angle": 90, "confidence": 0.93, "applied": true}}
```

`{}` for a `native_text` page, which is never rasterized, and `{}` when
the toggle is off. The decision is recorded even when nothing was
applied, deliberately: "the classifier looked and said upright" and "the
classifier never ran" are different facts about a document, and only the
second is a reason to suspect the configuration.

Still `schema_version: 2`, not 3. Phase 2 is one unreleased unit and this
key is additive within it; a version number that moved every few days
would tell a consumer nothing. dms-core does not parse `pages` at all, so
nothing downstream is affected either way.

### Not applied to zone / rubber-band OCR

Recorded as a deliberate limit, not an oversight.

A rubber-band box is drawn by the user on the page **as displayed**, so
reorienting underneath it would change what their own selection means —
the fix there is to reorient the preview too, which is Phase 4's review
UI work.

Zone templates are authored upright and arguably *should* be applied in
reading space. Two reasons not to do it now: no document type in this app
has zone rows configured at all (`document_type_ocr_zones` is still only
written by test fixtures), and the eval corpus does not exercise that
path, so the change would ship unverified. Carried forward.

### Also in this step

- `eval/run.py` gained a `no_orientation` variant and both orientation
  settings in `settings_fingerprint`. The second matters more than it
  looks: a fingerprint that omits a toggle is worse than no fingerprint,
  because it makes two incomparable runs look comparable. It also now
  says out loud that `baseline` is a *moving* variant — it means
  "whatever config.py's defaults are", so re-running it will legitimately
  no longer reproduce `baseline-2026-09-25.json`.
- `eval/harness.py`'s `build_pipeline` mirrors `worker_pool.py`'s
  initializer, warm-up included. A preprocessing stage that production
  has and the harness does not would make every number it prints a
  measurement of something else.
- `ocr_engine.py`'s comment claiming "layout.py already renders pages at
  a known orientation via PyMuPDF — nothing to unwarp/reorient" was
  simply false, and had been the standing justification for leaving this
  disabled. Replaced with the real reason the flag stays off.
- `eval/realset.py`'s `doc_id_for` turned a file named `....pdf` into the
  id `...`, which `label_path_for` would then write as a directory
  reference. A pre-existing red test, unrelated to this step; fixed here
  because a red suite hides the next real failure.

---


## Step 4 — 300 DPI with tiling: research, and a recommendation not to ship it

**Status: researched, not implemented.** The measurements below are the
reason. They are recorded here rather than in a discarded branch because
the next person to read "Phase 2 raises OCR_RENDER_DPI to 300" in the
README deserves to know it was tried and what it cost.

Reproducible via `scripts/dev/step4_resolution_probe.py` (whole page) and
`scripts/dev/step4_fineprint_probe.py` (the 6pt band only).

### The cost is real and it is steep

One A4 invoice (`invoice-clean_scan_300-00`), one process, the production
`mem_limit` of 8.5 GiB, render cap bypassed:

| dpi | raster | MP | time | peak RSS | CER | chars |
|---|---|---|---|---|---|---|
| 137 (today) | 1133×1603 | 1.8 | 53.5s | 2.06 GiB | 0.0164 | 974 |
| 200 | 1654×2340 | 3.9 | 85.5s | 3.49 GiB | 0.0164 | 974 |
| 250 | 2067×2925 | 6.0 | — | **OOM-killed** | — | — |

Memory grows about **0.7 GiB per megapixel** on a ~0.8 GiB warm floor,
linearly across the range that survives. At 250 DPI the kernel killed the
process — `OOMKilled=true`, exit 137 — with **one** inference stream at
the whole container's limit. Production runs two.

So the old `_MAX_RENDER_DIMENSION_PX = 1600` comment, which claimed a
correctly-capped ~3000×4000 input "still OOM-killed the worker", is
confirmed rather than inherited: re-measured on today's code, today's
models and today's limit.

**The ~6 MP ceiling belongs to one inference call, not to whole pages.**
A 5.6 MP *crop* (the fine-print band at 400 DPI) dies exactly as a 6.0 MP
page does. That is the number any tiling scheme has to size tiles
against, and it is why tiling is not optional for a DPI increase — it is
the only thing that makes one possible.

### The benefit, measured where it was designed to show up

The corpus carries 6pt fine print for exactly this question (Step 1: "at
9pt, resolution simply does not matter to this engine"). Cropping only
that band, so whole-page CER cannot dilute the result:

| dpi | crop | MP | time | fine-print CER | fine-print field |
|---|---|---|---|---|---|
| 137 | 1133×577 | 0.7 | 13.0s | **0.0000** | `cost_centre` = CC-4450 ✓ |
| 200 | 1654×843 | 1.4 | 19.4s | **0.0000** | ✓ |
| 300 | 2480×1264 | 3.1 | 39.7s | **0.0000** | ✓ |
| 400 | 3306×1685 | 5.6 | — | OOM-killed | — |

PP-OCRv6 reads the 6pt print with **zero character errors at today's
resolution**. There is no headroom for 300 DPI to recover, and the
whole-page run says the same thing a second way: 137 and 200 DPI produced
**byte-identical output** — same CER to four places, same 974 characters —
for 1.6× the time and 1.7× the memory.

Recognition is the reason, and it is worth understanding rather than
just observing. PaddleX's detection config is `limit_type: min`,
`limit_side_len: 64`, `max_side_limit: 4000`, so detection never
downscales — it really does see every extra pixel, which is why memory
and time scale with them. But the recognition model resizes each detected
line crop to a fixed height regardless. An 11px-tall line at 137 DPI and
a 25px one at 300 both arrive at the recogniser as the same-sized crop,
and on cleanly-rendered text 11px already carries enough signal.

### What this does NOT prove

The corpus draws vector text and degrades it with a scanner model **we
wrote**. Real 6pt print, on a real scanner, at a real angle, is worse
than our model of it. This experiment vetoes the change on the evidence
available; it does not establish that resolution would buy nothing on a
real document. That is the same limitation the banner at the top of this
document describes, and it bites hardest here, on a question about
image fidelity.

There is also a real loss today that the corpus cannot show, because
every corpus document is a *rendered* page rather than a supplied raster:

```
a real 300-DPI A4 TIFF (2480x3508 native)  ->  scale 0.61  ->  1131x1600
```

A genuine 300-DPI scan is downsampled on arrival and roughly **4.8× the
pixels the scanner captured are discarded**. The engine does not appear
to need them on synthetic pages. Whether it needs them on a real one is
exactly what the empty real set cannot answer.

### Where the missing accuracy actually is

For scale, against the same corpus that says resolution is worth zero:

| condition | field accuracy | worth |
|---|---|---|
| `garbage_text_layer` | **0.0%** | Step 7 (routing) |
| `hybrid_page` | **0.0%** | Step 7 (routing) |
| everything resolution could touch | already 83.6–95.3% | Step 4 |

Two conditions at 0.0% are ~14 points of the headline. Resolution is 0.

### DECISION (2026-09-28) — resolution is vetoed, on synthetic evidence only

**Recorded as a decision rather than a conclusion, because the evidence
it rests on cannot support a conclusion.**

Tiling and a 300 DPI default are **not being built**. Every number that
supports that choice comes from the synthetic corpus, and the synthetic
corpus is generated and degraded by code in this repository. It is
entitled to veto a change. It is not entitled to settle the question.

**This is the first item to re-test once the real corpus exists** —
before Step 5, before any further preprocessing work, ahead of whatever
else is queued at that point. Re-test it with:

- **a real 300 DPI flatbed scan** of a document with genuine small print
  (terms, remittance details, a tax identifier), not rendered text;
- **a real phone photo** of the same document, since a camera's blur,
  uneven lighting and perspective are the conditions our degradation
  model is least likely to resemble.

If either shows a real gain from resolution, this decision is wrong and
the tiling work in this section is the design to start from. Nothing
here should be read as "resolution does not matter" — only as "on
documents we generated ourselves, we could not measure it mattering."

What **was** taken from this research is in "What shipped instead"
below: the cap is now denominated in the unit the constraint is actually
in, and it no longer discards scanner pixels silently.

### Everything on this list is provisional, for the same reason

Resolution is not the only thing decided without real evidence. These
were all tuned against generated fixtures and have never been seen
against a real document. **Re-measure all of them, in this order, once a
real labelled corpus exists:**

| # | setting | default | tuned against | what could be wrong |
|---|---|---|---|---|
| 1 | **resolution** (`OCR_RENDER_MAX_MP`) | 1.81 | synthetic 6pt print that reads with zero errors at 137 DPI | real small print on a real scanner is worse than our degradation model; a real 300 DPI scan and a real phone photo are the test |
| 2 | `OCR_ROUTING_MAX_GARBAGE_RATIO` | 0.30 | a `(cid:NN)` layer that scores ~1.0 and clean text that scores 0.0 | nothing in the corpus lands *between* those. A real PDF with a partly-broken font could sit near the threshold and be decided by a number nobody has calibrated |
| 3 | `OCR_ROUTING_MIN_PLAUSIBILITY` | 0.50 | the same two extremes, plus identifiers and numbers | this is also a **documented deviation** from section 3.6, which asks for dictionary tokens — see below |
| 4 | `OCR_ROUTING_MIN_UNCOVERED_IMAGE` | 0.15 | one synthetic hybrid page whose body is ~91% of the page | a real form with a photographed signature panel might sit near 0.15, where the choice between `native_text` and `hybrid` decides whether a field is read at all |
| 5 | `OCR_ORIENTATION_MIN_CONFIDENCE` | 0.6 | a model whose score never left 0.9205–0.9284 | effectively an on/off switch; see Step 3 |

The corpus can tell us these thresholds do not *break* the fixtures it
contains. It cannot tell us they are right, because it contains no case
that sits near any of them — every synthetic fixture is comfortably on
one side or the other. That is a property of fixtures we generated, and
it is exactly what a real corpus would supply.

### Pending work, deliberately not built

Separate from the thresholds above: these are known, specified gaps with
a decision already taken not to close them yet.

| what | why it is pending | what it blocks |
|---|---|---|
| **Per-tenant locale wiring** (§3.8) | `parse_amount`/`parse_date` take a `locale` per call and the plumbing to *supply* one per tenant does not exist — dms-core would send it on `document.version.ocr.requested`, or resolve it alongside `requested_fields`. Every call today uses the `day_first` default | a comma-decimal tenant's `1.234` stays flagged `ambiguous` instead of resolving to 1234; a month-first tenant's dates are read day-first |
| **`processing_job_attempts` history table** | "this version has crashed a worker twice" is currently inferred from `processing_jobs.attempts`, because a requeue reuses the row and overwrites `last_error`. One row per attempt (cause, started/finished timestamps, worker id) would make it a real per-cause count | the OOM-twice rule is a proxy: a version that timed out once and then crashed is dead-lettered one attempt early. Also the only way to answer "how often does this fail, and how" from data rather than from logs |
| **Confidence calibration** (§3.10) | needs the real corpus to calibrate tier weights and the `extracted`/`low_confidence` threshold against | field confidences remain the hardcoded values §3.10 calls out |
| **Anchor-relative zones, invoice line items** (§3.8) | Phase 3 proper; only the deterministic pattern/validation work is done | — |
| ~~**The LLM tier**~~ | **EXCLUDED, not pending** — see "Scope decision — no LLM, ever". Document content never leaves this worker | — |

### Recommendation as put to review

Do not raise the default. Three options were put:

1. **Stop Step 4**, document `OCR_RENDER_DPI` honestly as inert, and go
   to Step 7, where the measured accuracy actually is.
2. **Build tiling, ship default-off** — makes `OCR_RENDER_DPI` real (today
   raising it does nothing at all while the README advertises 300),
   stops downsampling genuine high-DPI scans for anyone who opts in, and
   leaves a knob to re-evaluate once the real corpus exists.
3. **Build tiling and enable 300 DPI by default** — advised against: a
   measured ≥1.6× slowdown for no measured gain, and
   `OCR_TIMEOUT_PER_PAGE_S=25` would have to rise with it, which pushes a
   50-page document past an hour of one pool worker.

Option 1 was chosen.

### What shipped instead

Two things the research made obviously wrong, neither of which needs
tiling:

**1. The cap is now on area, not on the longest side.**
`OCR_RENDER_MAX_MP` replaces `_MAX_RENDER_DIMENSION_PX = 1600`. Memory is
driven by pixels — ~0.7 GiB per megapixel, measured — so the cap is
denominated in pixels. A longest-side cap punished any page that was not
roughly A4-shaped: a 2000×500pt landscape page was held to 1600×400 =
**0.64 MP**, a quarter of what an A4 page got, purely for its aspect
ratio. The same page now gets the same budget as any other.

**The default is 1.81, not 4.0, and that took two corrections to get
right.** 1.81 MP is the exact area the old cap produced on A4
(1131×1600 = 1,809,600 px), so an ordinary page renders
pixel-identically to before. The change is one of *shape*, not of
resolution.

It was briefly set to 4.0 — enough headroom under the ~6 MP kill point
to let A4 render at the full 200 DPI. That was wrong twice over, and the
review that caught it was right:

- It contradicted this section's own decision. Step 4's whole finding is
  that the extra pixels buy nothing measurable (137 and 200 DPI gave
  byte-identical text) and cost +60% time and +70% memory. Reshaping the
  cap must not smuggle in the resolution rise that was explicitly vetoed.
- **It caused real failures.** The 50-upload concurrent run at 4.0 MP
  produced **8 failures against a Phase 1 baseline of 4** — see "The
  timeout regression" below. Four ordinary documents that had never
  failed before hit `TIMEOUT`, including two *one-page* documents blowing
  an 85s budget.

| page | old (1600px longest side) | at 4.0 MP | at 1.81 MP (shipped) |
|---|---|---|---|
| A4 | 1131×1600 (1.81 MP) | 1653×2339 (3.87 MP) | **1131×1600 — identical** |
| Letter | 1236×1600 (1.98 MP) | 1700×2200 (3.74 MP) | 1183×1531 (1.81 MP) |
| wide 2000×500pt | 1600×400 (**0.64 MP**) | 4000×1000 (4.00 MP) | **2690×673 (1.81 MP)** |

One honest wrinkle: **Letter loses ~8% of its pixels.** A longest-side
cap hands out area as a function of aspect ratio, and Letter (612×792) is
squarer than A4, so 1600px gave it 1.98 MP against A4's 1.81. Equalising
that is what an area budget is *for*, and an 8% change is far below
anything Step 4 could measure moving accuracy — but it is a change, and
`test_letter_loses_a_little_area_and_that_is_the_point` records it rather
than letting it pass as "no change".

`max_megapixels=None` means the default budget, **not** "uncapped". That
was a real bug for one commit, caught by an existing test: with None
meaning uncapped, a caller that simply forgot rendered a 3000×4000pt page
at 12500×16667 = **208 MP** — at 0.7 GiB/MP an instant OOM kill taking
every other in-flight document in that worker with it. The safe value has
to be the one you get by forgetting.

**2. Discarded scanner pixels are no longer discarded silently.** When a
page carries a raster larger than what was rendered, the worker logs it
with both sizes and the budget, and the page's `geometry` gains
`downsampled_from`:

```json
"downsampled_from": {"width_px": 2480, "height_px": 3508, "megapixels": 8.7, "scale": 0.7014}
```

Absent means nothing was discarded — a vector page, or a raster rendered
at or above its own size — never "nobody checked", because the field is
useless for deciding whether to re-scan if absence is ambiguous.

Detection uses PyMuPDF's `get_image_info()`. The obvious choice,
`get_images()`, was tried first and is wrong here: it lists a PDF page's
image XObjects and returns **nothing at all** for a bare JPEG or TIFF,
because MuPDF synthesises a page around such a file rather than embedding
one in it. That is every phone photo and every single-image scan — the
majority of the population this field exists to describe — silently
reporting "nothing lost". Both shapes are now covered by tests.

Also fixed regardless: the README's `OCR_RENDER_DPI` row promised "Phase
2 raises this to 300 with tiling". A config knob that silently does
nothing is worse than an absent one.

### The timeout regression, and what it proves

The 50-upload / 10-concurrent run at `OCR_RENDER_MAX_MP=4.0` produced
**8 failures**. Phase 1's run of the identical harness and fixture set
produced **4**, and those four are the deliberate-failure set:

| fixture | error code | expected? |
|---|---|---|
| `corrupt_tiny_fragment.pdf` ×2 | `CORRUPT_FILE` | **yes** — the fixture is a truncated PDF |
| `password_protected.pdf` ×2 | `ENCRYPTED_PDF` | **yes** — the fixture is encrypted |
| `rotated_native_text.pdf` ×2 | `TIMEOUT` (85s budget, **1 page**) | **no — regression** |
| `scanned_image_only.pdf` | `TIMEOUT` (85s budget, **1 page**) | **no — regression** |
| `large_35page_scanned.pdf` | `TIMEOUT` (935s budget, died before page 16/35) | **no — regression** |

A one-page document blowing an 85-second budget is not a marginal
result. The budget is armed inside the pool worker once PyMuPDF has
opened the file, so queue waiting is not in it — 85s is 85s of actual
work on a single page.

The cause is the thing this section already argued against: at 4.0 MP
every ordinary page carried 2.1× the pixels, roughly 1.6× the inference
time, and under a 10-way concurrent burst on two pool workers that was
enough to push ordinary single-page documents past a budget they had
always cleared. The accuracy gain for that was, measured, zero.

Peak memory on that run was **6.87 GiB of 8.5** (80.8%, 1.63 GiB
headroom) — so the budget "fit" on the metric everyone thinks to check,
and still broke four documents on the one nobody did. Worth remembering
the next time a limit is justified by headroom alone.

The run also reported `stuck_non_terminal: 1`, which looks like a
violation of Phase 1's "zero stuck `processing` versions" exit criterion
and is **not** one. Checked rather than assumed: the second copy of
`large_35page_scanned.pdf` was on attempt 2, claimed at 07:33:03 with a
lease valid to 08:03:03, while the harness gave up at its own 3600s
patience limit at 07:37. A legitimate retry in flight, with the lease
machinery working exactly as designed — the harness's window closed
first. The reaper is not at fault and neither is the job.

Both the default and the finding stand as the argument for measuring a
resolution change end to end, under concurrency, rather than on one page
in isolation.

---

## Step 7 — per-page routing

The largest single accuracy change in Phase 2, and the cheapest to
justify: two whole conditions went from **0.0%** to 93.5% and 95.3%.

### The rule that was wrong

"Docling found any text on this page, so use it." One line, and it cost
two entire conditions of the corpus:

- **`garbage_text_layer`** — a real scan carrying an *invisible*
  `(cid:NN)` text layer, which is what a bad scanner-OCR pass leaves
  behind. A human sees a clean page; the router saw a text layer, took
  the native path, and returned confident nonsense with `confidence:
  1.0`.
- **`hybrid_page`** — a typed header over a scanned body. The header made
  the page "native", so PaddleOCR never ran and the entire body was
  silently dropped. Measured directly, before the change:

  ```
  invoice_number        expected='INV-2026-05426'        actual=None    not_found
  invoice_date          expected='2026-07-11'            actual=None    not_found
  amount                expected='41252.70'              actual=None    not_found
  currency              expected='EUR'                   actual=None    not_found
  vendor_supplier_name  expected='Contoso Supplies PLC'  actual='Contoso'  low_confidence
  ```

  Every field in the body missing; the one that returned came from the
  header, truncated.

Both are the same failure: the old rule asked *whether* there was text,
never whether it was any good or whether it covered the page.

### Before / after

Two full runs differing in **exactly one setting**, `ocr_routing_enabled`.
A comparison against `baseline-2026-09-25.json` would have been worthless
here — that report predates both orientation correction and the
render-budget reshape, so three changes' worth of movement would have
been attributed to routing.

| condition | before | after |
|---|---|---|
| `garbage_text_layer` | **0.0%** | **93.5%** |
| `hybrid_page` | **0.0%** | **95.3%** |
| `clean_scan_300` | 95.3% | 95.3% |
| `fax_g4_tiff` | 94.2% | 94.2% |
| `low_dpi_scan_72` | 92.2% | 92.2% |
| `multipage` | 95.3% | 95.3% |
| `native_pdf` | 95.3% | 95.3% |
| `phone_photo_perspective` | 89.4% | 89.4% |
| `rotated_90` / `_180` / `_270` | 93.5% | 93.5% |
| `shadowed_photo` | 95.3% | 95.3% |
| `skewed_2deg` | 95.3% | 95.3% |
| **overall** | **79.4%** | **93.9%** |

Every non-target condition is identical to the digit. CER 0.2654 →
0.0549; `extracted`-status precision 95.4% → 96.0%; 0 failures either
side.

**Latency is not comparable between these two runs** and is not quoted as
a result. Both shared a 4-core machine with other work, and the after run
in particular ran alongside the container rebuild. See "Clean latency"
for numbers taken on a quiet machine.

### Per-fixture routing decisions

Every one of the 18 target fixtures, from the report's own per-document
`routing` record:

| fixture | route | reason |
|---|---|---|
| `*-garbage_text_layer-*` (9) | `paddleocr` | text layer is **97%** unreadable glyphs |
| `*-hybrid_page-*` (9) | `hybrid` | **86%** of the page is image with no text layer over it |

Unanimous within each condition, and both a long way from their
thresholds (97% against a 30% bar; 86% against 15%). That is the
"provisional thresholds" problem made visible: the corpus proves the
rules fire, and says nothing about where they should sit.

### Where the remaining loss is: extraction, not the merge

Scored with the harness's own comparator (`field_matches`, which compares
numerics numerically -- an ad-hoc string comparison here produced a false
"the `amount` comparator is broken" reading before being checked):

```
native_pdf               not_found=5  wrong=0   {retention_class: 5}
hybrid_page              not_found=5  wrong=0   {retention_class: 5}
garbage_text_layer       not_found=5  wrong=2   {retention_class: 5, description: 2}
phone_photo_perspective  not_found=5  wrong=6   {retention_class: 5, tags: 3, invoice_number: 2, review_due_date: 1}
low_dpi_scan_72          not_found=8  wrong=0   {retention_class: 5, cost_centre: 3}
```

**`hybrid_page`'s failure set is byte-identical to a clean native
page's.** No duplication, no loss, no misplacement — the merge is clean,
and the test that would catch a regression (`test_native_header_text_
appears_exactly_once_in_the_merged_output`) makes the OCR engine return
the header's own text, so it fails unless only the uncovered region was
cropped.

The only condition still under 90% is `phone_photo_perspective` (89.4%),
and its extra six are OCR-quality errors on a perspective-distorted
photo — extraction input quality, not routing and not merging.

**The real finding is a universal `retention_class` floor.** It is
`not_found` on **65 of the 78** documents that expect it — all 39 POLICY
and 26 of 39 CONTRACT — regardless of condition, including on perfect
native pages. That single field is ~4.3% of the headline and is now the
largest remaining extraction gap. `low_dpi_scan_72`'s extra three are
`cost_centre`, the 6pt fine-print field, which is genuinely unreadable at
72 DPI.

### What the routing actually does

`src/ocr_worker/routing.py`, pure and model-free, three checks per page:

1. **garbage ratio** — `(cid:NN)` runs, U+FFFD, control characters.
2. **plausibility** — word *shape*. A documented deviation from section
   3.6, which asks for dictionary tokens; see below.
3. **uncovered image coverage** — image area the text layer does not
   explain, by coordinate-compression sweep so overlapping text boxes are
   not counted twice.

**Order matters, and getting it wrong hides the worst page.** Garbage is
checked *before* coverage, because a junk layer produces junk *boxes*
too, and those would otherwise count as explaining the image beneath them
— so the one page that most needs OCR would be the one that never gets
it.

Every threshold is asymmetric on purpose: re-OCRing a good native page
trades exact text for a guess. A few unmapped glyphs from a logo font do
not condemn a real layer, and a letterhead logo does not make every
native PDF in the system pay for a second inference pass.

A hybrid page's blocks each carry `"source": "native_text" | "paddleocr"`,
and only the uncovered regions reach the engine.

### Deviation: plausibility is a shape heuristic, not a dictionary

Section 3.6 asks for "the ratio of dictionary-like tokens for the
configured languages". This scores token shape instead (a word has a
vowel; it does not run five consonants without one).

A dictionary would be wrong on exactly the documents this system handles.
A real invoice is mostly `INV-2026-05426`, `PO-74300`, `CC-4450`,
`Contoso Supplies PLC`, `80gsm` — identifiers, vendor names, part codes
and units. None is in a dictionary, all are correct, and a
dictionary-ratio metric would score a perfectly good text layer as
implausible and send it to be re-OCR'd, which is the expensive direction.
It would also need shipping and versioning per configured language, with
Tamil a stated target (section 3.7), for a check that is a tie-breaker
rather than a primary signal — `garbage_ratio` already catches the real
case.

What it costs: this cannot detect a text layer that is well-formed but
simply *wrong*. Neither could a dictionary.

### A gate bug found by using it

`eval/compare.py`'s `verdict()` reported **"the real set regressed"**
whenever anything blocked — so this run, whose only flag was peak memory,
named a regression in a set that does not exist (there is no real
corpus). Fixed to name what actually blocked, with tests. A gate that
misdiagnoses sends someone to look in the wrong place, which is worse
than one that only says "failed".

The memory flag itself is real and expected: 3.66 → 4.29 GiB
single-process, because hybrid pages now rasterize *and* OCR, and
garbage-layer pages now OCR at all. Whether that is safe with two pool
workers is what the concurrent run below answers.

### The concurrent run, and what it cost to get one

50 uploads, 10 of them a simultaneous burst, through the real outbox path
(`scripts/dev/phase1_verification.py --trigger outbox`, the default —
dms-core's own `OutboxRelay` publishes, so both the trigger and the
outcome direction are exercised).

| | pre-fix (2026-09-28 16:39) | closeout (2026-09-29) |
|---|---|---|
| completed / failed | 43 / 7 | **43 / 7** |
| stuck non-terminal | 0 | **0** |
| wall clock | 3471s | 3483s |
| max attempts on any item | **2** | **1** |
| `WORKER_CRASH` | 1 | **0** |
| peak RSS | 6.715 GiB | **6.796 GiB** (80% of 8.5) |

The headline is deliberately unchanged, and that is the point: the same
43/7 split, the same zero stuck rows, with **every failure resolved in a
single attempt**. Four of the seven are fixtures that exist to fail —
`password_protected.pdf` (`ENCRYPTED_PDF`, 0.3s) and
`corrupt_tiny_fragment.pdf` (`CORRUPT_FILE`, 0.0s), twice each. A clear
status for every file means a clean error code, not a success.

**What actually changed is the retry behaviour.** Pre-fix,
`large_35page_scanned.pdf` came back on attempt 2 carrying
`WORKER_CRASH: pool worker died with exitcode=-9` — an OOM kill the
worker never survived long enough to explain, which the reaper then
retried as an ordinary transient crash. It now fails once, as `TIMEOUT`,
with the cause written by the parent (`record_death_cause`). At 4 MP the
same pathology spent ~40 of 60 minutes retrying one document five times;
this run has no retry storm anywhere in it.

**Two things this run does not say.** The `peak_was_reset_first: false`
caveat applies — `/sys/fs/cgroup` is read-only in these containers — but
the container was force-recreated immediately before the run, so the
high-water mark covers this run plus its own warm-up, which is
legitimately steady state. And **6.796 GiB is the honest number, not the
6.176 GiB** an earlier partial sample suggested: that sample came from a
run Postgres was shut down under at 49/50, so it never saw the tail.
Headroom is **1.70 GiB**, not 2.32.

### A timeout the render fix did not fix

`rotated_native_text.pdf` timed out on both copies, at 122.9s and 123.0s
against an 85s budget — pre-fix it was 115.4s and 115.3s. The cheap
native-size path removed a real cost and was worth doing; it was not what
was killing this document.

Reproduced with no competing load, and the asymmetry is absurd on its
face:

| fixture | pages | time |
|---|---|---|
| `native_text.pdf` | 1 | **1.6s** |
| `large_35page_native.pdf` | 35 | 114.5s / 124.3s |
| `rotated_native_text.pdf` | 1 | **87.3s** |

One rotated page costs what 35 unrotated copies of *the identical page*
cost. The fixture is `native_text.pdf` with `doc[0].set_rotation(90)` and
nothing else.

**It is Docling's table-structure pass.** Same bytes, same converter,
one option changed:

| `do_table_structure` | page | time | tables |
|---|---|---|---|
| `True` | upright | 1.2s | 0 |
| `True` | rotated | **88.5s** | 1 |
| `False` | upright | 1.2s | 0 |
| `False` | rotated | **1.6s** | 1 |

On the sideways page the layout model finds a table that is not there,
and TableFormer then predicts a grid for it. Docling's own log says what
happens next:

```
MatchingPostProcessor WARNING  24 of 24 pdf cells matched neither a row
nor a column band of the 378x2 grid and were dropped from the table
```

A **378-row** grid, on a page with 24 cells. Every real cell is dropped,
`tables_for_page` returns empty, and the 87 seconds buys nothing at all.

**The trigger is the aspect swap, not rotation**, and the cost is
unstable:

| `/Rotate` | time | tables | predicted grid |
|---|---|---|---|
| 0 | 1.4s | 0 | — |
| **90** | **85.0s** | 1 | **378 × 2** |
| 180 | 1.4s | 0 | — |
| **270** | **2.6s** | 1 | **8 × 2** |

`/Rotate 180` keeps the page portrait and is untouched. 90 and 270 both
present it as landscape and both hallucinate a table — but one predicts
378 rows and the other 8, a 33× cost difference between two near-mirror
inputs. Nothing here is bounded by anything.

**This is not a synthetic-only artifact.** `/Rotate` is what scanners and
phone-scanner apps emit, and the cost lands on any landscape-presenting
page — rotated or natively landscape. It stayed invisible until now
because the `rotated_90/180/270.png` fixtures are bare images with no
`/Rotate` attribute at all; they complete in 21–24s.

Three ways out, none free, none built:

1. **Normalise `/Rotate` to 0 before Docling sees the file** — bake the
   rotation into the content stream. Keeps table structure working
   everywhere and removes the pathology at source. Costs a PDF rewrite
   per rotated document, and has to keep native-text bboxes aligned with
   the render, which is exactly the invariant Step 2 built and
   `test_coordinates.py` guards.
2. **Bound the table pass** by rejecting a grid that is degenerate
   against the page's own cell count. Cleanest in principle; the grid
   size is not exposed until after the expensive call, so it is probably
   not reachable from outside Docling.
3. **`do_table_structure=False`** — one line, and it ends this today. But
   the option was enabled deliberately, and carried-forward item #1
   already records Docling missing a real line-items table. Turning it
   off makes that permanent.

Recommendation is (1), with (3) as the rollback. Not built pending a
decision.

---

## Step 8 — the scheduled reconciler

`OcrFieldsReconciler` in dms-core, replacing the poll-time re-apply in
`DocumentServiceImpl#getOcrStatus` per `phase1-closeout.md` section 5.

**V38 adds `document_versions.ocr_fields_applied_at`** plus a partial
index on `(ocr_status, ocr_fields_applied_at)` for the scan — there was
no index on `ocr_status` at all, and no OCR-completion timestamp on the
version.

The marker is explicit rather than inferred, deliberately. Inferring
"never applied" from `document_metadata_values` is tempting and wrong:
`applyOcrFields` legitimately writes nothing when every extracted field
is already overridden with `source='manual'`, or when every value failed
to parse for its data type. Both look permanently unapplied to a NOT
EXISTS check, so the reconciler would re-apply and re-warn about them on
every tick forever, and the warning would stop meaning anything.

**The warning is the product.** A healthy system produces none; each one
names a `document.version.ocr.completed` that genuinely went missing.
That is the difference from what it replaces, which only rescued a
document somebody happened to open and rescued it *silently* — which is
exactly how the `OutboxRelay` double-encoding defect went unnoticed for a
whole phase.

Design points worth keeping:

- **No lock.** The marker stamp is a guarded CAS
  (`UPDATE ... WHERE ocr_fields_applied_at IS NULL`), so if two instances
  — or the reconciler and the `.completed` listener — reach the same
  version at once, exactly one gets a row back and logs. Same approach as
  ocr-worker's reaper, which the codebase already establishes as the
  house pattern.
- **Claim before applying**, so a concurrent instance cannot also warn.
  `applyOcrFields` is idempotent, so a crash between claim and apply
  costs a re-run that converges, which is cheaper than two warnings.
- **Tenant bound per row from the row's own `tenant_id`**, cleared in a
  `finally`. The scheduler thread is long-lived and pooled and there is
  no row-level security in this system, so a leaked binding would apply
  one tenant's context to the next row.
- **`spring.task.scheduling.pool.size: 2`.** Spring's default scheduler
  is a single thread shared by every `@Scheduled` method. `OutboxRelay`
  ticks on it every 2s and is the mechanism every OCR job is triggered
  by; a slow reconciler tick would delay it.
- **`OcrProperties`** (`dms.ocr.*`) replaces the lone `@Value` boolean,
  mirroring `SchemaCacheProperties`.
- **`OcrFieldResultMapper`** — the row-to-map loop existed twice with a
  comment explaining the duplication was deliberate "for a ~10-line loop
  this stable". That held at two copies; the reconciler is the third.

### Still gated

The poll-time re-apply is **still enabled**. Removing it is gated, as
`phase1-closeout.md` section 5 specified, on a 50-upload concurrent run
against a document type with a real metadata schema with
`OCR_POLL_REAPPLY_ENABLED=false`, asserting every completed version has
its `document_metadata_values`. `scripts/dev/phase1_verification.py`
currently stages a schema-less throwaway type — which is exactly why open
item #3 could not be judged the first time — so it needs extending before
that run means anything. That is end-of-Phase-2 work.

---


---

## Scope decision — no LLM, ever (2026-09-29)

Production prompt section 3.8's tier 5, the optional
`EXTRACTION_LLM_PROVIDER=none|local|anthropic` structuring stage, is
**excluded from scope**. Not deferred, not behind a flag, not pending.

Nothing had to be removed: a search of the worker, dms-core, config and
compose found `EXTRACTION_LLM_PROVIDER` **only in the production prompt
itself**. It was never implemented, so there were no stubs to delete —
the change here is that it is now recorded as a decision rather than
sitting in "pending work, deliberately not built", where it was.

The reason is the property it would cost: **document content never leaves
this worker.** Every model runs locally on CPU. "Only for fields the
deterministic tiers left `not_found`" is not a containment argument — on a
badly scanned document that is every field.

### Outbound calls, verified three ways

| level | result |
|---|---|
| Source imports | no `requests`, `httpx`, `urllib`, `aiohttp`, `boto3` or `huggingface_hub` anywhere in `src/ocr_worker/` |
| Docling options | `enable_remote_services=False`, `do_picture_description=False`, `do_picture_classification=False`, `do_code_enrichment=False`, `do_formula_enrichment=False` — all defaults, none enabled. `do_ocr=False` set explicitly |
| Live container logs | exactly one external host: `huggingface.co` |

`github.com` also appears in the logs and is **not** a request — it is a
URL inside a PaddlePaddle "no ccache found" warning message. Worth
stating because grepping logs for `https://` is how this was checked, and
that hit looks alarming until read.

The one real call is HuggingFace **revision resolution** at converter
construction, which sends a model name and no document content, and
happens even when weights are cached. It is not required: a full Docling
conversion under `docker run --network none -e HF_HUB_OFFLINE=1`
succeeds from the mounted cache alone. `OCR_OFFLINE_MODELS=true` makes
that the running configuration.

Default false, deliberately: the model caches are named volumes populated
on first use, **not baked into the image**, so enabling it before the
first warm-up leaves a fresh deployment unable to fetch weights at all.

---

## Step 7 follow-up — the rotation defect, resolved

Carried-forward item 9. Two changes, because the experiment showed the
instance and the class need different answers.

### The experiment that decided it

| case | time | tables |
|---|---|---|
| A portrait, plain | 1.7s | 0 |
| B **landscape**, upright text, plain | 1.9s | 0 |
| C portrait, **real ruled table** | 5.9s | 1, grid **5x4** (correct) |
| D **landscape**, **real ruled table** | 1.7s | **0** |
| E portrait + `/Rotate 90`, generated | 1.7s | 0 |

Upright landscape is fast (B, D), so the pathology is **not** "landscape
pages are slow". But case E is fast too — so `/Rotate 90` is **not
sufficient** either. The 87s blow-up needs that page's particular content
*and* rotation. It is content-dependent and unpredictable, which is
precisely why a time bound is the real answer and normalising `/Rotate`
is only the known instance.

**Case D is a new finding, unrelated to timing:** a real ruled table on a
landscape page is **missed entirely** (0 tables), where the identical
table on a portrait page yields a correct 5x4 grid. Carried forward.


### Known limitation — a real table on a landscape page is not found

Separate from the timing pathology above, found by the same experiment,
and **not fixed by either change made for it**.

| page | table on it | Docling result |
|---|---|---|
| portrait | one real ruled 5x4 table | **1 table, grid 5x4** — correct |
| landscape | **the identical table** | **0 tables** |

Same generator, same cells, same rules, same font. The only difference is
the page's aspect ratio. On the landscape page the table is not detected
at all, so its cells never reach `tables_for_page` and the grid is simply
absent from the output.

**Why this matters more than it first looks.** Landscape is the normal
orientation for exactly the documents whose tables carry the value — a
financial statement, a line-items schedule, a timesheet, anything wide
enough that its author turned the page. And it is a *silent* loss: the
text of those cells still appears in `text` and `blocks` via the merge in
`_merge_native_text_and_tables`, so nothing looks broken. Only the
structure disappears. Full-text search still finds the words; anything
that wanted rows and columns gets nothing and cannot tell the difference
between "this page has no table" and "this page's table was not seen".

**This is a plausible common cause with carried-forward item 1**, where
Docling reported `tables=0` on a generated invoice that has a real
line-items table while the same page yielded 47 text lines. That was
recorded as an open question about Docling's table detection; a
shape-dependent blind spot in the layout model would explain both.

Not investigated further here, because it is an accuracy question in
Docling's layout model rather than anything this worker controls, and the
honest next step is a real labelled corpus rather than more generated
fixtures. Phase 3.
### /Rotate normalisation (`OCR_NORMALIZE_PAGE_ROTATION`, default on)

`layout.normalize_page_rotation` bakes a page's `/Rotate` into its
content before Docling sees the file, by drawing each page onto a new
page sized to its *displayed* box.

**`set_rotation(0)` is not this**, and was the first attempt. It clears
the attribute without touching the content stream, leaving the text
physically sideways and merely stopping the viewer compensating — the
exact opposite of the goal.

Verified on the real fixture:

| | before | after |
|---|---|---|
| words / chars | 24 / 196 | 24 / 196 |
| text | — | **byte-identical** |
| **max bbox delta** | — | **0.000 pt** |
| Docling conversion | 138.9s | **2.3s** |
| `texts` | 1 | 2 |

The coordinate invariant `test_coordinates.py` guards is preserved
because normalisation happens once, before anything reads the document.
`texts` going 1 → 2 is the hallucinated table no longer swallowing the
page.

### The table-structure bound (`OCR_TABLE_TIMEOUT_PER_PAGE_S`, default 20s)

The safety net for the class, since the experiment showed rotation is not
the only way in. Docling's `BaseTableStructureModel.__call__` is patched
to run `predict_tables` per page under a deadline; on overrun the page
keeps all its text, loses only table structure, and carries
`flags.table_structure_skipped`.

20s is set against measurement: the most expensive *legitimate* table
measured (case C's real 5x4) costs ~4s, so 20s leaves a 5x margin over
observed real work while cutting the pathological case by ~4x.

**Abandoned, not cancelled.** A thread running native torch cannot be
killed, so it keeps burning CPU until it finishes. What the bound buys is
that the *document* stops waiting. Process-level bounding remains the
pool worker's hard-kill ceiling.

**A bug the tests caught.** The first version shared one
`max_workers=1` executor across the page batch — so an abandoned
prediction still owned the only worker thread, the next page queued
behind the very work we had refused to wait for, and it timed out too.
One slow page silently cost every page after it. Each page now gets its
own executor (`test_one_slow_page_does_not_cost_the_others`).

---

## Step 5 — per-page image quality

Production prompt 3.3. `quality.py`, measured on every **rasterized**
page into `pages[].quality`, with `flags.poor_image_quality` and its
reasons when a bar is missed. Native-text pages are never rasterized and
carry no quality block — distinct from `{"measured": false}`, which means
rasterized but unmeasurable.

**A page is never rejected for quality.** 3.3 is explicit; nothing here
can fail a document, and no function raises.

Four decisions that are not obvious:

- **`effective_dpi` is computed from the source raster, not the render.**
  A 300 DPI scan whose render was capped by `OCR_RENDER_MAX_MP` would
  otherwise be reported as low resolution, telling a user to re-scan a
  page whose scan is already excellent.
- **Both axes are averaged.** A standard fax is 204x98 DPI; reporting
  only the width would call it fine.
- **Contrast is RMS (std-dev), not Michelson.** Peak-to-peak is decided
  by the single brightest and darkest pixel, so one speck of dust pins a
  uniformly grey page near 1.0 — exactly the washed-out scan the check
  exists for.
- **Skew is measured but never raises the flag.** It is an input to
  deskew (3.4 step 2), not a quality verdict: a sharp, well-lit,
  high-resolution page at 1.5 degrees is not one anyone should re-scan.
  Measured *after* orientation correction, so it is the residual angle,
  not 90 degrees for a sideways page. Hough is restricted to
  near-horizontal lines — without that, a table's vertical rules report a
  90-degree skew on a perfectly upright page.

A missing measurement never fires its own threshold: "we could not
measure the blur" is not evidence of blur.

### This is the second requires_review criterion

`docs/events/ocr-scanning-events.md` recorded `low_confidence` and
`requires_review` as always equal, because confidence was the only input.
`poor_image_quality` is an independent reason to route a document to
review: **a washed-out scan can be read confidently and wrongly.** Phase
3's rule is where the two stop being the same boolean, and the flags it
needs are now emitted.

### Thresholds are provisional

| setting | default | tuned against | what could be wrong |
|---|---|---|---|
| `OCR_QUALITY_MIN_DPI` | 150.0 | the corpus's `low_dpi_scan_72` condition, which loses 6pt `cost_centre` 3/3 while scoring 92.2% on large text | defensible from first principles; the least provisional of the three |
| `OCR_QUALITY_MIN_BLUR` | 100.0 | synthetic Gaussian blur only | **never measured against a real blurred photograph.** Variance-of-Laplacian is resolution- and content-dependent, so this is a starting value, not a calibration |
| `OCR_QUALITY_MIN_CONTRAST` | 35.0 | the corpus's `shadowed_photo` (~45) | fires only below the worst condition that currently reads correctly; untested against a real shadowed phone photo |

---

## Step 6 — normalized coordinates: already complete

Checked rather than assumed, because Step 2 predates the hybrid and zone
work. Every path that emits a bbox also emits `bbox_norm`: full-document
native and OCR blocks, hybrid blocks, zone blocks, rubber-band blocks,
tables, table cells, and extracted-field bboxes. Nothing outstanding, so
nothing was invented here.

The remaining 3.4 preprocessing steps — deskew, perspective correction,
denoising, CLAHE — are **not built**. `skew_angle` is now measured, which
is the input deskew needs, but applying a rotation is a change to what
OCR sees and has to be justified by a before/after eval per section 5.
## Test results

| suite | before Phase 2 | now |
|---|---|---|
| ocr-worker `pytest` | 183 passed, 2 deselected | **588 passed**, 2 deselected |
| dms-core `mvn test` | 41 passed | **59 passed** |
| dms-core `*IT` | 2 passed | **2 + `OcrFieldsReconcilerIT` (4) passed** |
| frontend `vitest` | 20 passed | 20 passed (untouched) |

`pytest` re-run after Step 5 and the rotation work (524 passed, 18.2s).
New: `test_quality.py` (23) and `test_rotation_and_table_bound.py` (17),
plus three rewritten timeout tests that pin the new in-worker retry
contract.

The dms-core rows are as recorded when this document was written and were not
re-run that day — nothing in dms-core changed after them.

New ocr-worker tests: `test_eval_metrics.py` (25 — the harness's own
scoring, hand-computed rather than golden-file, because a bug in it would
silently mis-grade every later step), `test_coordinates.py` (14),
`test_eval_sets.py`, `test_orientation.py` (39 — Step 3's geometry, its
confidence gate, and how paddlex's output is read), and four EXIF cases
added to `test_layout.py`.

New dms-core tests: `OcrFieldsReconcilerTest` (11),
`OcrFieldResultMapperTest` (7), `OcrFieldsReconcilerIT` (4, live against
real Postgres — the native scan query and the CAS are exactly the things
mocks cannot check).

---

## Carried forward

1. **Docling found no table** on a generated invoice that has a real
   line-items table (`tables=0`, while the same page yields 47 text
   lines). Related to Phase 1 open item #5. Step 7 / Phase 3.
2. **Docling merges visual rows into paragraphs** — 5 blocks where
   PyMuPDF sees 47 lines — which is why the spatial matcher reads
   PyMuPDF's line dict instead. Unchanged, noted.
3. **Two stray empty directories**, `backend/workers/ocr-worker;C` and
   `;W`, from a shell-quoting accident. Untracked, left alone.
4. ~~**`.env.example` is stale**~~ **Fixed.** It set `WORKER_POOL_SIZE`,
   which is not a `Settings` field at all and was silently swallowed by
   `extra="ignore"` — it never did anything. It also pinned
   `PREFETCH_COUNT=4` against a pool of 2, the exact configuration Phase 1
   removed. Both are gone, with the real knob (`OCR_WORKER_PROCESSES`) and
   its memory cost documented in their place.
5. **Orientation correction is not applied to zone or rubber-band OCR**
   (Step 3). Rubber-band deliberately — the user's box is drawn on the
   page as displayed, so reorienting underneath it changes what their own
   selection means, and the real fix is Phase 4's review UI. Zone
   templates arguably should get it, but nothing configures zones yet and
   the eval does not exercise that path.
6. **`rotated_*` conditions sit 2.6 points below their upright twins**,
   entirely due to one `q`→`g` recognition error in one word of one
   source description. Not a geometry or pipeline issue; revisit after
   Step 4's resolution work.
7. **The confidence floor is uncalibrated and probably uncalibratable.**
   `PP-LCNet_x1_0_doc_ori`'s score spanned 0.9205–0.9284 across all 81
   classified pages — a 0.008 spread covering every condition and both
   correct and would-be-incorrect answers. `OCR_ORIENTATION_MIN_CONFIDENCE`
   is effectively an on/off switch. If a real gate is wanted, it will
   have to come from somewhere other than this model's score — e.g.
   re-OCR'ing at the runner-up angle and comparing mean recognition
   confidence, which costs a second pass.
8. **The real set scores nothing yet** — 24 documents supplied, 24 draft
   labels, 0 reviewed — so nothing in this document is evidence of
   real-world accuracy. See the banner at the top and item 10.
9. ~~**A landscape-presenting PDF page costs ~87s in Docling's
   table-structure pass, for nothing.** `/Rotate 90` or `270` (not `180`
   — the trigger is the aspect swap) makes the layout model find a table
   that is not there; TableFormer then predicts a 378×2 grid on a page
   with 24 cells, all of which are dropped. Measured 1.6s → 88.5s on the
   same bytes, with `do_table_structure` the only difference. Real-world:
   `/Rotate` is what scanners emit. Three options and a recommendation
   are in Step 7, "A timeout the render fix did not fix".~~ **Fixed** — see
   "Step 7 follow-up". Both a per-page table-structure bound and `/Rotate`
   normalisation shipped; the rotated fixture went 138.9s → 2.3s.
   Confirmed in the close-out run (3c313b34): both copies of
   `rotated_native_text.pdf` completed, and the run carried 0 TIMEOUT.
10. **The real corpus is drafted but unreviewed.** 24 documents in
    `eval/corpus/real/` now have draft labels in `eval/labels/real/`
    (2026-09-29). Every one is `"reviewed": false`, so `eval.run` still
    reports the real set as pending and scores nothing — a draft is the
    pipeline's own output and scoring against it would measure the
    pipeline against itself. It needs a human pass before any real
    number exists, including the Step 4 resolution re-test.
11. **A real ruled table on a landscape page is missed entirely.** Found
    by the Step 7 follow-up experiment: the identical table yields a
    correct 5x4 grid portrait and **0 tables** landscape. Nothing to do
    with the timing pathology and not fixed by either change made for it
    — a separate accuracy gap in Docling's layout model, and the reason
    carried-forward item 1 (a real invoice's line-items table found as
    `tables=0`) may have the same cause. Phase 3.
12. **Deskew, perspective correction, denoising and CLAHE are not
    built** (production prompt 3.4 steps 2-5). `skew_angle` is now
    measured, which is the input deskew needs, but applying a rotation
    changes what OCR sees and has to be justified by a before/after eval
    per section 5.

---

# Phase 2 summary — closed 2026-09-29

## The close-out run

`scripts/dev/phase1_verification.py`, 50 uploads with a final 10-upload
simultaneous burst, through the real outbox path (dms-core's own
`OutboxRelay` publishes, so both the trigger and the outcome direction are
exercised).

```
RUN 3c313b34: 50 uploads, 1870.5s wall clock
ocr_status:  {'completed': 46, 'failed': 4}
stuck (non-terminal): 0
failure codes: {'ENCRYPTED_PDF': 2, 'CORRUPT_FILE': 2}
```

| criterion | result |
|---|---|
| zero crashes | **0** `WORKER_CRASH`; `OOMKilled=false`, `RestartCount=0` |
| zero stuck | **0** non-terminal |
| clear terminal status for every file | **50/50** |
| the 4 deliberate failures, correct codes | **2x `ENCRYPTED_PDF`, 2x `CORRUPT_FILE`**, one attempt each |
| `TIMEOUT` | **0** |

46 completed rather than 45: `large_35page_scanned.pdf`, the one fixture
that had never finished inside its budget, completed.

### Memory

| scope | figure |
|---|---|
| ocr-worker peak, over the run | **5.14 GiB** against its 8.5 GiB limit (60%) |
| ocr-worker steady state | 4.23 GiB |
| **whole stack, steady state** | **~4.94 GiB** across 8 containers |
| **whole stack, provision for** | **~9.2 GiB** — ocr-worker's 8.5 GiB limit plus ~0.7 GiB for everything else |

Everything other than ocr-worker is rounding error: dms-core 250 MiB,
Keycloak 208 MiB, MinIO 135 MiB, RabbitMQ 49 MiB, Postgres 54 MiB, Redis
5 MiB, Mailpit 14 MiB. **ocr-worker is the only container with a memory
limit set**, and it is 96% of the stack's budget — the other seven run
unbounded against the host, which is fine for dev and is something a
production deployment should set explicitly rather than inherit.

One inconsistency worth stating rather than smoothing: the sampler
reports `peak 5.136 GiB` (from the kernel's `memory.peak`) alongside
`p95 5.521 GiB` (from sampled `memory.current`). p95 should not exceed a
high-water mark. `peak_was_reset_first` is false because `/sys/fs/cgroup`
is read-only in these containers, so the two are not measured over
identical windows. Treat ~5.5 GiB as the honest upper bound for this run;
either way it sits well inside the limit, where earlier runs sat at 6.8.

## What Phase 2 delivered

| step | outcome |
|---|---|
| 0 | containers rebuilt to match source — now verified by md5 before every measured run |
| 1 | eval harness + recorded baseline |
| 2 | one coordinate space; `bbox_norm` on every path |
| 3 | orientation correction — rotated conditions 11.7-14.7% -> 92.7% |
| 4 | **vetoed**: 300 DPI + tiling bought 0 measured accuracy for >=1.6x cost |
| 5 | per-page quality assessment (DPI, blur, contrast, skew) + `poor_image_quality` |
| 6 | coordinates complete; `/Rotate` normalisation added. Deskew, perspective, denoise, CLAHE **not built** |
| 7 | per-page routing — `garbage_text_layer` 0.0% -> 93.5%, `hybrid_page` 0.0% -> 95.3%, overall 79.4% -> **93.9%** |
| 8 | scheduled reconciler replacing the poll-time re-apply |

## Eval numbers

**Synthetic only, and that is the headline caveat.** 117 generated
documents: **93.9% field accuracy**, `extracted`-status precision 96.0%,
CER 0.0549, 0 failures.

**The real set still scores nothing.** 24 real documents are in
`eval/corpus/real/` with 24 draft labels — but every draft is
`"reviewed": false`, and an unreviewed document is never scored, because
a draft is the pipeline's own output and scoring against it would measure
the pipeline against itself. Until those labels are reviewed, no number
here is evidence about real documents.

## Tests

| suite | before Phase 2 | now |
|---|---|---|
| ocr-worker `pytest` | 183 | **588** |
| dms-core `mvn test` | 41 | 59 |
| dms-core `*IT` | 2 | 2 + `OcrFieldsReconcilerIT` (4) |
| frontend `vitest` | 20 | 20 (untouched) |

## Deviations from the prompt

1. **Step 4 vetoed.** 300 DPI rendering with tiling is specified in
   section 3.5 and was not built. Measured: zero accuracy gain, >=1.6x
   slowdown, OOM above ~6 MP/inference. Shipped instead: an area-based
   `OCR_RENDER_MAX_MP` and `downsampled_from`, so scanner pixels are
   never discarded silently. **Re-test first when the real corpus exists.**
2. **Routing plausibility is a shape heuristic, not a dictionary.**
   Section 3.6 asks for dictionary-like token ratios; the check counts
   word-shaped tokens instead.
3. **Preprocessing 3.4 steps 2-5 not built** — deskew, perspective
   correction, denoising, CLAHE. Skew is now measured, which is deskew's
   input; applying a rotation changes what OCR sees and needs a
   before/after eval to justify.
4. **No LLM tier, permanently.** Section 3.8's optional
   `EXTRACTION_LLM_PROVIDER` is excluded by decision, not deferred:
   document content never leaves this worker.
5. **Per-tenant locale not wired.** `OCR_LOCALE` is process-wide; 3.8
   wants it per tenant, which needs dms-core to send it.

## Provisional — awaiting the real corpus

Every one of these was set against generated fixtures and has never been
seen against a real document:

| setting | default | risk |
|---|---|---|
| `OCR_RENDER_MAX_MP` | 1.81 | re-test first; a real 300 DPI scan and a real phone photo are the test |
| `OCR_ROUTING_MAX_GARBAGE_RATIO` | 0.30 | nothing in the corpus lands near it |
| `OCR_ROUTING_MIN_PLAUSIBILITY` | 0.50 | same, plus deviation 2 |
| `OCR_ROUTING_MIN_UNCOVERED_IMAGE` | 0.15 | a photographed signature panel could sit near it |
| `OCR_ORIENTATION_MIN_CONFIDENCE` | 0.6 | the model's score spans 0.008; effectively on/off |
| `OCR_QUALITY_MIN_BLUR` | 100.0 | never measured against a real blurred photograph |
| `OCR_QUALITY_MIN_CONTRAST` | 35.0 | never measured against a real shadowed photo |
| Phase 3 tier weights + `extracted` threshold | see `field_confidence.py` | explicitly uncalibrated by design |

## Carried into Phase 3+

- A real ruled table on a **landscape** page is missed entirely (0 tables
  vs a correct 5x4 portrait) — plausibly the same cause as a real
  invoice's line-items table reading `tables=0`.
- `processing_job_attempts` history table — the "OOM-killed twice" rule
  is inferred from `attempts`, not a real per-cause count.
- The confidence floor for orientation is uncalibratable from that
  model's own score.
