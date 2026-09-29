# OCR eval report -- `baseline`

Generated 2026-09-28T04:14:46.557063+00:00 - 2392.4s wall clock.

Settings: `{"ocr_render_dpi": 200, "ocr_languages": "en", "min_confidence_threshold": 0.95, "field_low_confidence_threshold": 0.6, "ocr_max_pages": 50, "ocr_timeout_base_s": 60.0, "ocr_timeout_per_page_s": 25.0, "ocr_orientation_enabled": true, "ocr_orientation_min_confidence": 0.6}`

> **Two sets, never combined.** The synthetic set is generated and
> labelled by the same process being measured -- it is a regression
> set that can veto a change, not evidence that one helps. The real
> set is the one a keep-or-drop decision rests on.

## Real set

**No reviewed real documents yet.** Put documents in
`eval/corpus/real/`, run `python -m eval.draft_labels`, correct each
label and set `"reviewed": true`.

Until this set has data, a synthetic-only result can **veto** a change
(a regression fails the gate) but cannot **justify** one.

## Synthetic set

_SYNTHETIC -- regression only, not real-world accuracy_

| metric | value |
|---|---|
| documents | 117 |
| field accuracy (OS-NFR-001) | **78.7%** |
| `extracted`-status precision | 94.5% |
| CER (mean) | 0.2658 |
| CER (p95) | 1.9798 |
| WER (mean) | 0.2498 |
| documents with reference text | 117 / 117 |
| latency p50 / p95 per document | 24.981s / 36.647s |
| latency p50 / p95 per page | 24.981s / 36.647s |
| statuses | {"completed": 117} |

| condition | docs | failed | field exact | CER | WER | p50 s | p95 s |
|---|---|---|---|---|---|---|---|
| `clean_scan_300` | 9 | 0 | 95.3% | 0.0322 | 0.0260 | 30.85 | 39.2 |
| `fax_g4_tiff` | 9 | 0 | 95.3% | 0.0382 | 0.0438 | 23.96 | 31.31 |
| `garbage_text_layer` | 9 | 0 | 0.0% | 1.9576 | 1.7100 | 2.09 | 4.47 |
| `hybrid_page` | 9 | 0 | 0.0% | 0.9143 | 0.9581 | 1.77 | 4.2 |
| `low_dpi_scan_72` | 9 | 0 | 92.2% | 0.0356 | 0.0444 | 27.74 | 36.06 |
| `multipage` | 9 | 0 | 95.3% | 0.0250 | 0.0196 | 4.4 | 5.2 |
| `native_pdf` | 9 | 0 | 95.3% | 0.0322 | 0.0260 | 1.43 | 2.52 |
| `phone_photo_perspective` | 9 | 0 | 83.6% | 0.1392 | 0.1462 | 23.91 | 38.12 |
| `rotated_180` | 9 | 0 | 92.7% | 0.0326 | 0.0286 | 26.69 | 41.0 |
| `rotated_270` | 9 | 0 | 92.7% | 0.0326 | 0.0286 | 27.57 | 41.31 |
| `rotated_90` | 9 | 0 | 92.7% | 0.0326 | 0.0286 | 26.72 | 33.22 |
| `shadowed_photo` | 9 | 0 | 95.3% | 0.0322 | 0.0260 | 26.03 | 35.72 |
| `skewed_2deg` | 9 | 0 | 93.2% | 0.1506 | 0.1613 | 31.86 | 36.65 |

| field | expected | produced | correct | precision | recall | extracted-prec |
|---|---|---|---|---|---|---|
| `amount` | 39 | 33 | 33 | 100.0% | 84.6% | 100.0% |
| `author` | 39 | 33 | 33 | 100.0% | 84.6% | 100.0% |
| `classification_level` | 117 | 99 | 99 | 100.0% | 84.6% | 100.0% |
| `contract_end_date` | 39 | 33 | 33 | 100.0% | 84.6% | 100.0% |
| `contract_party` | 39 | 33 | 33 | 100.0% | 84.6% | 100.0% |
| `contract_start_date` | 39 | 33 | 33 | 100.0% | 84.6% | 100.0% |
| `contract_value` | 39 | 33 | 33 | 100.0% | 84.6% | 100.0% |
| `cost_centre` | 39 | 31 | 31 | 100.0% | 79.5% | 100.0% |
| `currency` | 39 | 33 | 33 | 100.0% | 84.6% | 100.0% |
| `department` | 117 | 99 | 99 | 100.0% | 84.6% | 100.0% |
| `description` | 117 | 117 | 87 | 74.4% | 74.4% | 87.9% |
| `effective_date` | 78 | 66 | 66 | 100.0% | 84.6% | 100.0% |
| `expiry_date` | 78 | 66 | 66 | 100.0% | 84.6% | 100.0% |
| `invoice_date` | 39 | 33 | 33 | 100.0% | 84.6% | 100.0% |
| `invoice_number` | 39 | 33 | 31 | 93.9% | 79.5% | 93.9% |
| `keywords` | 39 | 36 | 33 | 91.7% | 84.6% | 100.0% |
| `language` | 0 | 39 | 0 | 0.0% | 0.0% | 0.0% |
| `purchase_order_number` | 39 | 33 | 32 | 97.0% | 82.0% | 97.0% |
| `reference_number` | 78 | 66 | 66 | 100.0% | 84.6% | 100.0% |
| `retention_class` | 78 | 11 | 11 | 100.0% | 14.1% | 100.0% |
| `review_due_date` | 78 | 66 | 65 | 98.5% | 83.3% | 98.5% |
| `tags` | 117 | 108 | 91 | 84.3% | 77.8% | 91.9% |
| `vendor_supplier_name` | 39 | 39 | 33 | 84.6% | 84.6% | 100.0% |

## Memory (whole run, both sets)

- peak: **3.521 GiB** (limit 8.5 GiB, source `cgroup.memory.peak`)
- median 2.162 GiB, p95 2.267 GiB, over 471 samples at 2.0s
- single-process measurement; production runs OCR_WORKER_PROCESSES=2, each with its own model set
