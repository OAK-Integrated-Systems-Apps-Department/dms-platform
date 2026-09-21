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
StorageLocationResolver          +----------------+----------------+
        |                        |                                 |
        v                  S3ObjectStorageAdapter        FileSystemStorageAdapter
StorageLocation (DB row)         |                                 |
                                  v                                 v
                         AWS SDK v2 S3Client                java.nio.file
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

## 2. Adapter / Strategy / Factory responsibilities

- **Adapter** (`S3ObjectStorageAdapter`, `FileSystemStorageAdapter`): each
  wraps a real storage mechanism (AWS SDK v2, `java.nio.file`) behind the
  common `ObjectStorageService` interface. `S3ObjectStorageAdapter` is the one
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
§7 - Security):

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

## 7. Tenant-specific storage & security

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
  `S3_SECRET_KEY`). This is the same pattern every other secret in this
  application already uses - environment variables populated from the
  OpenBao-encrypted `infra/secrets/*.enc.env` file before the container
  starts (see `scripts/openbao-decrypt-secrets.sh`) - there is no runtime
  secrets-fetching client in this codebase to integrate with instead.
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

## 8. How to add a new storage provider (e.g. Azure Blob)

1. Add a value to `StorageProvider` (e.g. `AZURE_BLOB`).
2. Add it to the `ck_storage_locations_provider` CHECK constraint in a new
   migration.
3. Implement `ObjectStorageService` in a new adapter class
   (`AzureBlobStorageAdapter`), registered as a `@Component` - use
   `StorageLocation.configuration` (a pre-serialized JSON string) for any
   provider-specific connection detail that doesn't fit the existing
   `endpoint`/`region`/`bucket`/`basePath` columns (e.g. Azure's connection-
   string shape).
4. Nothing else changes. `ObjectStorageServiceFactory` picks up the new bean
   automatically (it autowires `List<ObjectStorageService>`);
   `DocumentService`/`DocumentController` need zero changes.

## 9. Known limitations

- **Per-tenant credentials require an env var + redeploy**, not a
  self-service "register a new tenant's storage via API with instantly-live
  credentials" flow. This was a deliberate tradeoff (see the credential
  section above) given this application has no runtime secrets-fetching
  client to build on yet; building one is future work, not part of this
  change.
- **No physical-object purge on document delete** - `deleteDocument` is a
  soft delete only, matching every other entity's `is_deleted` convention in
  this codebase. The physical object is deliberately left in place (audit-
  trail/legal-hold concerns argue against purging it automatically); a
  separate administrative purge capability is future work.
- **No `publishedVersionId` workflow is wired up** in this pass - no publish
  endpoint exists yet to drive it.
- **No live-server test coverage for S3/MinIO/SeaweedFS.** This repository
  has no Testcontainers dependency; `S3ObjectStorageAdapterTest` mocks the
  AWS SDK client and verifies request translation, exception translation,
  and endpoint/path-style builder wiring, but never talks to a real server.
  Verify against a real MinIO/SeaweedFS instance manually before depending on
  a specific deployment's connectivity.
- **`StorageLocationService` is create/get/list only** - no update or
  deactivate endpoint yet.
