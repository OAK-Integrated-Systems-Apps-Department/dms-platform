# Deploying ocr-worker — proposed service definition

**The production compose is unchanged.** This is a proposal to review before
OCR is deployed anywhere real. OCR has never run in production:
`infra/production/docker-compose.yml` does not define `ocr-worker` at all, and
`dms.ocr.enabled` defaults to `false`, so a deployment that applies none of
this is a deployment with no OCR and no new surface.

One file in `infra/production` **was** changed: `nginx/dms.conf` gained
`location /internal/ { return 444; }`. That is the "prove it is not reachable
through the ingress" item, not a deployment change — nothing routed there
before and nothing routes there now. See [nginx](#nginx) below.

## Proposed service

```yaml
  ocr-worker:
    image: ${REGISTRY}/oakdms-ocr-worker:${TAG}
    restart: unless-stopped
    env_file:
      - ./ocr-worker.env            # OCR_* and DB creds; never committed
    environment:
      # dms-core serves document bytes; there is no object storage here.
      DMS_CORE_BASE_URL: http://dms-core:8080
      # MUST match dms-core's dms.ocr.internal-service-token.
      INTERNAL_SERVICE_TOKEN: ${INTERNAL_SERVICE_TOKEN}
      OCR_WORKER_PROCESSES: ${OCR_WORKER_PROCESSES:-2}
      LOG_LEVEL: INFO
      # Plaintext documents are written here while a job runs. The tmpfs
      # below is what makes that safe - see the mount.
      OCR_TMP_DIR: /dev/shm/ocr
    # No published ports. Nothing outside the compose network may reach this
    # service: it has no login of its own, and its routes are guarded only by
    # the shared internal token.
    networks:
      - internal
    tmpfs:
      # Decrypted document content never reaches a real filesystem. Without
      # this, plaintext lands on the container's writable layer, survives the
      # job, and ends up in image layers and snapshots - precisely what
      # encryption at rest exists to prevent.
      #
      # Size it for the largest document times the number of concurrent jobs,
      # with headroom: 2 processes x a 128 MB cap is 256 MB, so 512 MB.
      - /dev/shm/ocr:size=512m,mode=1700
    volumes:
      # Model weights, so a restart doesn't re-download ~2 GB.
      - ocr_worker_models:/home/oakdms/.paddlex
      - ocr_worker_hf_cache:/home/oakdms/.cache/huggingface
    deploy:
      resources:
        limits:
          # OCR is CPU-bound and memory-hungry: PaddleOCR plus Docling's
          # layout model is ~2.5 GB resident per process before a document is
          # opened. 2 processes x ~3 GB plus headroom.
          memory: 8G
          cpus: "4.0"
        reservations:
          memory: 6G
    healthcheck:
      test: ["CMD", "curl", "-fsS", "http://localhost:8090/readyz"]
      # Model warm-up is slow and deliberate (eager, at startup, so the first
      # real document doesn't pay for it). start_period must exceed it or the
      # orchestrator will kill the container mid-warm-up, forever.
      start_period: 300s
      interval: 30s
      timeout: 10s
      retries: 3
    depends_on:
      postgres:
        condition: service_healthy
      rabbitmq:
        condition: service_healthy
      dms-core:
        condition: service_healthy
```

## nginx

Already applied — this is the one change outside `infra/production`'s compose
that was made, because item 1 asked for the isolation to be proven rather than
asserted:

```nginx
location /internal/ { return 444; }
```

It sits **before** the catch-all `location /`, so a later broadening of the
catch-all cannot expose it. It is belt and braces:
nothing proxies `/internal/` to `dms_core` and the catch-all `location /` goes
to the frontend, so an outside request already 404s on Next.js — but that is
an emergent property of block ordering, and an explicit rule cannot be lost by
reordering. What is still not done is a test that fires a request at a running
nginx and asserts the 444; see the evidence table in
`docs/ocr/internal-endpoint.md` for exactly what is proven and how.

## What dms-core needs set

| Property | Why |
|---|---|
| `dms.ocr.enabled=true` | off by default; nothing else matters until this is on |
| `dms.ocr.job-token-secret` | **blank refuses every internal read.** Its own secret — not shared with impersonation or audit correlation |
| `dms.ocr.internal-service-token` | must equal the worker's `INTERNAL_SERVICE_TOKEN` |
| `dms.ocr.max-queued-per-tenant` | backpressure; default 2000 |
| `dms.ocr.max-per-import-batch` | per-batch OCR cap for bulk import; default 500. Files past it are imported normally, with no OCR |
| `dms.ocr.job-token-minutes` | life of a job read grant; default 30 |
| `dms.ocr.adhoc-token-minutes` | life of a review-screen read grant; default 2 |
| `dms.ocr.backlog-sweep-interval-ms` | how often held-back OCR is retried; default 30000 |
| `dms.ocr.page-image-cache-bytes` | review page-image cache budget, in bytes; default 64 MiB. In-process only — see `docs/ocr/page-images.md` |
| `dms.ocr.page-image-cache-enabled` | default true; turn off to diagnose a stale-image report without a redeploy |

### Queues

Two, not one:

| Routing key | Queue | Who publishes |
|---|---|---|
| `document.version.ocr.requested` | `ocr-worker.q.ocr-requested` | interactive uploads |
| `document.version.ocr.requested.bulk` | `ocr-worker.q.ocr-requested-bulk` | bulk import |

Both are declared and bound by the worker itself (`consumer.py`), so there is
nothing to create by hand. The bulk consumer runs at `prefetch=1` on purpose:
both consumers feed the same process pool, so the queue split alone would not
stop a 5,000-file import occupying every slot while someone waits on a scan.

## Sizing notes, honestly

The memory and CPU figures above are the shape of the local stack scaled up,
not measurements from a production load test — that hasn't been run. The one
number taken from a real measurement is the warm-up time behind
`start_period`, which the local stack shows is minutes, not seconds, and which
is the setting most likely to cause a mysterious restart loop if it is cut.

## Rollback

Set `dms.ocr.enabled=false` and stop the worker. Uploads continue to scan and
land exactly as before; the Scan Documents and review screens disappear from
the sidebar because the frontend probes the feature rather than assuming it.
No migration is reversed and no document is affected — OCR artifacts already
written stay where they are, encrypted, and are removed with their document by
the ordinary purge path.
