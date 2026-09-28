# OCR + Qwen structured extraction: automated verification report

Branch HEAD `7badef8` (+ working tree). Live model: ``. Grounding guard ON for the DMS-facing numbers.

## Automated steps

| step | result | time |
|---|---|---|
| ocr_worker_tests | FAIL | 2s |
| dms_core_tests | FAIL | 1s |
| make_docs | FAIL | 1s |
| paddleocr_docling | FAIL | 0s |
| llm_connectivity | FAIL | 1s |
| pipeline_a | FAIL | 0s |
| structuring_qwen_qwen2_5_3b_instruct | SKIP | model not pulled |
| structuring_qwen_qwen2_5_7b_instruct | SKIP | model not pulled |
| live_e2e | FAIL | 1s |

### PaddleOCR + Docling checks



## Without Qwen vs with Qwen (same documents, same OCR input)

| metric | Without Qwen: rules, label hints configured (offline) | Without Qwen: live /ocr/process | With Qwen: live DMS rows () |
|---|---|---|---|
| Correct fields | 76% (51/67) | – | – |
| Missed fields | 9 | 0 | 0 |
| Wrong mappings | 7 | 0 | 0 |
| Table cell accuracy | 39% (28/72) | – | – |
| Repeated fields complete | 0% (0/5) | – | – |
| Blank fields kept null | 70% (7/10) | – | – |
| Document type | 0% (0/8) | – | – |
| Hallucinations reaching the DMS | 3 | 0 | 0 |
| Invented values removed by the guard | 0 | 0 | 0 |
| Valid schema-checked JSON | 100% (8/8) | – | – |
| Structuring time (sum) | 0 s | 0 s | 0 s |

Peak memory (docker stats): before_3b: 2.634GiB / 7.61GiB; live: –

## Per-document result (`liveB`, vs `liveA`)

| document | result | fields correct | table cells | blank-field leaks | without Qwen: fields | why |
|---|---|---|---|---|---|---|

Correct checks, rules vs Qwen: 0 vs 0 (fields + table cells + repeated + nulls + doc type).

## Browser (DMS UI) verification

Not run by this script -- needs a signed-in browser session (see README "Browser verification").

## Overall: **FAIL**

