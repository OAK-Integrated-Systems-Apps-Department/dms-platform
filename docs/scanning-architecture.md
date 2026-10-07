# Virus & Malware Scanning - the Ingestion Scan Gate

Requirement: VMSBDI (requirement-registry.md, "Virus and Malware Scanning
Before Document Ingestion"). Phase 1 (V39) delivers the scan gate on three
providers. Phase 2 (V40) adds quarantine review - release, override and
delete - plus threat notifications and permissions (§12–§15). The manual
acceptance checklist is [scanning-acceptance-checklist.md](scanning-acceptance-checklist.md).

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
- An OOXML package's parts can come in any order (PowerPoint, Google Slides
  and OneDrive often write `[Content_Types].xml` last). The head only has to
  show that marker or a first entry that is an OOXML part (`ppt/`, `word/`,
  `xl/`, `docProps/`, ...). Once the file is staged, the gate confirms
  `[Content_Types].xml` in the ZIP index (`ZipArchiveCheck.containsEntry`). An
  index that can't be read is left to the scanner.
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

> **VMSBDI-FR-004 (embedded/compressed files).** ZIP archives are accepted
> and scanned in full, every file inside included. They're also checked
> structurally before scanning. See "ZIP archives" in section 9. Other
> archive formats (7z, rar, tar, gz) are not accepted. A ZIP container with
> an Office or OpenDocument extension must still be that document.

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
4. The quarantine review API (§13) exposes records and scan detail only -
   no DTO carries a storage location or object key, and there is no
   download, presigned URL or preview endpoint, for administrators either.
   The tenant storage-location API lists and creates only `document`
   locations.
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
| `dms.scanning.timeout-seconds` / `max-file-size-bytes` | 900 s / no limit (Long.MAX_VALUE) | copied onto the seeded engine row; timeout is per clamd segment; V72 lifted rows still on 2047 MB (V68) or 100 MB |
| `dms.scanning.retry.*` | poll 30 s, 10 attempts, backoff 30 s→900 s, lease 1800 s + 300 s per GB | lease outlasts a segmented scan of the file |
| `dms.scanning.clamav.segment-bytes` / `segment-overlap-bytes` | 2000 MB / 16 MB | files larger than one segment are scanned as overlapping segments |
| `dms.scanning.cloud-verdict-timeout-minutes`, `failed-retention-hours` | 60, 72 | |
| `dms.scanning.clamav.host/port` (`CLAMAV_HOST`/`CLAMAV_PORT`) | `localhost:3310` (`clamav` in Docker) | |
| `dms.scanning.azure-defender.*` | blank | endpoint, account name/key, `AZURE_DEFENDER_CALLBACK_SECRET` |
| `dms.scanning.aws-guardduty.*` | blank | region, access/secret key (blank → AWS default credential chain), `AWS_GUARDDUTY_CALLBACK_SECRET` |
| `dms.storage.staging.*` / `dms.storage.quarantine.*` | FILE_SYSTEM `/data/dms/staging`, `/data/dms/quarantine` | platform-default staging/quarantine locations |
| `dms.ingestion.allowed-file-types` | pdf, doc(x), xls(x), ppt(x), odt/ods/odp/odg, rtf, txt, csv, tsv, md, xml, json, msg, eml, vsd(x), dwg, png, jpg/jpeg, gif, bmp, webp, heic/heif, tif/tiff - each verified by content (FileTypeInspector); never html/svg, macro-enabled Office or archives | |
| `spring.servlet.multipart.max-file-size` / `max-request-size` | -1 / -1 (no limit) | previously Spring's 1 MB default; parts over 1 MB spool to disk |

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
  `StreamMaxLength` and `MaxFileSize` must stay ≥ `clamav.segment-bytes`.
- **Files over 2 GB.** clamd scans at most 2147483645 bytes of any one stream,
  whatever `MaxFileSize` says. Its startup log shows
  `File size limit set to 2147483645 bytes` even when 4000M is configured.
  Past that it answers `OK` without reading the rest. Checked with a test
  signature in the last bytes of a 2047 MB stream: it was found.
  So `ClamAvScanAdapter` sends a larger file as consecutive 2000 MB segments,
  one `INSTREAM` each (`OverlappingSegments`):
  - Each segment starts with the last 16 MB of the one before, so a signature
    that straddles a cut is still whole somewhere.
  - Any detection in any segment makes the file infected. Otherwise a segment
    clamd couldn't finish makes it unscannable.
  - Limitation: segments after the first start mid-file, so clamd's
    file-format parsing (a PDF's objects, an Office package's parts) covers
    only the first 2000 MB. Byte-pattern signatures cover the whole file.
  - The upload request doesn't wait for such a scan. The gate hands the row
    to the retry scheduler at once, and its lease grows by
    `lease-seconds-per-gb`.

  `MaxScanSize` (the total read from inside archives) stays at 4000M.
