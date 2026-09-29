# OCR eval report -- `baseline`

Generated 2026-09-29T10:57:27.804543+00:00 - 2620.5s wall clock.

Settings: `{"ocr_render_dpi": 200, "ocr_languages": "en", "min_confidence_threshold": 0.95, "field_low_confidence_threshold": 0.6, "ocr_max_pages": 50, "ocr_timeout_base_s": 60.0, "ocr_timeout_per_page_s": 45.0, "ocr_orientation_enabled": true, "ocr_orientation_min_confidence": 0.6, "ocr_render_max_mp": 1.81, "ocr_routing_enabled": true, "ocr_routing_max_garbage_ratio": 0.3, "ocr_routing_min_plausibility": 0.5, "ocr_routing_min_uncovered_image": 0.15}`

> **Two sets, never combined.** The synthetic set is generated and
> labelled by the same process being measured -- it is a regression
> set that can veto a change, not evidence that one helps. The real
> set is the one a keep-or-drop decision rests on.

## Real set

_Not run in this pass (`--set` excluded it)._

## Synthetic set

_SYNTHETIC -- regression only, not real-world accuracy_

| metric | value |
|---|---|
| documents | 117 |
| field accuracy (OS-NFR-001) | **93.9%** |
| `extracted`-status precision | 96.0% |
| CER (mean) | 0.0549 |
| CER (p95) | 0.1544 |
| WER (mean) | 0.0609 |
| documents with reference text | 117 / 117 |
| latency p50 / p95 per document | 24.218s / 33.967s |
| latency p50 / p95 per page | 24.218s / 33.967s |
| statuses | {"completed": 117} |

### Preprocessing -- orientation

| metric | value |
|---|---|
| pages classified | 90 (45 native-text pages never rasterized, never classified) |
| upright pages wrongly rotated | **0 / 63 (0.00%)** |
| rotated pages corrected | 27 / 27 (100.00%) |
| applied angles | {"0": 63, "90": 9, "180": 9, "270": 9} |

| condition | docs | failed | field exact | CER | WER | p50 s | p95 s |
|---|---|---|---|---|---|---|---|
| `clean_scan_300` | 9 | 0 | 95.3% | 0.0331 | 0.0325 | 26.58 | 41.18 |
| `fax_g4_tiff` | 9 | 0 | 94.2% | 0.0447 | 0.0565 | 21.63 | 27.07 |
| `garbage_text_layer` | 9 | 0 | 93.5% | 0.0324 | 0.0277 | 25.53 | 33.97 |
| `hybrid_page` | 9 | 0 | 95.3% | 0.1023 | 0.1759 | 23.25 | 29.47 |
| `low_dpi_scan_72` | 9 | 0 | 92.2% | 0.0367 | 0.0509 | 24.84 | 36.33 |
| `multipage` | 9 | 0 | 95.3% | 0.0250 | 0.0196 | 4.06 | 5.5 |
| `native_pdf` | 9 | 0 | 95.3% | 0.0322 | 0.0260 | 1.69 | 2.18 |
| `phone_photo_perspective` | 9 | 0 | 89.4% | 0.1477 | 0.1530 | 20.51 | 30.16 |
| `rotated_180` | 9 | 0 | 93.5% | 0.0324 | 0.0277 | 27.01 | 32.76 |
| `rotated_270` | 9 | 0 | 93.5% | 0.0324 | 0.0277 | 25.15 | 29.55 |
| `rotated_90` | 9 | 0 | 93.5% | 0.0324 | 0.0277 | 27.0 | 34.39 |
| `shadowed_photo` | 9 | 0 | 95.3% | 0.0327 | 0.0290 | 25.44 | 31.94 |
| `skewed_2deg` | 9 | 0 | 95.3% | 0.1291 | 0.1372 | 26.02 | 35.03 |

| field | expected | produced | correct | precision | recall | extracted-prec |
|---|---|---|---|---|---|---|
| `amount` | 39 | 39 | 38 | 97.4% | 97.4% | 97.4% |
| `author` | 39 | 39 | 39 | 100.0% | 100.0% | 100.0% |
| `classification_level` | 117 | 117 | 117 | 100.0% | 100.0% | 100.0% |
| `contract_end_date` | 39 | 39 | 39 | 100.0% | 100.0% | 100.0% |
| `contract_party` | 39 | 39 | 39 | 100.0% | 100.0% | 100.0% |
| `contract_start_date` | 39 | 39 | 39 | 100.0% | 100.0% | 100.0% |
| `contract_value` | 39 | 39 | 39 | 100.0% | 100.0% | 100.0% |
| `cost_centre` | 39 | 36 | 36 | 100.0% | 92.3% | 100.0% |
| `currency` | 39 | 39 | 39 | 100.0% | 100.0% | 100.0% |
| `department` | 117 | 117 | 117 | 100.0% | 100.0% | 100.0% |
| `description` | 117 | 117 | 109 | 93.2% | 93.2% | 93.2% |
| `effective_date` | 78 | 78 | 78 | 100.0% | 100.0% | 100.0% |
| `expiry_date` | 78 | 78 | 78 | 100.0% | 100.0% | 100.0% |
| `invoice_date` | 39 | 39 | 39 | 100.0% | 100.0% | 100.0% |
| `invoice_number` | 39 | 39 | 37 | 94.9% | 94.9% | 94.9% |
| `keywords` | 39 | 39 | 39 | 100.0% | 100.0% | 100.0% |
| `language` | 0 | 39 | 0 | 0.0% | 0.0% | 0.0% |
| `purchase_order_number` | 39 | 39 | 39 | 100.0% | 100.0% | 100.0% |
| `reference_number` | 78 | 78 | 78 | 100.0% | 100.0% | 100.0% |
| `retention_class` | 78 | 13 | 13 | 100.0% | 16.7% | 100.0% |
| `review_due_date` | 78 | 78 | 77 | 98.7% | 98.7% | 98.7% |
| `tags` | 117 | 117 | 114 | 97.4% | 97.4% | 97.4% |
| `vendor_supplier_name` | 39 | 39 | 39 | 100.0% | 100.0% | 100.0% |

## Memory (whole run, both sets)

- peak: **4.908 GiB** (limit 6.0 GiB, source `cgroup.memory.peak`)
- median 3.387 GiB, p95 3.651 GiB, over 515 samples at 2.0s
- single-process measurement; production runs OCR_WORKER_PROCESSES=2, each with its own model set
