# Pluggable Object Storage Architecture

## 1. Architecture overview

```
Controller (DocumentController)
        |
        v
DocumentService (DocumentServiceImpl)
        |
        v
ObjectStorageServiceFactory -- resolves --> ObjectStorageService
        |                                         ^
        v                                         |
StorageLocationResolver     +--------------------+-+--------------------+
        |                   |                      |                    |
        v             S3ObjectStorageAdapter  AzureBlobStorageAdapter  FileSystemStorageAdapter
StorageLocation (DB row)    |                      |                    |
                             v                      v                    v
                    AWS SDK v2 S3Client   Azure Blob Storage SDK   java.nio.file
                             |
               +-------------+-------------+
               |             |             |
            AWS S3         MinIO      SeaweedFS S3 gateway
```

`DocumentService` depends only on `ObjectStorageService` (via the factory) and
`StorageLocationResolver`. It never imports an AWS SDK type, a MinIO/SeaweedFS
type, or `java.io.File`/`Path`/`Files`. Moving a tenant from AWS S3 to MinIO to
an on-prem filesystem never touches `DocumentService`, `DocumentController`, or
any other document business logic - only which `StorageLocation` row that
tenant resolves to.

**Malware scanning.** Uploads no longer write straight into the document
location: every file is staged, scanned and only then promoted, through the
scan gate in `com.oak.dms.scanning`. Storage locations now carry a `purpose`
(`document` | `staging` | `quarantine`), and `StorageLocationResolver`
resolves by tenant *and* purpose. See
[scanning-architecture.md](scanning-architecture.md).

## 2. Adapter / Strategy / Factory responsibilities

- **Adapter** (`S3ObjectStorageAdapter`, `AzureBlobStorageAdapter`,
  `FileSystemStorageAdapter`): each wraps a real storage mechanism (AWS SDK
  v2, the Azure Blob Storage SDK, `java.nio.file`) behind the common
  `ObjectStorageService` interface. `S3ObjectStorageAdapter` is the one
  adapter that talks to AWS S3, MinIO, *and* the SeaweedFS S3 gateway - all
  three are S3-compatible, and AWS SDK v2's `endpointOverride()`/
  `forcePathStyle()` builder options are all that differ between them. A
  separate `MinIOObjectStorageAdapter`/`SeaweedFSObjectStorageAdapter` is
  deliberately **not** created unless a real provider-specific feature (not
  just a different endpoint) ever needs one.
- **Strategy**: `ObjectStorageService` is the strategy interface; which
  concrete adapter actually runs for a given call is decided per
  `StorageLocation.provider`, not hardcoded anywhere in `DocumentService`.
- **Factory** (`ObjectStorageServiceFactory`): the one place this application
  branches on storage provider. Autowires every `ObjectStorageService` Spring
  bean and picks the one whose `supportedProvider()` matches. `DocumentService`
  never contains `if (provider == S3)`/`if (provider == FILE_SYSTEM)` logic.
- **Spring DI**: every adapter, the factory, `StorageLocationResolver`, and
  `StorageLocationService` are ordinary Spring beans, wired the same way as
  everything else in this codebase (constructor injection via Lombok
  `@RequiredArgsConstructor`).

## 3. AWS S3 configuration

Deployment-wide defaults (used by the platform-default `StorageLocation` and
by any tenant location with a blank `credentialRef`):

```
STORAGE_PROVIDER=S3
S3_REGION=us-east-1
S3_BUCKET=my-company-dms-documents
S3_ACCESS_KEY=...
S3_SECRET_KEY=...
S3_PATH_STYLE_ACCESS=false
# S3_ENDPOINT left unset - real AWS S3's own default routing.
```

A tenant that wants its own AWS account/bucket instead registers its own
`StorageLocation` via `POST /api/v1/storage-locations`:

