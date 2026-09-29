# Virus & Malware Scanning - the Ingestion Scan Gate (Phase 1)

Requirement: VMSBDI (requirement-registry.md, "Virus and Malware Scanning
Before Document Ingestion"). Phase 1 delivers the scan gate on three
providers. Notifications, quarantine admin APIs/screens and quarantine
release/delete are Phase 2.

**No file becomes a document version without being scanned.** Clean files are
promoted to documents. Infected files are isolated in quarantine with a
full record, and no document row is ever created for them.

## 1. Architecture overview

`com.oak.dms.scanning` mirrors `com.oak.dms.storage` (see
[storage-architecture.md](storage-architecture.md)) pattern for pattern:

| scanning                                                   | storage equivalent            |
|------------------------------------------------------------|-------------------------------|
| `ScanService` (strategy - the only scan abstraction)       | `ObjectStorageService`        |
| `ClamAvScanAdapter`, `AzureDefenderScanAdapter`, `AwsGuardDutyScanAdapter` | the three storage adapters |
| `ScanEngineFactory` (the one place that branches on provider) | `ObjectStorageServiceFactory` |
| `ScanEngineResolver` (tenant row, else platform default)   | `StorageLocationResolver`     |
| `ScanProperties` (`dms.scanning.*`), `ScanEngineBootstrap` | `StorageProperties`, `StorageLocationBootstrap` |
| `ScanException` hierarchy                                  | `StorageException` hierarchy  |

```
DocumentController ── POST /api/v1/documents ──> DocumentService.uploadDocument
                                                        │
                                                        v
                                   IngestionGate (IngestionGateImpl)
   1 resolve engine + staging, check compatible   ─────────┤
   2 FileTypeInspector (allowlist + magic bytes)  ─────────┤  415 - nothing stored
   3 scan_results row: pending, leased            ─────────┤
   4 stream -> staging location (SHA-256 + /Encrypt watch, one pass)
                                                        │
                                                        v
                                   IngestionProcessor.process
        ScanEngineFactory -> ScanService.scan(engine, stagedObject)
             │ CLEAN        │ INFECTED        │ UNSCANNABLE │ PENDING / error
             v              v                 v             v
   DocumentPromoter    quarantine copy      reject,       retry later
   (DocumentVersion-   + quarantine_records delete staged (ScanRetryScheduler)
    Promoter)          no document ever
```

## 2. Two scan modes

| | On-prem | Azure | AWS |
|---|---|---|---|
| Engine | ClamAV `clamd` (INSTREAM over TCP) | Defender for Storage | GuardDuty Malware Protection for S3 |
| `scan_mode` | `pre_store_stream` | `post_store_object` | `post_store_object` |
| Verdict | synchronous, in-call | async - blob index tag + Event Grid | async - object tag + EventBridge |
| Staging | any storage location | `AZURE_BLOB` container on the Defender-protected account | `S3` bucket with a Malware Protection plan |
| engine / signature version | from `clamd VERSION` | not exposed (null) | not exposed (null) |

A cloud scanner can only scan an object after it has landed in its own
storage, which is why every upload is staged first and why `scan_mode` is a
persisted column. The staged object is always the scan target, so a single
`ScanService.scan(ScanEngine, ScanTarget)` serves both modes:

- **ClamAV**: reads the staged object through `ObjectStorageServiceFactory`,
  streams it to `clamd`, and returns CLEAN/INFECTED now.
  `Heuristics.Encrypted.*` maps to UNSCANNABLE.
- **Defender / GuardDuty**: neither has a per-object submit API. Writing the
  object into the protected container or bucket *is* the submission. `scan()`
  reads the provider's verdict tag and returns PENDING until it appears.
  The staged object's ETag, captured at staging, is stored as
  `scan_results.external_scan_id`. A verdict whose ETag doesn't match is
  never applied.

`scan()` is idempotent, so retries, polling, callbacks and crash recovery all
go through the same call.

## 3. Where the gate sits and how it can't be bypassed

`DocumentVersionPromoter` (document module) is the **only** code that builds
a `DocumentVersion`. It implements the scanning module's `DocumentPromoter`,
and only the gate calls it, for a CLEAN verdict. Every creation path uses
`IngestionGate.submit`:
- `initial_upload` today, through `IngestionRequest.newDocument`;
- `replacement_upload`, `workflow_update`, `signed_copy` and `migration`
  later, through `IngestionRequest.newVersion`.

This is **enforced by the database** (V39), not by convention:
- `trg_document_versions_require_clean_scan` is a deferred constraint trigger
  on insert. At commit it rejects any version that lacks a linked
  `scan_results` row with `status = clean` and **the same file hash**, stored
  in a `purpose = document` location. It also applies to raw SQL and to the
  Python workers.
- `trg_document_versions_content_immutable` stops a version's key, location
  or hash from being changed later, and stops `scan_status` moving away from
  `clean`.
- `trg_scan_results_link_immutable` freezes a scan result's verdict, hash and
  link once it produced a version.
- On promotion, `StorageObjectMover.copyVerified` re-hashes the bytes while
  copying from staging to the document location. It refuses the copy unless
  they match the hash taken at staging, so the promoted bytes are exactly the
  scanned bytes.

Residual risk: someone who deliberately writes a forged `clean`
`scan_results` row could still create a version. What this blocks is an
accidental back door.

## 4. Upload response contract

`POST /api/v1/documents` returns an `IngestionTicketResponse`, whose
`scanResultId` is the ticket:

| Outcome | HTTP |
|---|---|
| clean (ClamAV, usually) | `201`, `Location: /api/v1/documents/{documentId}` |
| pending (cloud verdict, or engine unavailable - `retrying: true`) | `202`, `Location: /api/v1/ingestion-tickets/{id}`, `Retry-After: 5` |
| infected / quarantined / unscannable / failed | `422`, ticket body with `message` |
| type not allowed | `415` (error body, nothing stored) |
| too large | `413` (error body) |

`GET /api/v1/ingestion-tickets/{id}` requires `DOCM_DOCUMENTS_CREATE` and is
limited to the uploader's own uploads; anyone else gets 404.

**Frontend, for when the upload screen is built:** on `202`, show
"Scanning…" and poll `Location` (honour `Retry-After`, back off to 30 s)
until the status is terminal. Then:
- `clean`: open the document.
- `infected`/`quarantined`: "threat detected, file blocked".
- `unscannable`: "remove the password protection and re-upload".
- `failed`: "couldn't be scanned, try again later".

## 5. Allowed file types and unscannable files

`FileTypeInspector` runs **before anything is stored**:
- The extension must be in `dms.ingestion.allowed-file-types`.
- The first bytes must match that extension's format: `%PDF-`, OOXML ZIP with
  `[Content_Types].xml`, ODF ZIP with a leading `mimetype` entry, OLE2, RTF,
  PNG, JPEG, TIFF, or text with no NUL bytes and no binary signature.
- The client's Content-Type is ignored, and the canonical MIME type is stored.

Unscannable files are marked `unscannable` and rejected (VMSBDI alternate
flow 4):
- An OOXML extension on an OLE2 container is how Office stores a
  password-protected `.docx`/`.xlsx`/`.pptx`. It is detected from the header.
- A PDF containing `/Encrypt` is caught by `EncryptionMarkerInputStream`
  during the staging pass. **Any** encrypted PDF counts, including
  owner-password-only PDFs that open without a password. This is the agreed
  Phase 1 policy: no PDFBox, and no parser runs over unscanned bytes.
- GuardDuty's `UNSUPPORTED` verdict is also mapped to unscannable.

Not detected in Phase 1: encrypted ODF documents and legacy
`.doc`/`.xls`/`.ppt` encryption. ClamAV still scans those files.

> **Deliberate deviation from VMSBDI-FR-004.** FR-004 asks for scanning of
> "embedded/compressed files (e.g., ZIP archives)". This system accepts no
> archive uploads: there is no zip/7z/rar/tar entry in the allowlist, and a
> ZIP container is accepted only when it is structurally an OOXML/ODF
> document. There are therefore no archives to scan. This is intentional,
> not an oversight. (ClamAV still inspects the ZIP structure inside
> OOXML/ODF files.)

## 6. Failure modes - fail closed

**Claim/lease model.** Whoever works on a `scan_results` row first leases it.
- The upload request inserts its row already leased.
- `ScanRetryScheduler` claims due rows one at a time with
  `FOR UPDATE SKIP LOCKED`, so multiple instances are safe.
- Scans and copies run outside DB transactions. Every state change is its own
  short transaction that re-locks the row and checks that this worker's lease
  value is still the current one.
- A stalled or dead worker's lease expires and someone else takes over. The
  stalled worker can then never commit an outcome.

| Failure | What happens | Uploader sees |
|---|---|---|
| Engine unavailable / timeout | `attempt_count++`, backoff 30 s doubling to 15 min, `DOCUMENT_SCAN_FAILED` audited, staged object kept | `202` pending, `retrying: true` |
| Retries exhausted (`max-attempts`, default 10 ≈ 2 h) | `failed`, never promoted; staged object purged after `failed-retention-hours` (72) | `failed` |
| Cloud verdict never arrives (`cloud-verdict-timeout-minutes`, 60) | `failed` | `failed` |
| Promotion copy/commit fails after CLEAN | counted as a failed attempt; `clean` is only ever set together with the version insert, so the retry rescans | pending, retrying |
| Quarantine copy fails after INFECTED | row stays `infected` (never promoted) and isolation is retried indefinitely | `infected` |
| Process dies mid-staging | lease expires → scheduler fails the row ("upload interrupted") and deletes the partial object | `failed` |
| Process dies mid-scan | lease expires → scheduler rescans from staging | pending → outcome |
| Dies after copy to document location, before commit | an unreferenced clean object remains; no version points at it, so it's unreachable. The retry promotes under new ids (storage waste only; Phase 2 GC) | pending → clean |
| Dies after copy to quarantine, before commit | row still `infected`; the retry re-copies to the same deterministic key | `infected` → `quarantined` |
| Dies after commit, before the staging delete | the purge sweep deletes staged objects of finished rows | - |

Bytes reach a document location only after a CLEAN verdict, only as the
hash-verified scanned bytes, and only a DB-validated version row can
reference them.

## 7. Quarantine isolation - how it is enforced

1. `StorageLocationResolver` returns a quarantine (or staging) location only
   when that purpose is requested explicitly. Only `IngestionProcessor` asks
   for quarantine.
2. The V39 trigger means no `document_versions` row can reference a
   non-document location. Downloading goes through a version row, and
   `DocumentServiceImpl#downloadDocument` re-checks
   `purpose == document` as well.
3. `generatePresignedDownloadUrl` has no callers.
4. `quarantine_records` has no controller or DTO in Phase 1. The tenant
   storage-location API lists and creates only `document` locations.
5. `storage_locations.purpose` is immutable (trigger).
6. `StorageLocationBootstrap` **refuses to start** if the platform staging or
   quarantine location resolves to the same physical target as the document
   location, or as each other (same bucket/container, or a nested filesystem
   path).

Legacy versions uploaded before V39 were never scanned. They stay
`scan_status = pending`, and download returns `409 DOCUMENT_NOT_SCANNED`.

## 8. Cloud callbacks

`POST /api/v1/scanning/callbacks/azure-defender` and `/aws-guardduty` are
public in `SecurityConfig` (the providers can't present a JWT). They are
authenticated by the shared secret in `X-Oak-Scan-Callback-Secret`, compared
in constant time. A blank configured secret disables the endpoint (401).

- **Azure**: an Event Grid custom-topic subscription with a static delivery
  header. The subscription validation handshake is answered automatically.
- **AWS**: an EventBridge rule on "GuardDuty Malware Protection Object Scan
  Result" → an API destination whose Connection uses API-key auth with the
  same header.

A callback **never sets a verdict**. It matches the row by staged key,
container/bucket, provider and ETag, and stores the threat-name hint. Tags
don't carry threat names, so the hint is used only if the tag independently
confirms infection. The callback then processes the row immediately, which
re-reads the provider's tag. The scheduler polls the same tag as a fallback,
so callbacks are optional.

Provider prerequisites:
- **Defender**: on-upload malware scanning enabled on the staging storage
  account, with the "blob index tags" result option on.
- **GuardDuty**: a Malware Protection plan on the staging bucket, with
  "tag scanned objects" on.

## 9. Configuration

| Key | Default | Notes |
|---|---|---|
| `dms.scanning.default-provider` (`SCAN_PROVIDER`) | `CLAMAV` | seeds the platform-default `scan_engines` row |
| `dms.scanning.timeout-seconds` / `max-file-size-bytes` | 60 / 100 MB | copied onto the seeded engine row |
| `dms.scanning.retry.*` | poll 30 s, 10 attempts, backoff 30 s→900 s, lease 300 s | |
| `dms.scanning.cloud-verdict-timeout-minutes`, `failed-retention-hours` | 60, 72 | |
| `dms.scanning.clamav.host/port` (`CLAMAV_HOST`/`CLAMAV_PORT`) | `localhost:3310` (`clamav` in Docker) | |
| `dms.scanning.azure-defender.*` | blank | endpoint, account name/key, `AZURE_DEFENDER_CALLBACK_SECRET` |
| `dms.scanning.aws-guardduty.*` | blank | region, access/secret key (blank → AWS default credential chain), `AWS_GUARDDUTY_CALLBACK_SECRET` |
| `dms.storage.staging.*` / `dms.storage.quarantine.*` | FILE_SYSTEM `/data/dms/staging`, `/data/dms/quarantine` | platform-default staging/quarantine locations |
| `dms.ingestion.allowed-file-types` | pdf, doc(x), xls(x), ppt(x), odt/ods/odp, rtf, txt, csv, png, jpg/jpeg, tif/tiff | |
| `spring.servlet.multipart.max-file-size` | 100 MB | previously Spring's 1 MB default |

A tenant `scan_engines.credential_ref` resolves `SCAN_CRED_<REF>_ACCESS_KEY`
/ `_SECRET_KEY` (AWS) or `_ACCOUNT_NAME` / `_ACCOUNT_KEY` (Azure) at call
time. It is never stored as a raw credential and never logged.

**Local stack:**
- `infra/local/docker-compose.yml` runs `clamav/clamav-debian:1.4.3`. It's
  multi-arch; `clamav/clamav` publishes amd64 only.
- The image's freshclam daemon checks for signature updates 12× a day
  (`FRESHCLAM_CHECKS`). If an update fails, clamd keeps the last good
  definitions.
- `clamd` limits are set through the image's `CLAMD_CONF_*` env vars.
  `StreamMaxLength` must stay ≥ the scan size cap.
- No new secret is needed locally.

## 10. Audit events

All events are module `DOCM`, with object type `SCAN_RESULT` (or
`QUARANTINE_RECORD`):
- `DOCUMENT_SCAN_CLEAN` (MEDIUM)
- `DOCUMENT_SCAN_INFECTED` (HIGH)
- `DOCUMENT_QUARANTINED` (HIGH)
- `DOCUMENT_SCAN_FAILED` (MEDIUM, one per failed attempt, with `attempt` and
  `terminal` flags)
- `DOCUMENT_SCAN_UNSCANNABLE` (MEDIUM)

`DOCUMENT_UPLOADED` is now emitted when a clean file is promoted. Events on
async paths carry the tenant explicitly and record the SYSTEM actor, with
`uploadedBy` in `new_value`.

## 11. Known limitations (Phase 1)

- No notifications, quarantine admin API/screen, or release/delete of
  quarantined files (Phase 2). V39's triggers will need a `released` path
  when release lands.
- No batch/bulk upload path. The requirement's bulk-report alternate flow is
  out of scope until bulk upload exists.
- There's no health screen for signature age or failed updates, because the
  monitoring module doesn't exist yet.
- The cloud adapters haven't been exercised against real Azure/AWS accounts.
  Before relying on them, verify the tag names and event field names (§2, §8)
  in a sandbox with Defender/GuardDuty enabled, using the EICAR test file.
- `new_version` promotion does a minor-version increment. Publishing and
  major-version rules belong to the future versioning module.
