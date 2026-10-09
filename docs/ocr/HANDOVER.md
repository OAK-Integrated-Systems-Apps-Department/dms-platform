# OCR & Scanning — handover

Final state of the OCR module as of **2026-10-02**. Written for whoever
picks this up next, including the version of us who has forgotten it.

Two things to read first, because everything else depends on them:

- **[`backend/workers/ocr-worker/README.md`](../../backend/workers/ocr-worker/README.md)**
  — how the worker actually works, every configuration knob, the
  measurements behind each default, and what is deliberately out of
  scope.
- **[`docs/ocr/phase2-verification.md`](phase2-verification.md)** — the
  accuracy work's own record, including the changes that were tried and
  **rejected on measurement**. Read it before re-trying any of them.

---

## 1. What was built

Four delivery phases from `docs/ocr/ocr-production-prompt.md`, all
closed:

| phase | what | evidence |
|---|---|---|
| **1 — Stability** | Queue-based triggering via the outbox, process pool with bounded concurrency, short transactions (claim / run / commit), lease + reaper, magic-byte MIME sniffing, per-page and per-document timeouts, an error-code taxonomy | `phase1-verification.md`, `phase1-closeout.md` |
| **2 — Accuracy foundation** | Evaluation harness and corpus, normalized coordinates (`schema_version: 2`), whole-page orientation correction, per-page quality assessment, per-page routing (native / OCR / hybrid) | `phase2-verification.md` |
| **3 — Extraction** | Computed field confidence, Sri Lankan money and date patterns, invoice line items (V39), validation cross-checks | `phase2-verification.md` (Phase 3 sections) |
| **4 — Outputs and UX** | Searchable-PDF rendition, review queue (V41), review screen with field highlights, correction capture (V43), friendly error messages | `phase4-verification.md` |

Then a measured accuracy pass on real documents, one change at a time,
each with its own eval run: type-gated spatial matching, total-label
vocabulary, decimal normalisation, currency detection, `ocr_extractable`
(V44), a tenant default currency, vendor letterhead scoring, a
reviewer-confirmed known-vendor list, prominent document numbers, and
confidence calibration.

**Reports** for every one of those runs are in
`backend/workers/ocr-worker/eval/reports/`. The aggregate `.json`/`.md`
are committed and redact values; the `-real-detail.json` files are
gitignored because they carry per-document customer data.

### Deliberately NOT built

Each of these is a decision with a reason, not a backlog item. Re-read
the reason before reopening one.

- **No LLM tier.** Document content never leaves this worker. README,
  "No LLM, by design".
- **No handwriting detection.** The proposed detector was measured and
  does not separate the populations. README, "Handwriting detection —
  deferred, and why".
- **No 300 DPI rendering with tiling.** Measured: zero accuracy gain,
  ≥1.6× slower, OOM above ~6 MP per inference. `phase2-verification.md`
  Step 4. **Re-test this first** if the render budget is ever revisited.
- **No `description` / `tags` suggestions.** They were never extraction.
  README, "Suggested `description` / `tags`".
- **Preprocessing steps 2–5** (deskew, perspective, denoise, CLAHE) are
  not built. Skew is measured but not corrected.
- **Anchor-relative zones** (§3.8 tier 2) are not built.

---

## 2. Final numbers

### Real set — `eval/reports/release-loo.{json,md}`

The decision set. 21 human-labelled documents, leave-one-out vendor
seeding (what production does).

| metric | value |
|---|---|
| **printed field accuracy** | **95.1%** (77 / 81 fields, 19 docs) |
| **`extracted`-status precision, printed** | **98.7%** |
| handwritten field accuracy | 62.5% (5 / 8 fields, 2 docs) |
| combined | 92.1% (82 / 89) |
| review-queue rate | 16 / 21 |
| review-flag recall / precision | **100%** / 37.5%, 0 missed |
| searchable PDF | 21 / 21 |
| latency p50 / p95 per document | 38.1 s / 47.0 s |
| peak memory (1 process) | 3.154 GiB |