```json
{
  "name": "tenant-b-aws",
  "provider": "S3",
  "region": "eu-west-1",
  "bucket": "tenant-b-documents",
  "pathStyleAccess": false,
  "credentialRef": "tenant-b-aws"
}
```

...with the matching credentials supplied as environment variables (see
§8 - Security):

```
STORAGE_CRED_TENANT-B-AWS_ACCESS_KEY=...
STORAGE_CRED_TENANT-B-AWS_SECRET_KEY=...
```

## 4. MinIO configuration

Same adapter, just a custom endpoint and (usually) path-style access:

```json
{
  "name": "tenant-b-minio",
  "provider": "S3",
  "endpoint": "https://minio.tenant-b.example.com",
  "region": "us-east-1",
  "bucket": "dms-documents",
  "pathStyleAccess": true,
  "credentialRef": "tenant-b-minio"
}
```

```
STORAGE_CRED_TENANT-B-MINIO_ACCESS_KEY=...
STORAGE_CRED_TENANT-B-MINIO_SECRET_KEY=...
```

## 5. SeaweedFS configuration

Point the same adapter at SeaweedFS's own S3 gateway endpoint - everything
else is identical to the MinIO example above:

```json
{
  "name": "tenant-c-seaweedfs",
  "provider": "S3",
  "endpoint": "https://seaweed-s3.tenant-c.example.com",
  "bucket": "dms-documents",
  "pathStyleAccess": true,
  "credentialRef": "tenant-c-seaweedfs"
}
```

## 6. On-prem filesystem configuration

```
STORAGE_PROVIDER=FILE_SYSTEM
STORAGE_FILESYSTEM_BASE_PATH=/data/dms/documents
```

Or, for a tenant-specific mount:

```json
{
  "name": "tenant-d-onprem",
  "provider": "FILE_SYSTEM",
  "basePath": "/mnt/tenant-d-nfs/dms-documents"
}
```

`FileSystemStorageAdapter` confines every read/write to `basePath`: the
object key is resolved and normalized against it, and explicitly checked to
not escape it before any operation - path traversal via a crafted object key
is rejected with `StorageConfigurationException`, regardless of what the key
contains. Object keys are always server-generated (never a raw uploaded
filename), so this is defense in depth, not the primary safeguard.

## 7. Azure Blob Storage configuration

