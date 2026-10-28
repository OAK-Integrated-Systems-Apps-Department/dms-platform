# OCR eval report -- `no_routing`

Generated 2026-09-28T08:53:08.532439+00:00 - 2224.2s wall clock.

Settings: `{"ocr_render_dpi": 200, "ocr_languages": "en", "min_confidence_threshold": 0.95, "field_low_confidence_threshold": 0.6, "ocr_max_pages": 50, "ocr_timeout_base_s": 60.0, "ocr_timeout_per_page_s": 25.0, "ocr_orientation_enabled": true, "ocr_orientation_min_confidence": 0.6, "ocr_render_max_mp": 1.81, "ocr_routing_enabled": false, "ocr_routing_max_garbage_ratio": 0.3, "ocr_routing_min_plausibility": 0.5, "ocr_routing_min_uncovered_image": 0.15}`

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
| field accuracy (OS-NFR-001) | **79.4%** |
| `extracted`-status precision | 95.4% |
| CER (mean) | 0.2654 |
| CER (p95) | 1.9798 |
| WER (mean) | 0.2504 |
| documents with reference text | 117 / 117 |
| latency p50 / p95 per document | 22.746s / 34.683s |
| latency p50 / p95 per page | 22.746s / 34.683s |
| statuses | {"completed": 117} |

### Preprocessing -- orientation

| metric | value |
|---|---|
| pages classified | 81 (54 native-text pages never rasterized, never classified) |
| upright pages wrongly rotated | **0 / 54 (0.00%)** |
| rotated pages corrected | 27 / 27 (100.00%) |
| applied angles | {"0": 54, "90": 9, "180": 9, "270": 9} |

| condition | docs | failed | field exact | CER | WER | p50 s | p95 s |
|---|---|---|---|---|---|---|---|
| `clean_scan_300` | 9 | 0 | 95.3% | 0.0331 | 0.0325 | 25.68 | 66.79 |
| `fax_g4_tiff` | 9 | 0 | 94.2% | 0.0447 | 0.0565 | 22.75 | 26.54 |
| `garbage_text_layer` | 9 | 0 | 0.0% | 1.9576 | 1.7100 | 1.84 | 5.44 |
| `hybrid_page` | 9 | 0 | 0.0% | 0.9143 | 0.9581 | 1.63 | 4.41 |
| `low_dpi_scan_72` | 9 | 0 | 92.2% | 0.0367 | 0.0509 | 25.28 | 42.56 |
| `multipage` | 9 | 0 | 95.3% | 0.0250 | 0.0196 | 4.01 | 5.6 |
| `native_pdf` | 9 | 0 | 95.3% | 0.0322 | 0.0260 | 1.46 | 7.42 |
| `phone_photo_perspective` | 9 | 0 | 89.4% | 0.1477 | 0.1530 | 22.21 | 26.14 |
| `rotated_180` | 9 | 0 | 93.5% | 0.0324 | 0.0277 | 24.1 | 29.53 |
| `rotated_270` | 9 | 0 | 93.5% | 0.0324 | 0.0277 | 24.71 | 28.62 |
| `rotated_90` | 9 | 0 | 93.5% | 0.0324 | 0.0277 | 24.34 | 48.16 |
| `shadowed_photo` | 9 | 0 | 95.3% | 0.0327 | 0.0290 | 24.23 | 29.17 |
| `skewed_2deg` | 9 | 0 | 95.3% | 0.1291 | 0.1372 | 26.39 | 39.11 |

| field | expected | produced | correct | precision | recall | extracted-prec |
|---|---|---|---|---|---|---|
| `amount` | 39 | 33 | 32 | 97.0% | 82.0% | 97.0% |
| `author` | 39 | 33 | 33 | 100.0% | 84.6% | 100.0% |
| `classification_level` | 117 | 99 | 99 | 100.0% | 84.6% | 100.0% |
| `contract_end_date` | 39 | 33 | 33 | 100.0% | 84.6% | 100.0% |
| `contract_party` | 39 | 33 | 33 | 100.0% | 84.6% | 100.0% |
| `contract_start_date` | 39 | 33 | 33 | 100.0% | 84.6% | 100.0% |
| `contract_value` | 39 | 33 | 33 | 100.0% | 84.6% | 100.0% |
| `cost_centre` | 39 | 30 | 30 | 100.0% | 76.9% | 100.0% |
| `currency` | 39 | 33 | 33 | 100.0% | 84.6% | 100.0% |
| `department` | 117 | 99 | 99 | 100.0% | 84.6% | 100.0% |
| `description` | 117 | 117 | 93 | 79.5% | 79.5% | 93.9% |
| `effective_date` | 78 | 66 | 66 | 100.0% | 84.6% | 100.0% |
| `expiry_date` | 78 | 66 | 66 | 100.0% | 84.6% | 100.0% |
| `invoice_date` | 39 | 33 | 33 | 100.0% | 84.6% | 100.0% |
| `invoice_number` | 39 | 33 | 31 | 93.9% | 79.5% | 93.9% |
| `keywords` | 39 | 36 | 33 | 91.7% | 84.6% | 100.0% |
| `language` | 0 | 39 | 0 | 0.0% | 0.0% | 0.0% |
| `purchase_order_number` | 39 | 33 | 33 | 100.0% | 84.6% | 100.0% |
| `reference_number` | 78 | 66 | 66 | 100.0% | 84.6% | 100.0% |
| `retention_class` | 78 | 11 | 11 | 100.0% | 14.1% | 100.0% |
| `review_due_date` | 78 | 66 | 65 | 98.5% | 83.3% | 98.5% |
| `tags` | 117 | 108 | 96 | 88.9% | 82.0% | 97.0% |
| `vendor_supplier_name` | 39 | 39 | 33 | 84.6% | 84.6% | 100.0% |

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

- peak: **3.662 GiB** (limit 8.5 GiB, source `cgroup.memory.peak`)
- median 2.251 GiB, p95 2.396 GiB, over 445 samples at 2.0s
- single-process measurement; production runs OCR_WORKER_PROCESSES=2, each with its own model set
