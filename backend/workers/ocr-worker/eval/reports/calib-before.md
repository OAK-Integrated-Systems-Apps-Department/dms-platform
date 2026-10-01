# OCR eval report -- `baseline`

Generated 2026-09-30T15:08:37.603477+00:00 - 466.2s wall clock.

Settings: `{"ocr_render_dpi": 200, "ocr_languages": "en", "min_confidence_threshold": 0.95, "field_low_confidence_threshold": 0.6, "ocr_max_pages": 50, "ocr_timeout_base_s": 60.0, "ocr_timeout_per_page_s": 45.0, "ocr_orientation_enabled": true, "ocr_orientation_min_confidence": 0.6, "ocr_render_max_mp": 1.81, "ocr_routing_enabled": true, "ocr_routing_max_garbage_ratio": 0.3, "ocr_routing_min_plausibility": 0.5, "ocr_routing_min_uncovered_image": 0.15}`

> **Two sets, never combined.** The synthetic set is generated and
> labelled by the same process being measured -- it is a regression
> set that can veto a change, not evidence that one helps. The real
> set is the one a keep-or-drop decision rests on.

## Real set

_REAL -- human-labelled; this is the decision set_

| metric | value |
|---|---|
| documents | 22 |
| field accuracy (OS-NFR-001) | **80.9%** |
| `extracted`-status precision | 79.0% |
| CER (mean) | 55.0000 |
| CER (p95) | 55.0000 |
| WER (mean) | 76.0000 |
| documents with reference text | 1 / 22 |
| latency p50 / p95 per document | 24.709s / 29.741s |
| latency p50 / p95 per page | 24.709s / 29.741s |
| statuses | {"completed": 22} |

### Preprocessing -- orientation

| metric | value |
|---|---|
| pages classified | 16 (9 native-text pages never rasterized, never classified) |
| upright pages wrongly rotated | **0 / 0** |
| rotated pages corrected | 0 / 0 |
| applied angles | {"0": 15, "270": 1} |

| field | expected | produced | correct | precision | recall | extracted-prec |
|---|---|---|---|---|---|---|
| `amount` | 21 | 18 | 10 | 55.6% | 47.6% | 55.6% |
| `classification_level` | 1 | 1 | 1 | 100.0% | 100.0% | 100.0% |
| `cost_centre` | 1 | 1 | 1 | 100.0% | 100.0% | 100.0% |
| `currency` | 16 | 7 | 7 | 100.0% | 43.8% | 100.0% |
| `department` | 1 | 1 | 1 | 100.0% | 100.0% | 100.0% |
| `description` | 21 | 22 | 21 | 95.5% | 100.0% | 80.0% |
| `effective_date` | 1 | 1 | 1 | 100.0% | 100.0% | 100.0% |
| `expiry_date` | 1 | 1 | 1 | 100.0% | 100.0% | 100.0% |
| `invoice_date` | 17 | 19 | 16 | 84.2% | 94.1% | 84.2% |
| `invoice_number` | 9 | 8 | 7 | 87.5% | 77.8% | 87.5% |
| `keywords` | 1 | 1 | 1 | 100.0% | 100.0% | 0.0% |
| `language` | 1 | 1 | 1 | 100.0% | 100.0% | 100.0% |
| `purchase_order_number` | 2 | 2 | 2 | 100.0% | 100.0% | 100.0% |
| `review_due_date` | 1 | 1 | 1 | 100.0% | 100.0% | 100.0% |
| `tags` | 22 | 22 | 22 | 100.0% | 100.0% | 100.0% |
| `vendor_supplier_name` | 20 | 21 | 17 | 81.0% | 85.0% | 100.0% |

## Synthetic set

_Not run in this pass (`--set` excluded it)._

## Searchable PDF rendition -- real

- **22 of 22** completed documents produced one (100.0%)

## Memory (whole run, both sets)

- peak: **3.682 GiB** (limit 8.5 GiB, source `cgroup.memory.peak`)
- median 2.252 GiB, p95 2.46 GiB, over 110 samples at 2.0s
- single-process measurement; production runs OCR_WORKER_PROCESSES=2, each with its own model set