`AzureBlobStorageAdapter` (Azure Blob Storage SDK, `com.azure:azure-storage-blob`)
reuses the same `endpoint`/`bucket`/`credentialRef` columns S3 does, rather
than adding Azure-specific columns: `endpoint` is the Blob Service endpoint
URL, `bucket` is the blob *container* name, and `credentialRef` resolves an
account name/key pair instead of an access/secret key pair. Unlike S3, a
blank `endpoint` has no universal default to fall back to (there's no "the"
Azure endpoint the way `s3.amazonaws.com` is AWS S3's) - it falls back to
this deployment's `dms.storage.azure-blob.endpoint` default, and
`StorageConfigurationException` if that's blank too.

Deployment-wide defaults (used by the platform-default `StorageLocation` and
by any tenant location with a blank `credentialRef`):

```
STORAGE_PROVIDER=AZURE_BLOB
AZURE_STORAGE_ENDPOINT=https://my-company-dms.blob.core.windows.net
AZURE_STORAGE_CONTAINER=dms-documents
AZURE_STORAGE_ACCOUNT_NAME=my-company-dms
AZURE_STORAGE_ACCOUNT_KEY=...
```

A tenant that wants its own Azure Storage account/container instead
registers its own `StorageLocation` via `POST /api/v1/storage-locations`:

```json
{
  "name": "tenant-e-azure",
  "provider": "AZURE_BLOB",
  "endpoint": "https://tenant-e-dms.blob.core.windows.net",
  "bucket": "dms-documents",
  "credentialRef": "tenant-e-azure"
}
```

...with the matching credentials supplied as environment variables (see
§8 - Security):

```
STORAGE_CRED_TENANT-E-AZURE_ACCOUNT_NAME=...
STORAGE_CRED_TENANT-E-AZURE_ACCOUNT_KEY=...
```

Authentication uses account-name/account-key (`StorageSharedKeyCredential`),
the same shared-key model S3's access/secret key pair mirrors - not a SAS
token or Azure AD/`TokenCredential`, which would need a different
credential-resolution path than the `STORAGE_CRED_<ref>_*` convention every
other provider in this codebase already uses.

## 8. Tenant-specific storage & security

- `StorageLocation.tenantId` is `NULL` for the one platform-wide default row
  (`StorageLocationBootstrap` creates it on startup from `STORAGE_PROVIDER`/
  `S3_*`/`STORAGE_FILESYSTEM_BASE_PATH` if none exists yet) and set for every
  tenant-registered location. `StorageLocationResolver` picks a tenant's own
  location first, falling back to the platform default only if the tenant
  hasn't registered one.
- **No storage credential is ever stored in Postgres.** `StorageLocation`
  only stores a `credentialRef` string. `S3ObjectStorageAdapter` resolves the
  actual access/secret key pair from environment variables named
  `STORAGE_CRED_<credentialRef>_ACCESS_KEY`/`_SECRET_KEY` at call time (a
  blank `credentialRef` falls back to the deployment's own `S3_ACCESS_KEY`/
  `S3_SECRET_KEY`); `AzureBlobStorageAdapter` resolves the same way from
  `STORAGE_CRED_<credentialRef>_ACCOUNT_NAME`/`_ACCOUNT_KEY` (falling back to
  `AZURE_STORAGE_ACCOUNT_NAME`/`_ACCOUNT_KEY`). This is the same pattern every
  other secret in this application already uses - environment variables
  populated from the OpenBao-encrypted `infra/secrets/*.enc.env` file before
  the container starts (see `scripts/openbao-decrypt-secrets.sh`) - there is
  no runtime secrets-fetching client in this codebase to integrate with
  instead.
- **Tenant isolation is never based on the object key alone.**
  `DocumentServiceImpl` resolves the caller's tenant from `TenantContext`,
  loads the `Document`/`DocumentVersion` scoped to that tenant (a mismatch is
  a 404, not a 403 - consistent with `ResourceNotFoundException`'s existing
  convention across this codebase), and re-validates that the version's
  `StorageLocation` belongs to that same tenant (or is the platform-default,
  `tenantId IS NULL`) immediately before every storage call.
- Registering a `StorageLocation` (`POST /api/v1/storage-locations`) is
  gated the same way tenant-level administration is gated elsewhere in this
  application (tenant admin or platform admin), not a per-document `DOCM`
  permission - it's tenant infrastructure, not a document action.
- Nothing ever logs an access key, secret key, presigned URL, or document
  content - see `S3ObjectStorageAdapter`'s and `StorageLocation`'s own doc
  comments.

## 8a. Encryption at rest (Module DE, DE-FR-001..006)

Every object the application writes - documents, staged and quarantined
uploads, bulk-import files, export ZIPs - is encrypted **by the application,
before it leaves the process**, whichever storage provider holds it. Storage
on its own therefore never holds readable content (DE-FR-006).

**How it works** (`com.oak.dms.encryption`):

- `ObjectStorageServiceFactory#resolve` wraps every adapter in
  `EncryptingObjectStorageService`. No business code knows about encryption.
- **Upload:** a fresh random 256-bit data key per object. The object streams
  through Google Tink's streaming AEAD (AES-256-GCM, HKDF-SHA256 subkey,
  authenticated 1 MiB segments, associated data = location + object key). Only
  ciphertext reaches storage, so a modified, truncated or swapped object fails
  to decrypt rather than returning wrong bytes.
- **Keys:** the data key is **wrapped** by the key service and only the wrapped
  form is kept, in `storage_object_keys` (V55), separate from the data
  (DE-FR-004, DE-NFR-001).