Per field: `amount` 100% precision / 90.5% recall · `currency` 100 / 100
· `invoice_number` 100 / 92.9 · `vendor_supplier_name` 95.2 / 95.2 ·
`invoice_date` 94.7 / 85.7 · `purchase_order_number` 100 / 100.

**OS-NFR-001 (≥95% on standard layouts) is met on printed documents.**

### Synthetic set — `eval/reports/synthetic-baseline.{json,md}`

The regression set and the CI gate's reference. 117 generated documents.

| metric | value |
|---|---|
| field accuracy | 99.5% |
| `extracted`-status precision | 94.5% |
| CER (mean) | 0.0549 |
| searchable PDF | 117 / 117 |
| wall clock | 2962 s |
| peak memory | 3.761 GiB |

### ⚠ The 21-document caveat — read this before quoting 95.1%

**21 documents is a small sample and the figure is fragile.**

- One field is worth **1.2 points** of the printed number. Two documents
  going the other way would put it under the bar.
- There is **no confidence interval** on 81 field observations. 95.1% and
  92% are not distinguishable at this size.
- The corpus is **narrow**: 19 printed documents from roughly **8
  vendors**, almost all Sri Lankan medical invoices and receipts. Several
  share a template — four are the same Santa Dora layout, three the same
  Medihelp receipt. A change that helps one template moves the number
  several points for reasons that will not generalise.
- `vendor_supplier_name`'s 95.2% depends on **leave-one-out seeding**,
  which assumes a tenant has already confirmed that vendor. First
  sightings are measured at the unseeded figure: **61.9%**. Both numbers
  are real; which one applies depends on how long the tenant has been
  running.
- The two handwritten documents are **not a sample of anything**. 62.5%
  over 8 fields is an observation, not a rate.

**What would make this solid:** 100+ real documents across more vendors
and more templates, and at least 30 per seeded type. Treat 95.1% as
"the bar was cleared on the evidence available", not as the system's
accuracy.

---

## 3. Known limitations

### Handwriting

Not detected. PaddleOCR has no handwritten-English model and does not
misread handwriting — it does not see it, returning confident reads of
the printed parts while the pen strokes produce nothing. Page-mean
confidence therefore *inverts* between the populations, and the ink-based
detector that was tried does not separate them either (README has the
measurements).

Today a handwritten entry comes back `not_found`, which sends the
document to the review queue — the right outcome, reached by accident
rather than by detection. The eval knows which documents are handwritten
only because a human wrote `"handwritten": true` in the label.

### CONTRACT and POLICY are untested on real documents

**The real corpus is 21 INVOICEs and nothing else.** Every CONTRACT and
POLICY number anywhere in this project comes from the synthetic set,
which is generated and labelled by the same process it measures.

So `contract_party`, `contract_value`, `contract_start_date`,
`contract_end_date`, `retention_class` and the POLICY date fields have
**never been scored against a real document**. The extraction tiers they
use are the same ones INVOICE uses, and the label vocabulary was tuned
against invoices — several of this session's improvements (total-label
tiers, currency detection, prominent document numbers) are
invoice-shaped by construction and may do nothing for a contract.

Treat contract and policy extraction as **unmeasured**, not as working.
Getting real documents of those types into `eval/corpus/real/` is the
single highest-value next step.

### The review queue is high early on

**16 of 21 documents are flagged**, with 100% recall and 37.5%
precision. That is deliberate — a missed flag files a wrong value as
correct, a false flag costs a minute of someone's time — but it means
**a new tenant reviews nearly everything at first.**

It improves on its own, and this is the mechanism: the biggest remaining
queue driver is `invoice_number` (8 documents), followed by
`vendor_supplier_name` (4). Every vendor a reviewer confirms enters the
known-vendor list, so the *next* document from that vendor is matched at
a high tier and does not flag. The queue shrinks as the tenant's
confirmed-vendor list grows.

Set expectations accordingly at go-live: the first week is review-heavy
by design. If it is still review-heavy after a few hundred documents,
that is a signal worth investigating, not the intended steady state.

### Smaller things

