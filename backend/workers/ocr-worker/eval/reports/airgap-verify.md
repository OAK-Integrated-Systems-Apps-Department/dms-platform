# OCR eval report -- `baseline`

Generated 2026-10-02T04:08:36.156345+00:00 - 493.9s wall clock.

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
| field accuracy (OS-NFR-001) | **92.1%** |
| `extracted`-status precision | 98.8% |
| CER (mean) | n/a  |
| CER (p95) | n/a  |
| WER (mean) | n/a  |
| documents with reference text | 0 / 21 |
| latency p50 / p95 per document | 26.299s / 35.83s |
| latency p50 / p95 per page | 26.299s / 35.83s |
| statuses | {"completed": 21} |

### Printed vs handwritten

OS-NFR-001's 95% is for *standard document layouts*. A form filled in by pen is not one, so the two populations are reported separately and never averaged into the headline above.

| population | docs | fields | correct | field accuracy | `extracted` precision |
|---|---|---|---|---|---|
| printed | 19 | 81 | 77 | **95.1%** | 98.7% |
| handwritten | 2 | 8 | 5 | **62.5%** | 100.0% |

### `requires_review` as a routing decision

| metric | value |
|---|---|
| documents flagged | 16 / 21 |
| documents that really had a wrong or missing field | 6 / 21 |
| precision (flagged and really wrong) | 37.5% |
| **recall (really wrong and caught)** | **100.0%** |
| missed -- wrong but not flagged | 0 |

Reasons fired: `{"poor_image_quality": 14, "missing_mandatory_field": 9}`

Mandatory fields reported missing: `{"invoice_number": 8, "vendor_supplier_name": 4, "invoice_date": 2, "amount": 2}`

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
| `currency` | 10 | 10 | 10 | 100.0% | 100.0% | 100.0% |
| `invoice_date` | 21 | 19 | 18 | 94.7% | 85.7% | 94.7% |
| `invoice_number` | 14 | 13 | 13 | 100.0% | 92.9% | 100.0% |
| `purchase_order_number` | 2 | 2 | 2 | 100.0% | 100.0% | 100.0% |
| `vendor_supplier_name` | 21 | 21 | 20 | 95.2% | 95.2% | 100.0% |

## Synthetic set

_Not run in this pass (`--set` excluded it)._

## Searchable PDF rendition -- real

- **21 of 21** completed documents produced one (100.0%)

## Memory (whole run, both sets)

- peak: **3.159 GiB** (limit None GiB, source `cgroup.memory.peak`)
- median 1.683 GiB, p95 1.932 GiB, over 113 samples at 2.0s
- single-process measurement; production runs OCR_WORKER_PROCESSES=2, each with its own model set