- **One master key per tenant (V57):** each tenant's objects are wrapped with
  its own master key (`<key-name>-<tenantId>` in OpenBao, created on first use;
  its own entry in the development key file). One tenant's key never opens
  another's documents, and the key screen (rotate, re-wrap, encrypt existing)
  only ever acts on the caller's own tenant. The `platform` key covers objects
  written outside any tenant, and the keys from before V57; re-wrap moves those
  onto the tenant's own key.
- **Download:** after the access checks, the key service unwraps the data key
  and the object is decrypted as it streams to the user (DE-FR-003). Encrypted
  objects are never given a presigned URL.
- **Key cache:** unwrapped data keys are kept in memory for a short time
  (`ENCRYPTION_KEY_CACHE_TTL`, default 5 minutes; at most
  `ENCRYPTION_KEY_CACHE_MAX_ENTRIES`, default 10,000), so re-opening a document
  doesn't call OpenBao again. Cached keys are copied in and out and wiped when
  they leave the cache; `PT0S` turns the cache off. A bulk export unwraps a whole
  chunk of keys in **one** Transit batch request (`batch_input`, up to 100 per
  call) before it streams the files.
- **Objects without a key row** (stored before encryption) are served as stored
  until "Encrypt existing documents" converts them. Document version keys are
  immutable, so the encrypted bytes go to `<key>.enc` and the key row's
  `physical_object_key` (V56) points there. Saving that row is the atomic
  switch, and only then is the readable original deleted. Each copy is
  verified against the SHA-256 recorded at upload.
- **Cloud malware scanners:** GuardDuty and Defender for Storage scan the staged
  object in place, so the application can't encrypt it. Instead the storage
  must: the upload is **refused** unless the staging location encrypts at rest
  (`resolveForInPlaceScan`). Azure Blob always does (Storage Service
  Encryption); for S3 set `SCAN_STAGING_SSE=aws:kms` (with
  `SCAN_STAGING_SSE_KMS_KEY_ID`) or `AES256`, or `"serverSideEncryption"` in
  the location's `configuration`. The staged copy is deleted as soon as the
  verdict moves it on (encrypted by the app) or quarantines it. ClamAV scans the
  stream, so its staged copies are app-encrypted like everything else.
  `S3_SSE` / `S3_SSE_KMS_KEY_ID` add server-side encryption to every S3
  location as defence in depth.
- **Rotation (DE-FR-005):** "Rotate key" adds a master-key version. New objects
  use it at once; older data keys stay unwrappable. "Re-wrap" moves them onto
  the latest version inside the key service; **no object is rewritten**.
  Rotation, re-wrap and backfill runs are audited (`ENCRYPTION_*`, module DE).
  Keys on the same tenant key are re-wrapped with batch requests.
- **Rotation schedule (V59):** each organisation sets how often its key should
  be rotated (off, 90 days, 6 months or a year) on the key screen. The default
  is `ENCRYPTION_ROTATION_DEFAULT_DAYS`, 365. Every
  `ENCRYPTION_ROTATION_CHECK_INTERVAL` (6 hours), one instance (it holds a
  Postgres advisory lock) checks every active tenant with encrypted objects. A
  key is due once the interval has passed since its last rotation, or since its
  first stored key if it was never rotated. With "rotate automatically" on, a
  due key is rotated and its data keys re-wrapped (audited as
  `ENCRYPTION_KEY_ROTATED`, trigger `schedule`). Otherwise an
  `ENCRYPTION_KEY_ROTATION_DUE` audit event is the reminder, repeated every
  `ENCRYPTION_ROTATION_REMIND_EVERY` (7 days), and the key screen shows the key
  as due.
