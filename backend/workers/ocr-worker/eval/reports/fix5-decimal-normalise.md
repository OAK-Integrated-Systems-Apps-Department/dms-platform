# OCR eval report -- `baseline`

Generated 2026-10-01T05:40:23.258765+00:00 - 516.0s wall clock.

Settings: `{"ocr_render_dpi": 200, "ocr_languages": "en", "min_confidence_threshold": 0.95, "field_low_confidence_threshold": 0.6, "ocr_max_pages": 50, "ocr_timeout_base_s": 60.0, "ocr_timeout_per_page_s": 45.0, "ocr_orientation_enabled": true, "ocr_orientation_min_confidence": 0.6, "ocr_render_max_mp": 1.81, "ocr_routing_enabled": true, "ocr_routing_max_garbage_ratio": 0.3, "ocr_routing_min_plausibility": 0.5, "ocr_routing_min_uncovered_image": 0.15}`

> **Two sets, never combined.** The synthetic set is generated and
> labelled by the same process being measured -- it is a regression
> set that can veto a change, not evidence that one helps. The real
> set is the one a keep-or-drop decision rests on.

## Real set

_REAL -- human-labelled; this is the decision set_

| metric | value |
|---|---|
| documents | 21 |
| field accuracy (OS-NFR-001) | **76.3%** |
| `extracted`-status precision | 89.8% |
| CER (mean) | n/a  |
| CER (p95) | n/a  |
| WER (mean) | n/a  |
| documents with reference text | 0 / 21 |
| latency p50 / p95 per document | 24.874s / 42.194s |
| latency p50 / p95 per page | 24.874s / 42.194s |
| statuses | {"completed": 21} |

### Printed vs handwritten

OS-NFR-001's 95% is for *standard document layouts*. A form filled in by pen is not one, so the two populations are reported separately and never averaged into the headline above.

| population | docs | fields | correct | field accuracy | `extracted` precision |
|---|---|---|---|---|---|
| printed | 19 | 85 | 69 | **81.2%** | 91.1% |
| handwritten | 2 | 8 | 2 | **25.0%** | 66.7% |

### `requires_review` as a routing decision

| metric | value |
|---|---|
| documents flagged | 20 / 21 |
| documents that really had a wrong or missing field | 11 / 21 |
| precision (flagged and really wrong) | 55.0% |
| **recall (really wrong and caught)** | **100.0%** |
| missed -- wrong but not flagged | 0 |

Reasons fired: `{"missing_mandatory_field": 20, "poor_image_quality": 14}`

Mandatory fields reported missing: `{"classification_level": 20, "currency": 20, "vendor_supplier_name": 20, "invoice_number": 13, "invoice_date": 2, "amount": 2}`

### Preprocessing -- orientation

| metric | value |
|---|---|
| pages classified | 16 (6 native-text pages never rasterized, never classified) |
| upright pages wrongly rotated | **0 / 0** |
| rotated pages corrected | 0 / 0 |
| applied angles | {"0": 15, "270": 1} |

| field | expected | produced | correct | precision | recall | extracted-prec |
|---|---|---|---|---|---|---|
| `amount` | 21 | 19 | 19 | 100.0% | 90.5% | 100.0% |
| `classification_level` | 1 | 1 | 1 | 100.0% | 100.0% | 100.0% |
| `cost_centre` | 1 | 1 | 1 | 100.0% | 100.0% | 100.0% |
| `currency` | 10 | 7 | 7 | 100.0% | 70.0% | 100.0% |
| `department` | 1 | 1 | 1 | 100.0% | 100.0% | 100.0% |
| `description` | 0 | 21 | 0 | 0.0% | 0.0% | 0.0% |
| `invoice_date` | 21 | 19 | 18 | 94.7% | 85.7% | 94.7% |
| `invoice_number` | 14 | 8 | 8 | 100.0% | 57.1% | 100.0% |
| `purchase_order_number` | 2 | 2 | 2 | 100.0% | 100.0% | 100.0% |
| `tags` | 1 | 21 | 1 | 4.8% | 100.0% | 100.0% |
| `vendor_supplier_name` | 21 | 21 | 13 | 61.9% | 61.9% | 100.0% |

## Synthetic set

_Not run in this pass (`--set` excluded it)._

## Searchable PDF rendition -- real

- **21 of 21** completed documents produced one (100.0%)

## Memory (whole run, both sets)

- peak: **3.146 GiB** (limit None GiB, source `cgroup.memory.peak`)
- median 1.675 GiB, p95 1.946 GiB, over 116 samples at 2.0s
- single-process measurement; production runs OCR_WORKER_PROCESSES=2, each with its own model set
