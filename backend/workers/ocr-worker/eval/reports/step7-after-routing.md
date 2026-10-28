# OCR eval report -- `baseline`

Generated 2026-09-28T09:42:02.501376+00:00 - 2832.3s wall clock.

Settings: `{"ocr_render_dpi": 200, "ocr_languages": "en", "min_confidence_threshold": 0.95, "field_low_confidence_threshold": 0.6, "ocr_max_pages": 50, "ocr_timeout_base_s": 60.0, "ocr_timeout_per_page_s": 25.0, "ocr_orientation_enabled": true, "ocr_orientation_min_confidence": 0.6, "ocr_render_max_mp": 1.81, "ocr_routing_enabled": true, "ocr_routing_max_garbage_ratio": 0.3, "ocr_routing_min_plausibility": 0.5, "ocr_routing_min_uncovered_image": 0.15}`

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
| field accuracy (OS-NFR-001) | **93.9%** |
| `extracted`-status precision | 96.0% |
| CER (mean) | 0.0549 |
| CER (p95) | 0.1544 |
| WER (mean) | 0.0609 |
| documents with reference text | 117 / 117 |
| latency p50 / p95 per document | 25.635s / 37.039s |
| latency p50 / p95 per page | 25.635s / 37.039s |
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
| `clean_scan_300` | 9 | 0 | 95.3% | 0.0331 | 0.0325 | 28.09 | 41.06 |
| `fax_g4_tiff` | 9 | 0 | 94.2% | 0.0447 | 0.0565 | 24.13 | 28.3 |
| `garbage_text_layer` | 9 | 0 | 93.5% | 0.0324 | 0.0277 | 31.42 | 43.31 |
| `hybrid_page` | 9 | 0 | 95.3% | 0.1023 | 0.1759 | 24.26 | 32.23 |
| `low_dpi_scan_72` | 9 | 0 | 92.2% | 0.0367 | 0.0509 | 27.5 | 35.22 |
| `multipage` | 9 | 0 | 95.3% | 0.0250 | 0.0196 | 4.5 | 5.38 |
| `native_pdf` | 9 | 0 | 95.3% | 0.0322 | 0.0260 | 2.21 | 14.59 |
| `phone_photo_perspective` | 9 | 0 | 89.4% | 0.1477 | 0.1530 | 24.67 | 35.86 |
| `rotated_180` | 9 | 0 | 93.5% | 0.0324 | 0.0277 | 27.12 | 35.29 |
| `rotated_270` | 9 | 0 | 93.5% | 0.0324 | 0.0277 | 27.51 | 40.94 |
| `rotated_90` | 9 | 0 | 93.5% | 0.0324 | 0.0277 | 28.31 | 34.48 |
| `shadowed_photo` | 9 | 0 | 95.3% | 0.0327 | 0.0290 | 27.66 | 34.71 |
| `skewed_2deg` | 9 | 0 | 95.3% | 0.1291 | 0.1372 | 29.25 | 32.28 |

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

## Real set -- problems

- 4400051211 (2) (2) (1).PDF: no label -- run `python -m eval.draft_labels`
- 8b75ddd3-e5e4-47cf-b4f4-04314f523f87.pdf: no label -- run `python -m eval.draft_labels`
- Ayushi_bill.jpeg: no label -- run `python -m eval.draft_labels`
- Ayushi_channelling.jpeg: no label -- run `python -m eval.draft_labels`
- Benjamin receipt.png: no label -- run `python -m eval.draft_labels`
- Doc3.pdf: no label -- run `python -m eval.draft_labels`
- Doc4.pdf: no label -- run `python -m eval.draft_labels`
- Doc5.pdf: no label -- run `python -m eval.draft_labels`
- MOM_NatureSpices_2026_08_14.pdf: no label -- run `python -m eval.draft_labels`
- Receipt (1).png: no label -- run `python -m eval.draft_labels`
- SCAN0002.PDF: no label -- run `python -m eval.draft_labels`
- SCAN0011.jpg: no label -- run `python -m eval.draft_labels`
- Sales Order - SO0000456 (1).pdf: no label -- run `python -m eval.draft_labels`
- Tax Invoice - IN_2025_01448 (2).pdf: no label -- run `python -m eval.draft_labels`
- Thanuja_Channelling.png: no label -- run `python -m eval.draft_labels`
- Thanuja_bill.png: no label -- run `python -m eval.draft_labels`
- Thusitha Invoice.jpg: no label -- run `python -m eval.draft_labels`
- Thusitha_channelling.jpg: no label -- run `python -m eval.draft_labels`
- WhatsApp Image 2026-07-08 at 21.17.32 (4).jpg: no label -- run `python -m eval.draft_labels`
- benjamin inv.jpg: no label -- run `python -m eval.draft_labels`
- cc90b3ca-027b-448b-bbd1-993cd7ed04c0.pdf: no label -- run `python -m eval.draft_labels`
- image1.jpg: no label -- run `python -m eval.draft_labels`
- new prescription 2.png: no label -- run `python -m eval.draft_labels`
- sample_test_invoice.pdf: no label -- run `python -m eval.draft_labels`

## Memory (whole run, both sets)

- peak: **4.292 GiB** (limit 8.5 GiB, source `cgroup.memory.peak`)
- median 2.879 GiB, p95 3.025 GiB, over 553 samples at 2.0s
- single-process measurement; production runs OCR_WORKER_PROCESSES=2, each with its own model set