- **Crypto-shredding a removed organisation:** after a platform admin has
  deactivated a tenant, `POST /api/v1/tenants/{tenantId}/shred-keys`
  (PLATFORM_ADMIN; body `confirmTenantName`, `reason`,
  `acknowledgeUnencrypted`) makes everything it stored encrypted unreadable for
  good. It overwrites the tenant's wrapped data keys (each row kept as a tombstone, V60, so
  a read fails plainly instead of serving ciphertext), destroys its master key in
  every key service holding one (OpenBao: `deletion_allowed`, then delete the
  Transit key; the development key file and its backup: the tenant's entry
  removed), and sets `tenants.keys_shredded_at`. That also covers copies of the
  storage and database backups that still hold the wrapped keys, as long as no
  OpenBao backup from before the shred is restored. Files stored before
  encryption aren't protected by the key: the call refuses while any remain
  unless `acknowledgeUnencrypted` is true. If a key service can't be reached
  part-way, the call answers 502 and can be repeated. Audited as
  `ENCRYPTION_TENANT_KEYS_SHREDDED` (platform level, reason required). The
  OpenBao policy needs `update` on `transit/keys/dms-documents*/config` and
  `delete` on `transit/keys/dms-documents*` for this.
  Every unwrap is also an authenticated request in OpenBao's own audit log
  (DE-NFR-006).

- **Jobs (V58):** re-wrap and "Encrypt existing" are tracked in
  `encryption_jobs`, one row per tenant and job, shared by every dms-core
  instance. A job is claimed with one conditional upsert, so it runs once per
  tenant however many instances or clicks there are. Its progress survives a
  restart. A running job writes a heartbeat every 30 seconds; if the heartbeat
  stops for 3 minutes (its instance died), the screen shows the job as
  interrupted and it can be started again. Both jobs skip work that is already
  done, so a restarted job carries on where the last one stopped.
- **Encrypt existing** covers document versions, files still in quarantine and
  export ZIPs that can still be downloaded. The key screen counts the last two
  as "other files".

**Key services** (`dms.encryption.provider`):

| Provider | Where the master key lives | Use |
|---|---|---|
| `openbao` | OpenBao Transit key `dms.encryption.openbao.key-name` (default `transit/dms-documents`, aes256-gcm96, never exported) | Production |
| `local-dev` | A JSON key file on its own volume (`encryption_dev_keys` locally) | Local development only |

`dms.encryption.require-managed-keys=true` (production) refuses to start with
anything but OpenBao.

The development key file is protected against loss:

- every change is also written to a backup copy outside Docker
  (`ENCRYPTION_LOCAL_DEV_BACKUP_FILE`; locally the gitignored `.dms-dev-keys/`
  folder of the repository), and a missing key file is **restored from it** on
  start;
- with neither file present while encrypted objects depend on it, dms-core
  **refuses to start** rather than creating a new key;
- at every start one stored key per tenant and version is test-unwrapped, so a
  wrong or old key file is refused at once, not discovered on a later download.

**Switching key service** (for example development key file to OpenBao): each
key row records which service wrapped it. Keep the old service configured while
switching - the other one stays available for reading - then run **Re-wrap** on
the key screen. It unwraps each key with its old service and wraps it with the
new one under the tenant's key; no object is rewritten.

`dms.encryption.enabled=false` only stops encrypting **new** objects; already
encrypted objects are still decrypted.

**Testing against a real OpenBao:** `OpenBaoTransitKeyServiceIT` runs when
`OPENBAO_IT_ADDR` and `OPENBAO_IT_TOKEN` are set (see its class comment for a
throwaway dev server) and is skipped otherwise. The app's own settings are
`ENCRYPTION_OPENBAO_ADDR` / `_AUTH` / `_TOKEN` / `_ROLE_ID` / `_SECRET_ID` /
`_APPROLE_MOUNT` / `_MOUNT` / `_KEY` - deliberately not
`OPENBAO_TRANSIT_KEY`, which the secrets scripts use for their own key:
documents must never share a key with the secrets file.

**Signing in to OpenBao** (`ENCRYPTION_OPENBAO_AUTH`):