- `invoice_date` 94.7% / 85.7%. One real failure reads a **handwritten
  margin annotation** in preference to the printed date.
- A **first sighting of a new vendor** whose letterhead OCRs imperfectly
  is wrong once, then corrected, then right. By design.
- `OCR_DEFAULT_CURRENCY` and `OCR_LOCALE` are **process-wide, not
  per-tenant**. §3.8 wants per-tenant; that needs dms-core to send it on
  `document.version.ocr.requested`.
- The **confidence thresholds and tier weights are calibrated on 21
  documents**. Re-calibrate when the corpus grows.
- `LOW_CONTRAST` quality detection is **disabled** (`OCR_QUALITY_MIN_CONTRAST=0`):
  RMS contrast is inverted for shadowed photos. The replacement (Otsu
  foreground/background separation) is specified in
  `phase2-verification.md` and not built.

---

## 4. Production checklist

### Server sizing

The worker is the only memory-hungry service; everything else is
ordinary. **Measured, one inference process: 3.76 GiB peak.** Scaling
rule: **~3.4 GiB per pool process plus a ~1.5 GiB shared floor.**

| `OCR_WORKER_PROCESSES` | worker needs | rest of the stack | **server RAM** |
|---|---|---|---|
| 1 | ~5 GiB | ~4 GiB | **12 GB minimum** |
| 2 (default) | ~8.5 GiB | ~4 GiB | **16 GB recommended** |
| 4 | ~15 GiB | ~4 GiB | **24 GB** |

"Rest of the stack" is Postgres, RabbitMQ, Redis, SeaweedFS, Keycloak,
dms-core and the frontend. Do not run the worker on a box sized only for
its average: inference memory is spiky, and the failure mode is the
kernel picking a victim.

- [ ] **Server RAM matches the table above** for the configured
      `OCR_WORKER_PROCESSES`.
- [ ] **`mem_limit` on every container, not just ocr-worker.**
      `infra/local/docker-compose.yml` sets `mem_limit: 8704m` on
      ocr-worker (the measured 6.89 GiB peak plus ~25% headroom) and
      **nothing on the other nine services**. An unbounded Postgres or
      JVM is the same failure in a different place: without a limit the
      host runs out and the kernel picks a victim, which may not be the
      service that caused it. Set one per service before go-live.
- [ ] **Raising `OCR_WORKER_PROCESSES` means raising `mem_limit`**, by
      ~3.4 GiB per process. They are not independent knobs.
- [ ] **`OCR_FINGERPRINT_ENABLED=false`.** Default is **on** because
      development is where it earns its keep, and it enumerates this
      service's internal module names. Nothing in production asks for it.
- [ ] **`OCR_DEFAULT_CURRENCY`** set to the tenant's currency (`LKR` for
      this deployment). Applied only when a page names no currency at
      all; arrives as `status="defaulted"`, never counted as OCR
      accuracy, never a review reason. **Set it empty for a genuinely
      multi-currency tenant** — `not_found` is the honest answer there.
