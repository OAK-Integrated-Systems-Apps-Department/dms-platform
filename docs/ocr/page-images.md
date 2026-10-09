# Review page images: on demand, cached in memory, never persisted

## The shape

A page image is rendered by ocr-worker when the review screen asks for it,
and **nothing writes one to disk or to object storage**.

```
browser → dms-core  GET /api/v1/documents/{id}/page-image?page=N
            ├─ os.* permission (@PreAuthorize)
            ├─ document-level folder access (findVisible, view)
            ├─ PageImageCache  ─── hit ──→ serve
            └─ miss → OcrWorkerClient.fetchPageImage
                        ├─ mints a short-lived adhoc read grant
                        └─ ocr-worker renders, reading the document back
                           through /internal/v1/ocr
```

## The cache

`PageImageCache` (`backend/dms-core/.../document/PageImageCache.java`).

| Property | Value |
|---|---|
| Where | in the dms-core process's heap |
| Key | `(tenantId, versionId, page, dpi)` |
| Bound | **bytes**, `dms.ocr.page-image-cache-bytes`, default 64 MiB |
| Eviction | LRU (`LinkedHashMap` in access order) |
| Switch | `dms.ocr.page-image-cache-enabled`, default true |
| Purge hook | `PageImageCache#invalidate(versionId)`, called from `RecycleBinService#purge` |

### Why caching is correct here

A version's bytes are immutable — a new upload is a new version, with a new
id. So a rendered page *of a version* is immutable too, and a cached copy
cannot go stale. This is what makes the cache a latency fix rather than a
correctness risk.

### Why in memory, and not Redis or object storage

A page image **is** the document, as a picture. Everything this system stores
is encrypted at rest precisely so a readable copy never sits outside that
guarantee.

- **Redis** in this deployment is not encrypted and is shared between
  services. A page image there is a plaintext copy of a document in a store
  that nothing in the purge or key-shredding path reaches.
- **Object storage** would be encrypted correctly, but then something has to
  know those objects exist in order to delete them when the document is
  deleted — which is the tracking table and purge hook that persisting was
  deferred until. See below.

In-process memory has neither problem: a cached page dies with the process,
and `invalidate` removes it the moment its document is purged.

### Why bytes and not a count of entries

A 300-DPI A3 page and a 72-DPI thumbnail differ by two orders of magnitude.
"Keep 200 entries" bounds a number nobody cares about; it does not bound
memory. An image worth more than a quarter of the budget is served but not
cached, so one oversized page cannot evict everything else to store itself.

### Tenant in the key

A version id is already globally unique, so the tenant is redundant today. It
is in the key anyway because it costs nothing, and because the one bug in this
class that would actually matter is a cache hit crossing a tenant boundary.
`PageImageCacheTest#twoTenantsNeverShareAnEntry` fails loudly if that ever
becomes possible.

## If page images are persisted later

Two things must land in the same change, not after it:

1. **A tracking table.** `page_images(version_id, page, dpi, storage_location_id,
   object_key, created_at)`, so the purge path can find every object by
   explicit key. (`RecycleBinService` deletes by explicit key, never by
   prefix — see `docs/ocr/export-import-ocr-columns.md` and
   `OcrArtifactWriter#deleteFor` for why a prefix sweep is the wrong tool: it
   would take sibling versions' artifacts with it.)
2. **An explicit purge hook**, called from `RecycleBinService#purge` alongside
   `OcrArtifactWriter#deleteFor` and `PageImageCache#invalidate`, and covered
   by an assertion in
   `DocumentUploadIT#aPurge_destroysTheOcrOutputAndSearchablePdfAndEveryRowDerivedFromThem`.

Persisting without both means a permanently deleted document stays readable as
a set of pictures that nothing knows how to delete.

## Measuring review latency

`scripts/dev/review_page_latency.py <documentId>` measures the real round trip
over 50 pages, reporting the cold (rendered) and warm (cached) paths
separately.

It is a script and not a test because a render is ocr-worker doing real work
with multi-gigabyte models: this repo has no Testcontainers harness, and a
test that is skipped on every machine is a worse answer than a script that
states what it needs.

> **Not yet run.** Producing the number requires the full local stack up
> (postgres, rabbitmq, seaweedfs, clamav, dms-core, ocr-worker with warm model
> caches) and a 50-page PDF uploaded and OCR'd. Standing that stack up runs
> Flyway, and the instruction for this work was not to touch `dms_core_db` —
> so the measurement is pending a run against a throwaway database. The
> harness and its method are in the repo; the figure is not.
>
> Read it as: the cache's effect is proven by `PageImageCacheTest` (the same
> page renders once, not five times); the absolute millisecond figures are
> not yet measured.