- `approle` (production): dms-core logs in with `ENCRYPTION_OPENBAO_ROLE_ID` and
  `ENCRYPTION_OPENBAO_SECRET_ID` (`POST auth/<approle-mount>/login`). It renews
  the token in the background once two thirds of its lifetime have passed. When
  the token can't be renewed any more (max TTL reached, or revoked), dms-core
  logs in again. A request refused with 403 triggers one fresh login and one
  retry. No long-lived token is stored anywhere.
- `token` (development, or a token issued by an agent): a fixed
  `ENCRYPTION_OPENBAO_TOKEN`. At start dms-core looks the token up and renews
  it before it expires, if it is renewable.

**Audit device:** with `ENCRYPTION_REQUIRE_AUDIT_DEVICE=true` (the production
default), dms-core refuses to start unless OpenBao has at least one audit
device enabled (`sys/audit`). Without one, nothing records who unwrapped which
key (DE-NFR-006).

**Production go-live runbook** (production starts with
`ENCRYPTION_ENABLED=false` until these are done):

1. In OpenBao: `bao secrets enable transit` (if not already),
   `bao write -f transit/keys/dms-documents type=aes256-gcm96` (dms-core also
   creates it on first start if missing).
2. Create a policy limited to the document keys - `transit/datakey/plaintext/dms-documents*`,
   `transit/encrypt/dms-documents*`, `transit/decrypt/dms-documents*`,
   `transit/rewrap/dms-documents*` (update), `transit/keys/dms-documents*`
   (read, create, delete), `transit/keys/dms-documents*/config` (update - for
   crypto-shredding) and `transit/keys/dms-documents*/rotate` (update) - the `*`
   covers each tenant's own key - plus `sys/audit` (read, sudo) for the
   audit-device check. OpenBao 2.x only accepts audit devices declared in its
   config (`audit "file" "file" { ... }` in `config.hcl`, which
   `infra/openbao/bao-ops.sh` already checks for), not through the API.
3. Create the AppRole: `bao auth enable approle`, then
   `bao write auth/approle/role/dms-core token_policies=dms-documents token_ttl=1h token_max_ttl=24h secret_id_ttl=0`.
   Read the role ID (`bao read auth/approle/role/dms-core/role-id`) and issue a
   secret ID (`bao write -f auth/approle/role/dms-core/secret-id`). Store them as
   `ENCRYPTION_OPENBAO_ROLE_ID` and `ENCRYPTION_OPENBAO_SECRET_ID` in the secret
   store (`secret/dms/production/app`), and add both names to
   `infra/production/secret-keys.txt`. Production already sets
   `ENCRYPTION_OPENBAO_AUTH=approle`.
4. Make OpenBao reachable from the dms-core container (it listens on the host's
   127.0.0.1 today) and set `ENCRYPTION_OPENBAO_ADDR` in `infra/production/.env`, over TLS.
5. Set `ENCRYPTION_ENABLED=true` in `.env`, deploy, then on **Governance >
   Encryption Keys** run "Encrypt existing documents". Grant the
   `de.manage-encryption-keys.*` permissions only to the security officer role.
6. Back up OpenBao (its storage and unseal keys). **Losing the master key
   makes every document unreadable** - that is the point of the design.

## 9. How to add a new storage provider (e.g. Google Cloud Storage)

`AzureBlobStorageAdapter` is a worked example of exactly this process - S3
and FILE_SYSTEM were the only providers this application started with.

1. Add a value to `StorageProvider` (e.g. `GCS`).
2. Add it to the `ck_storage_locations_provider` CHECK constraint in a new
   migration.
3. Implement `ObjectStorageService` in a new adapter class
   (`GoogleCloudStorageAdapter`), registered as a `@Component` - reuse the
   existing `endpoint`/`region`/`bucket`/`basePath`/`credentialRef` columns
   where the new provider's connection details map naturally onto them (see
   `StorageLocation`'s own field-by-field doc comments for how S3 and
   AZURE_BLOB each do this), and fall back to `StorageLocation.configuration`
   (a pre-serialized JSON string) only for something that doesn't fit any
   existing column.