- [ ] **V44 applied.** Without it `metadata_fields.is_ocr_extractable`
      is false for every field (V28's default) and OCR extracts nothing.
      Verify against `flyway_schema_history`, not against the file
      existing — a migration shipping inside the dms-core JAR has
      silently failed to apply before.
- [ ] **ocr-worker stays internal-only** — no published port,
      `X-Internal-Token` required. It answers for any version id
      presented with that token and has no notion of who is asking; the
      tenant check lives in dms-core.
- [ ] **Back up `eval/corpus/real/` and `eval/labels/real/`.** They are
      **gitignored** — they are customer documents and their ground
      truth — so they exist on developer machines and nowhere else.
      Losing them loses every accuracy number this project has and the
      only way to produce another. Back them up wherever customer data is
      permitted to live, with the same handling as the documents
      themselves, and make sure more than one person has a copy.
- [ ] **Model weights are baked into the image** — ~653 MB of PaddleOCR
      detection/recognition, the orientation and textline classifiers,
      and Docling's layout and table-structure models. **No egress is
      needed at runtime.** Verified by running the built image on an
      `--internal` Docker network with no model volumes: `/readyz` went
      503 → 200 in 5 s and a full 21-document real-set run produced
      results identical to the networked baseline, field for field.
      - **On upgrade, delete the old `ocr_worker_models` and
        `ocr_worker_hf_cache` volumes.** Compose still mounts them, and a
        pre-existing volume **shadows** the baked weights with whatever it
        already holds. Docker only seeds a volume from the image when the
        volume is *fresh*. Identical content is harmless; a stale or
        partial volume reintroduces the download this change removed.
      - Changing `OCR_LANGUAGES` to a language that was not baked means
        that language downloads on first use. Rebuild with
        `--build-arg OCR_PREFETCH_LANGS="en,ta"` to bake more.
- [ ] **`/readyz` returns 503 until models are loaded** — re-verified
      after the weights were baked in (503 `"ocr process pool still
      warming up"` → 200 after ~11 s, air-gapped). Make sure the
      orchestrator's readiness probe uses it, or the first document after
      a deploy pays model construction inside its own deadline.

### Operational

- [ ] Alert on `processing_jobs` rows in `dead_letter`.
- [ ] Alert on the review-queue depth trending **up** over weeks — see
      "The review queue is high early on" for why it should trend down.
- [ ] Watch for OOM kills on ocr-worker (`RestartCount`, `OOMKilled`).
      `restart: unless-stopped` is set so one is survivable; a pattern of
      them means `mem_limit` or `OCR_WORKER_PROCESSES` is wrong.

---

## 5. Release procedure

Two gates. The full instructions, with the exact commands, are in the
**"The accuracy gate"** section of
[`backend/workers/ocr-worker/README.md`](../../backend/workers/ocr-worker/README.md).
In outline:

### Automatic — the synthetic veto

`.github/workflows/ocr-accuracy-gate.yml` runs on **pull requests into
`main`**, **nightly at 02:30 UTC**, and on manual dispatch. It fails the
build when synthetic field accuracy drops below
`eval/reports/synthetic-baseline.json`, or when **any** document fails to
produce a searchable PDF.

It deliberately does **not** run on every push: 117 documents of CPU
inference is 49 minutes measured, 65–90 on a hosted runner. The workflow
header carries the full runner budget.

The synthetic set can only **veto**. It is generated and labelled by the
same process it measures, so an improvement on it justifies nothing.

**Updating the baseline is a deliberate act.** Re-run
`--set synthetic --report-name synthetic-baseline`, commit it, and say in
the commit message what moved and why. A baseline quietly refreshed to
make a gate pass is the gate not existing.

### Manual, before a release — the real-set gate

Run by someone with the real corpus on their machine:

1. `python -m eval.check_labels` — the ruler first. Exits 1 on anything
   that would score the pipeline against a malformed label.
2. `python -m eval.run --set real --report-name release-candidate` —
   leave-one-out vendor seeding is the default and is what production
   does.
3. `python -m eval.compare eval/reports/release-previous.json eval/reports/release-candidate.json`

**Read the printed/handwritten split, not the headline.** OS-NFR-001's
≥95% is for standard layouts. The release questions are the **printed**
field accuracy and the **`extracted`-status precision** beside it —
"when we said we were sure, were we right" is what decides whether a
value can be filed without a human looking at it.

Do not commit `release-candidate-real-detail.json`. It is per-document
output from customer documents and is gitignored for that reason.

---

## 6. If you change one thing, change this

In rough order of value:

1. **Get real CONTRACT and POLICY documents into the corpus.** Two
   document types are shipping unmeasured.
2. **Grow the real invoice corpus past 100 documents, across more
   vendors.** Everything else in this list is hard to judge until the
   ruler is wide enough to see it.
3. **Re-calibrate** confidence thresholds and tier weights once it is.
   The current ones were set on 21 documents and are honest about that.
4. **`invoice_number` recall (92.9%)** is the largest remaining queue
   driver.
5. **Per-tenant locale and currency**, which needs dms-core to send them
   on the request event.
6. **Handwriting detection**, properly specified — see the README for
   what was tried and why it failed.
