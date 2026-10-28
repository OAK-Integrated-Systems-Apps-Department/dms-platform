# Merging `feature/ushani/watermarking` into the OCR branch

What the conflict actually was, what was kept, and what was re-applied on top.

## The shape of the problem

Not a text conflict. Two branches had independently grown:

- **two migration lineages** — both numbered V32…V45, describing different
  tables;
- **two upload pipelines** — the watermarking branch routes every upload
  through `IngestionGate` → `ScanResult` → `DocumentVersionPromoter` (the
  only class that may create a `DocumentVersion`, enforced by a database
  trigger), while the OCR branch still had `DocumentServiceImpl` writing a
  version directly;
- **two storage stories** — the watermarking branch encrypts every stored
  object through `EncryptingObjectStorageService`, while ocr-worker read the
  document straight out of a MinIO bucket.

The third one is the reason this could not be a merge of files. Under
encryption at rest, a worker reading storage directly gets ciphertext. The
read path had to change, not just be reconciled.

## The decision

**Scan gate as the base; OCR re-applied on top.** The watermarking
architecture is the one with the security properties (scan gate, folders,
document access, encryption), and none of those can be bolted back on. OCR is
additive to it.

## Migrations

| Was | Now |
|---|---|
| OCR `V32__create_documents_and_versions.sql` | **deleted** — watermarking's V32 is a strict superset, verified column by column for both `documents` and `document_versions` |
| OCR V33–V45 | renumbered **V69–V81**, in order, no gaps |
| — | **V82** `scan_result_ocr_request` — the OCR request rides on `scan_results` |
| — | **V83** `ocr_outputs_one_per_version` — the unique key the artifact upsert needs |
| — | **V84** `import_batch_ocr_fields` — "Run OCR on imported files" + the per-batch counter |

`V69` was also rewritten: it kept only `processing_jobs`, `renditions` and
`ocr_outputs`. Its `upload_sessions` was dropped (V42 is a superset) and its
`malware_scan_results` was dropped (V39 is the real implementation, with the
trigger).

All 84 applied cleanly to a scratch database.

## What was re-applied, file by file

### Backend — conflicts resolved

| File | How |
|---|---|
| `DocumentVersion.java` | union of both column sets; all ten columns checked against the live schema |
| `Document.java` | watermarking side taken whole — **see the note below** |
| `DocumentRepository.java` | watermarking side taken whole — **see the note below** |
| `DocumentVersionRepository.java` | union, plus `findReviewQueueVisible`, `findSavedReviewsVisible`, `findSavedReviews`, `findOcrHeldBack` |
| `DocumentService.java` / `DocumentServiceImpl.java` | watermarking base; OCR methods re-applied as additions. OCR's own `uploadDocument(MultipartFile…)`, `sha256Hex` and `sanitizeForStorageKey` removed — the gate owns all three now |
| `DocumentController.java` | assembled; every OCR route gained `@PreAuthorize` with an `os.*` code |
| `GlobalExceptionHandler.java` | both handler sets |
| `DocumentTypeFieldServiceImpl.java` | record accessors, plus `field.isOcrExtractable()` re-applied |
| `application.yml`, `application-docker.yml`, `docker-compose.yml` | union; `minio:` blocks removed entirely |
| `.gitignore` | both rule sets, deduped |

### Frontend — conflicts resolved

| File | How |
|---|---|
| `app/documents/page.tsx` | browser (watermarking) version wins; OCR'd documents appear in it like any other |
| `lib/documents/api.ts` | union |
| `bun.lock` | **regenerated**, not hand-merged. The frontend uses bun (bun 1.3.14); `package-lock.json` in the tree is stale and unused |

### Confirmation: `Document.java` and `DocumentRepository.java` lost nothing

Both were taken from the watermarking side whole. That is safe, and not just
by inspection of the conflict hunks (where the OCR side contributed an empty
import block and a bare `}`), but structurally:

- **`Document` has no OCR column.** Every piece of OCR state lives on
  `document_versions` (`ocr_status`, `requested_fields`, `requires_review`,
  `review_reasons`, `ocr_fields_applied_at`, `page_count`) or in the
  `document_ocr_*` tables. A grep for `ocr` in `Document.java` finds one word,
  in a comment.
- **`DocumentRepository` has no OCR query.** Its full method list is
  `countByTypeCreatedBetween`, `countInFolders`,
  `existsByTenantIdAndAccessModeAndDeletedFalse`, `existsRestrictedNotPurged`,
  `findAllByDocumentIdInAndTenantIdAndDeletedFalse`,
  `findByDocumentIdAndTenantIdAndDeletedFalse`,
  `findByTenantIdAndDeletedFalse(…)` and its title/folder variants,
  `findIdsByTypeCreatedBetween`, `findIdsInFolders`, `findRecycleBin`,
  `findRestrictedIds`, `findRestrictedIdsWithFolder`, `findSharedWith`,
  `findVisible`, `findVisibleAtTopLevel`, `findVisibleInFolder` — none OCR.
  The OCR review queue is built from `DocumentVersionRepository`, which *was*
  merged as a union.

A dropped query would therefore have to show up as a missing symbol, and the
module compiles.

## The architectural change: how ocr-worker reads a document

**Before:** ocr-worker opened MinIO with the MinIO SDK and read the object.

**Now:** it cannot. Every object is encrypted and only dms-core holds the
keys. dms-core serves plaintext over `/internal/v1/ocr`, an endpoint that:

- requires a static service token **and** a signed per-job grant naming one
  version in one tenant with an expiry;
- checks the version's tenant against the grant's rather than trusting it;
- requires a live `processing_jobs` row, so finishing or cancelling a job
  revokes its grant immediately;
- goes through `VersionContent`, so the scan gate, storage-purpose and purge
  checks are the same code a user download takes;
- streams rather than buffering, is rate limited per grant, audits once per
  grant rather than per read, and returns `no-store`.

See `docs/ocr/internal-endpoint.md`. The worker writes its artifacts back the
same way, which is what lets dms-core own their keys — and therefore what lets
the purge path find and destroy them (`OcrArtifactWriter#deleteFor`, called
from `RecycleBinService#purge`).

MinIO is removed entirely: compose service, volume, Java classes
(`ObjectStorageService`, `MinioConfig`, `MinioProperties`), the `io.minio`
dependency, the Python `storage.py` and `minio` dependency, and the env
templates. Objects go through `ObjectStorageServiceFactory` to SeaweedFS's S3
gateway locally. CI never referenced MinIO.

## Still out of date

`scripts/dev/{phase1_verification,publish_fake_scan_event,repro_batch,
smoke_test_ocr,step3_orientation_live_check}.py` stage fixtures by writing
straight into MinIO. Each now carries a header explaining why that no longer
works (no MinIO, and a hand-written object would be plaintext where ciphertext
is expected). They were left runnable rather than disabled — how to rebuild
them is a call for whoever uses them.