4. Add a `StorageProperties` nested class (e.g. `Gcs`) for the deployment-wide
   defaults, and a matching `dms.storage.gcs.*` block in `application.yml`.
5. Add a branch to `StorageLocationBootstrap`'s provider `switch` so
   `STORAGE_PROVIDER=GCS` can seed the platform-default location too.
6. Nothing else changes. `ObjectStorageServiceFactory` picks up the new bean
   automatically (it autowires `List<ObjectStorageService>`);
   `DocumentService`/`DocumentController` need zero changes.

## 10. Known limitations

- **Per-tenant credentials require an env var + redeploy**, not a
  self-service "register a new tenant's storage via API with instantly-live
  credentials" flow. This was a deliberate tradeoff (see the credential
  section above) given this application has no runtime secrets-fetching
  client to build on yet; building one is future work, not part of this
  change.
- **Deleting is two-step (V62).** `deleteDocument` is a soft delete: the
  document goes to the recycle bin (`/api/v1/recycle-bin`, module RBR), where
  it can be restored for the tenant's retention period
  (`recycle_bin_settings`; default `RECYCLE_BIN_RETENTION_DAYS`, 30). After
  that the hourly sweep (`RecycleBinService#purgeExpired`, one instance at a
  time) purges it, or someone with `rbr.permanently-purge-documents` does it
  straight away. A purge deletes every version's object, its encrypted copy and
  its key row, so no copy of the bytes can be decrypted again. It also removes
  the metadata values and access entries, and keeps the `documents` and
  `document_versions` rows as tombstones (`purged_at`), because scan results,
  import rows and audit events refer to them. A document with a protected
  version (`is_protected`) is never purged.
- **Versions (V62).** `POST /documents/{id}/versions` adds a version through
  the scan gate. Once clean it becomes the current version (`1.0`, `1.1`, …).
  Restoring an older version submits its bytes again as a new `rollback`
  version, so the history only grows.
- **Previews (V64).** Every uploadable type can be previewed in the browser
  except AutoCAD `.dwg`, which needs a commercial converter.
  - The browser renders these itself: PDF, `.docx`, spreadsheets (`xlsx`,
    `xls`, `ods`, `csv`, `tsv`), email (`eml`, `msg`), images (HEIC and TIFF
    included) and text.
  - Office files it can't read (`doc`, `ppt`, `pptx`, `odt`, `odp`, `odg`,
    `rtf`, `vsd`, `vsdx`) are converted to PDF on their first preview by the
    Gotenberg container (`PREVIEW_CONVERTER_URL`; LibreOffice inside).
    `DocumentRenditionService` keeps that PDF beside the original
    (`document_versions.rendition_object_key`), encrypted the same way, and a
    purge deletes it together with the original.
  - With `PREVIEW_CONVERTER_URL` blank, those types are download-only.
  - In production the converter sits on an internal-only Docker network
    (`previews`), because LibreOffice can follow links embedded in a document.
    Its Chromium (URL-fetching) routes are switched off.
- **No `publishedVersionId` workflow is wired up** in this pass - no publish
  endpoint exists yet to drive it.
- **No live-server test coverage for S3/MinIO/SeaweedFS/Azure Blob.** This
  repository has no Testcontainers dependency; `S3ObjectStorageAdapterTest`/
  `AzureBlobStorageAdapterTest` mock the AWS/Azure SDK clients and verify
  request translation, exception translation, and endpoint/credential builder
  wiring, but never talk to a real server. Verify against a real MinIO/
  SeaweedFS/Azure Storage account manually before depending on a specific
  deployment's connectivity.
- **`StorageLocationService` is create/get/list only** - no update or
  deactivate endpoint yet.