- **ZIP archives.** `.zip` is the one archive type accepted. It is stored as
  uploaded, never extracted, and nothing in it is opened or run. On top of the
  usual checks:
  1. `FileTypeInspector` needs a ZIP local file header. A first entry marked
     encrypted makes the upload unscannable at once, like a password-protected
     Office file.
  2. Once staged, `ZipArchiveCheck` copies the archive to a private temporary
     file and opens it by its central directory, the way unzip tools do. It
     handles every standard layout: ZIP64, comments, and entries whose sizes
     follow their data (how OneDrive, SharePoint and Google Drive build their
     download ZIPs). Every entry is read once and its CRC compared, then the
     temporary copy is deleted.

     The archive is refused (415, with the reason) when it's damaged or
     truncated, or empty. It's also refused when it holds more than
     `dms.ingestion.zip-max-entries` (10,000, clamd's `MaxFiles`), unpacks to
     more than `zip-max-uncompressed-bytes` (4000 MB, clamd's `MaxScanSize`),
     or uses a compression method other than stored or Deflate.
     A password-protected entry anywhere makes it unscannable.
  3. ClamAV scans every file inside, nested archives included. Because of
     `AlertExceedsMax` and `AlertEncryptedArchive`, anything clamd can't finish
     comes back `Heuristics.Limits.Exceeded.*` or `Heuristics.Encrypted.*`.
     `ClamAvScanAdapter` maps both to unscannable, not infected.

  This replaces the earlier deviation from VMSBDI-FR-004, under which no
  archive uploads were accepted. Other archive formats (7z, rar, tar, gz) are
  still refused.
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
- Quarantine review (Phase 2, object type `QUARANTINE_RECORD`, all HIGH and
  reason-required): `QUARANTINE_RELEASED`, `QUARANTINE_RELEASE_REFUSED`,
  `QUARANTINE_OVERRIDE_RELEASED`, `QUARANTINE_DELETED`. Each carries the
  threat name, file hash and reviewer in `new_value`.

`DOCUMENT_UPLOADED` is now emitted when a clean file is promoted. Events on
async paths carry the tenant explicitly and record the SYSTEM actor, with
`uploadedBy` in `new_value`.

## 11. Known limitations

- Threat notifications are best-effort: a mail lost to an SMTP failure or a
  crash is not retried (§14). The quarantine record, audit events and review
  screen are the durable record.
- `TENANT_ADMIN`/`PLATFORM_ADMIN` hold every permission implicitly
  (`AuthorizationService#hasFullAccess`), including
  `docm.quarantine.override`. Requiring an explicit grant for override even
  for tenant admins would be a separate change to that bypass.
- No batch/bulk upload path. The requirement's bulk-report alternate flow is
  out of scope until bulk upload exists.
- There's no health screen for signature age or failed updates, because the
  monitoring module doesn't exist yet.
- The cloud adapters haven't been exercised against real Azure/AWS accounts.
  Before relying on them, verify the tag names and event field names (§2, §8)
  in a sandbox with Defender/GuardDuty enabled, using the EICAR test file.
- `new_version` promotion does a minor-version increment. Publishing and
  major-version rules belong to the future versioning module.

## 12. Releasing a false positive (Phase 2, V40)

The V39 gate trigger only accepts a version linked to a **clean** scan
result, and a quarantined file's result is `quarantined`. Release therefore
works in two steps, both recorded as a *release attempt* - a `scan_results`
row whose `released_from_scan_result_id` points at the original quarantined
result, carrying `review_requested_by` and a mandatory `review_reason`:

1. **Re-scan** (`docm.quarantine.release`). `QuarantineReleaseService` copies
   the quarantined bytes back into staging (hash-verified) and runs them
   through the ordinary gate on the tenant's **current** engine and
   signatures (`IngestionProcessor.process`).
   - Clean: promoted by `DocumentVersionPromoter` like any upload - the
     trigger is satisfied honestly - and `ReleaseAttemptFinalizer` marks the
     quarantine record `released` in the same transaction. The quarantine
     copy is deleted after commit. The original uploader owns the document.
   - Still infected: the attempt ends `quarantined` ("refused"); no second
     quarantine record, the record stays `held`, no notification.
   - Cloud engines: the copy into the protected staging area *is* the
     re-submission; the attempt stays `pending` until the scheduler or a
     callback reads the verdict. The API answers `202` and the detail screen
     polls.
2. **Override** (`docm.quarantine.override`, a separate permission). Allowed
   only once a re-scan has been refused and nothing is in flight. Promotes
   the file as a version with `scan_status = 'overridden'`, backed by a
   `released_override` scan result, and marks the record `released` - one
   transaction, HIGH audit, reason mandatory.

**The widened trigger** (V40 replaces `document_versions_require_clean_scan`)
accepts exactly two pairings: a `clean` version with a `clean` scan result
(unchanged), or an `overridden` version with a `released_override` result
that has a reviewer and reason, **and** whose `released_from_scan_result_id`
has a quarantine record that is `released`, reviewed by that same user,
pointing at this document, with the same file hash, over an original result
the scanner `quarantined`. Quarantine records are only ever created by
`IngestionProcessor` after the engine scanned and flagged those exact bytes,
so an override can only release a file the scanner has seen - never new or
unscanned bytes. An ordinary unscanned insert is still rejected. A unique
index (`uq_scan_results_one_active_release`) allows at most one in-flight or
successful release per quarantined file.

Downloads serve `clean` and `overridden` versions only.

## 13. Quarantine review API

`/api/v1/quarantine`, tenant-scoped (another tenant's id is a 404):

| Method + path | Permission | Notes |
|---|---|---|
| `GET /` | any of view/release/delete/override | `status`, `detectedFrom`, `detectedTo`, `search` (filename or threat), paged, newest first |
| `GET /{id}` | same | record, original scan detail, release history, `releaseInProgress`, `overrideAvailable` |
| `POST /{id}/release` | `docm.quarantine.release` | `{reason}` → `200` released/refused/failed, `202` pending |
| `POST /{id}/override-release` | `docm.quarantine.override` | `{reason}` → `200`; `409` before any refused re-scan |
| `POST /{id}/delete` | `docm.quarantine.delete` | `{reason}` → `204`; removes the object, record kept as `deleted` |

All actions `409` unless the record is `held` and no release is in flight.
`ck_quarantine_records_reviewed` requires `reviewed_by`/`reviewed_at` on
every non-held record; the services always set both, plus `review_reason`.

## 14. Threat notifications (VMSBDI FR-003)

`IngestionProcessor.isolate` publishes `ThreatQuarantinedEvent` inside the
quarantine transaction (original detections only, never refused re-scans).
`ThreatNotificationListener` handles it with `@TransactionalEventListener(AFTER_COMMIT)`
on Spring Boot's `applicationTaskExecutor`, so it runs only once the
quarantine is durable, can never roll it back, and never delays the upload.
Mail uses `JavaMailSender` directly, following `PasswordResetService`
(`ThreatNotificationMailer`); there is no generic notification module.

Recipients:
- the **uploader** - filename, threat, time, "not added";
- **administrators** - active in-tenant users holding `docm.quarantine.view`
  through a DB role (`RolePermissionRepository.findUserIdsWithPermission`),
  plus the tenant's `TENANT_ADMIN` users (Keycloak role members matched to
  `users` by `keycloak_id`), i.e. exactly who can act on quarantine. A
  Keycloak failure only drops the second group. The admin mail adds uploader,
  hash, engine/signature version and a link to the review screen - never the
  file.

Every failure is caught and logged at WARN. Config:
`dms.scanning.notifications.enabled` (`SCAN_NOTIFY_ENABLED`) and
`dms.scanning.notifications.mail-from` (`SCAN_NOTIFY_MAIL_FROM`).

## 15. Permissions and admin screen

V40 seeds `docm.quarantine.view`, `.release`, `.delete` and `.override`
under module `DOCM` (new actions `RELEASE` and `OVERRIDE_RELEASE`), granted
to **no role** - an administrator assigns them. Mirrored in
`PermissionCodes.java` and `frontend/lib/auth/permission-codes.ts`.

Frontend: a **Quarantine** item in the sidebar's Governance group (with Roles,
Permissions and Audit Log), shown to holders of any of the four codes.
`app/admin/quarantine` lists files (search, status, detection range);
`app/admin/quarantine/[id]` shows the record, scan result and release
history, with Release, Override and Delete each behind a reason-mandatory
confirmation dialog and shown only to holders of that permission.
