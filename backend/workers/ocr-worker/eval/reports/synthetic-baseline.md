# OCR eval report -- `baseline`

Generated 2026-10-01T10:31:46.033136+00:00 - 2961.7s wall clock.

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
| field accuracy (OS-NFR-001) | **99.5%** |
| `extracted`-status precision | 94.5% |
| CER (mean) | 0.0549 (n=117) |
| CER (p95) | 0.1544 (n=117) |
| WER (mean) | 0.0609 (n=117) |
| documents with reference text | 117 / 117 |
| latency p50 / p95 per document | 22.145s / 47.857s |
| latency p50 / p95 per page | 22.145s / 47.857s |
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
| `clean_scan_300` | 9 | 0 | 100.0% | 0.0331 | 0.0325 | 29.16 | 51.22 |
| `fax_g4_tiff` | 9 | 0 | 98.2% | 0.0447 | 0.0565 | 20.52 | 41.46 |
| `garbage_text_layer` | 9 | 0 | 100.0% | 0.0324 | 0.0277 | 25.66 | 61.55 |
| `hybrid_page` | 9 | 0 | 100.0% | 0.1023 | 0.1759 | 22.2 | 48.68 |
| `low_dpi_scan_72` | 9 | 0 | 100.0% | 0.0367 | 0.0509 | 23.63 | 43.47 |
| `multipage` | 9 | 0 | 100.0% | 0.0250 | 0.0196 | 4.22 | 8.49 |
| `native_pdf` | 9 | 0 | 100.0% | 0.0322 | 0.0260 | 1.42 | 2.71 |
| `phone_photo_perspective` | 9 | 0 | 94.1% | 0.1477 | 0.1530 | 19.71 | 47.69 |
| `rotated_180` | 9 | 0 | 100.0% | 0.0324 | 0.0277 | 23.26 | 43.81 |
| `rotated_270` | 9 | 0 | 100.0% | 0.0324 | 0.0277 | 23.46 | 47.86 |
| `rotated_90` | 9 | 0 | 100.0% | 0.0324 | 0.0277 | 23.28 | 39.4 |
| `shadowed_photo` | 9 | 0 | 100.0% | 0.0327 | 0.0290 | 22.15 | 48.84 |
| `skewed_2deg` | 9 | 0 | 100.0% | 0.1291 | 0.1372 | 22.54 | 47.71 |

| field | expected | produced | correct | precision | recall | extracted-prec |
|---|---|---|---|---|---|---|
| `amount` | 39 | 39 | 38 | 97.4% | 97.4% | 97.4% |
| `author` | 39 | 39 | 39 | 100.0% | 100.0% | 100.0% |
| `contract_end_date` | 39 | 39 | 39 | 100.0% | 100.0% | 100.0% |
| `contract_party` | 39 | 39 | 39 | 100.0% | 100.0% | 100.0% |
| `contract_start_date` | 39 | 39 | 39 | 100.0% | 100.0% | 100.0% |
| `contract_value` | 39 | 39 | 39 | 100.0% | 100.0% | 100.0% |
| `currency` | 39 | 39 | 39 | 100.0% | 100.0% | 100.0% |
| `effective_date` | 78 | 78 | 78 | 100.0% | 100.0% | 100.0% |
| `expiry_date` | 78 | 78 | 78 | 100.0% | 100.0% | 100.0% |
| `invoice_date` | 39 | 39 | 39 | 100.0% | 100.0% | 100.0% |
| `invoice_number` | 39 | 39 | 37 | 94.9% | 94.9% | 94.9% |
| `language` | 0 | 39 | 0 | 0.0% | 0.0% | 0.0% |
| `purchase_order_number` | 39 | 39 | 39 | 100.0% | 100.0% | 100.0% |
| `reference_number` | 78 | 78 | 78 | 100.0% | 100.0% | 100.0% |
| `review_due_date` | 78 | 78 | 77 | 98.7% | 98.7% | 98.7% |
| `vendor_supplier_name` | 39 | 39 | 39 | 100.0% | 100.0% | 100.0% |

## Searchable PDF rendition -- synthetic

- **117 of 117** completed documents produced one (100.0%)

## Memory (whole run, both sets)

- peak: **3.761 GiB** (limit None GiB, source `cgroup.memory.peak`)
- median 2.238 GiB, p95 2.449 GiB, over 588 samples at 2.0s
- single-process measurement; production runs OCR_WORKER_PROCESSES=2, each with its own model set
