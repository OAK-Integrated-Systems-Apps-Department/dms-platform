# The internal OCR endpoint

How `ocr-worker` reads a document's plaintext bytes, and why it has to ask
`dms-core` rather than read storage itself.

## Why it exists

The worker used to read MinIO directly with the MinIO SDK. That stopped being
possible when the two branches merged: on the main lineage every stored object
goes through `EncryptingObjectStorageService`, whose contract is that *only
ciphertext reaches storage*. A direct read returns an encrypted blob, and a
presigned URL is refused outright for an encrypted object.

The alternative — teaching the worker to decrypt — was rejected. It would put
data-key unwrapping and key-service access (OpenBao, or the local dev key file)
into a second process, which is a much larger thing to get wrong than one
endpoint, and it widens what a compromise of the worker exposes from "one
document at a time" to "every document in every tenant".

So `dms-core` serves the plaintext.

## The endpoints

All under `/internal/v1/ocr`, all `@Hidden` from the OpenAPI document.

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/versions/{versionId}/content` | the document's plaintext bytes, streamed |
| `PUT` | `/versions/{versionId}/output` | the OCR result JSON |
| `PUT` | `/versions/{versionId}/rendition` | the searchable PDF |

Both `PUT`s are idempotent: the object key is derived from the version, so a
retried job overwrites its own artifact rather than leaving an orphan, and the
row upserts on the same basis (`uq_ocr_outputs_version` from V83,
`uq_renditions_version_type` from V76). A worker that retries three times
leaves one object and one row. They return `{"objectKey": "..."}` — `dms-core`
owns the key format, and returning it is what keeps that a single definition
rather than the same string built in Java and again in Python.

## Two credentials, both required

**`X-Internal-Token`** — the shared secret the worker already uses in the other
direction (`dms.ocr.internal-service-token`, the same value as the worker's
`INTERNAL_SERVICE_TOKEN`). Second factor only. On its own it reads nothing.

**`X-Ocr-Job-Token`** — an HMAC-SHA256 grant (`OcrJobTokenCodec`, its own
secret `dms.ocr.job-token-secret`) carrying:

```
purpose | jobId | versionId | tenantId | issuedAt | expiresAt
```

Two purposes:

* **`job`** — minted when the OCR job is queued and delivered on the
  `document.version.ocr.requested` event. Lives `dms.ocr.job-token-minutes`
  (default 30). The endpoint checks the job is still `queued` or `processing`,
  so finishing, failing or cancelling the job revokes the grant before it
  expires.
* **`adhoc`** — minted while serving a review-screen page image or region read,
  for a caller who has already passed their `os.*` permission *and*
  document-level access. There is no job to check, so it lives
  `dms.ocr.adhoc-token-minutes` (default 2) instead. **An adhoc grant may read
  but never write** — it exists so a page can be rendered, not so a document's
  OCR output can be replaced.

The worker never mints a grant. There is no endpoint that hands them out, only
one that honours them.

## What is checked, in order

1. `dms.ocr.enabled` — off answers 404, not 401. Off means the endpoint isn't
   there, not that it refuses you.
2. The static token, compared in constant time.
3. The grant's signature and expiry.
4. The grant's `versionId` equals the path's — a grant for one document cannot
   read another.
5. The version's `tenant_id` equals the grant's. **Tenant comes from the row,
   never a header**: the worker has no user and must not be able to assert
   whose document it is reading.
6. For a `job` grant, the job is live and belongs to that version.
7. The rate limit (20 reads per job; `dms.ocr.max-adhoc-reads-per-version`,
   default 200, per version for adhoc).
8. `VersionContent` — the same code path a user download takes — enforces the
   **scan gate** (`clean` or an administrator's `overridden`), the
   storage-purpose check and the purge check. An unscanned or quarantined file
   is unreachable here exactly as it is everywhere else.

A refusal always answers `401 Not authorised.` with the reason logged and never
returned. Telling a caller which of the eight checks they failed is telling
them how to pass it.

## Auditing

`DOCUMENT_VIEWED` with `via: ocr-worker` is written **once per grant**, on its
first read, not per read. A worker that retries would otherwise turn a retry
storm into an audit storm and bury the real event.

## Not reachable from outside — and how that is kept true

`/internal/**` must never be proxied by nginx or an ingress.

* **Local dev**: `dms-core` publishes `8080` to the host for the frontend, so
  the path *is* reachable from the developer's machine. That is acceptable
  because the dev secrets are dev secrets; it is not the deployment shape.
* **Production** (`infra/production/nginx/dms.conf`): `location /internal/ {
  return 444; }` sits before the catch-all. The worker reaches `dms-core` on
  the container network, which nginx is not in the path of.

### What is actually proven, and by what

| Claim | Proven by | Kind of evidence |
|---|---|---|
| nginx does not forward `/internal/` to `dms-core` | `infra/production/nginx/dms.conf` — no `location /internal/` proxies to `dms_core`, an explicit `return 444` does, and the catch-all `location /` goes to `dms_frontend` | configuration, checked with `nginx -t` |
| the config is syntactically valid | `docker run --rm -v .../dms.conf:/etc/nginx/conf.d/dms.conf:ro nginx:alpine nginx -t` → *syntax is ok* | executed |
| both credentials are required | `OcrInternalContentControllerTest#withoutTheStaticToken_or_withTheWrongOne_nothingIsServed`, `#aMissingForgedOrTamperedJobToken_isRefused` | test |
| a grant cannot be re-pointed at another version | `#aGrantForAnotherVersion_cannotReadThisOne` | test |
| a grant cannot claim another tenant | `#aGrantClaimingAnotherTenant_cannotReadThisTenantsVersion` | test |
| expiry is enforced | `#anExpiredGrant_isRefused` | test |
| finishing the job revokes the grant at once | `#aGrantWhoseJobHasFinished_or_neverExisted_isRefused` | test |
| the scan gate applies | `#anUnscannedVersion_isRefusedByTheScanGate_notServed` | test |
| reads are rate limited | `#aGrantThatKeepsReading_isCutOff` | test |
| `dms.ocr.enabled=false` removes the route | `#withOcrDisabled_theRouteIsNotThere` | test |
| an adhoc grant cannot write artifacts | `#anAdhocGrant_cannotWriteAnArtifact` | test |

**Still configuration, not a test:** nothing executes a request against a
running nginx and asserts the 444. That needs the production stack up, which
this repo has no harness for (there are no Testcontainers in this suite). The
nginx rule is therefore reviewed, not exercised.

That gap is why the endpoint is built to be safe without it. Every row in the
table below the first two is a check inside `dms-core`: if the nginx rule were
deleted tomorrow, an attacker reaching `/internal/v1/ocr` from the internet
would still need the service token **and** a validly signed grant naming the
exact version, for the right tenant, with a live job, inside the expiry — and
would be rate limited while failing.

## Secrets

| Property | Env | Notes |
|---|---|---|
| `dms.ocr.internal-service-token` | `INTERNAL_SERVICE_TOKEN` | shared with the worker; same file both read |
| `dms.ocr.job-token-secret` | `OCR_JOB_TOKEN_SECRET` | **blank refuses every internal read** rather than signing with an empty key, which would be a token anybody could mint |

The grant secret is deliberately *not* shared with impersonation or audit
correlation: a leak or rotation of one must not touch the others.

## On the worker side

`content_client.py` keeps three rules:

1. **Plaintext never touches ordinary disk.** Downloads stream into a file
   under `ocr_tmp_dir` (default `/dev/shm/ocr`), which **must be a tmpfs mount**
   in any real deployment, and that file is deleted when the context manager
   exits — including on failure. Decrypted content on a container's writable
   layer survives the job and lands in image layers and snapshots, which is
   exactly what encryption at rest exists to prevent.
2. **Content is never logged.** Sizes, status codes, version ids and durations
   are; bytes, document filenames and extracted text are not. `ReadGrant.__repr__`
   redacts the token so it cannot reach a traceback.
3. **Nothing is cached across jobs.** Each read is scoped to the grant that
   authorised it.
