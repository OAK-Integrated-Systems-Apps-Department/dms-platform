from __future__ import annotations

from pydantic_settings import BaseSettings, SettingsConfigDict

DMS_EVENTS_EXCHANGE = "oak.dms.events"
DMS_EVENTS_DLX = "oak.dms.events.dlx"

ROUTING_KEY_SCAN_COMPLETED = "document.version.scan.completed"
ROUTING_KEY_OCR_REQUESTED = "document.version.ocr.requested"
ROUTING_KEY_OCR_COMPLETED = "document.version.ocr.completed"
ROUTING_KEY_OCR_FAILED = "document.version.ocr.failed"
ROUTING_KEY_OCR_DEAD_LETTER = "document.version.ocr.dead-letter"

QUEUE_SCAN_COMPLETED = "ocr-worker.q.scan-completed"
QUEUE_OCR_REQUESTED = "ocr-worker.q.ocr-requested"
QUEUE_DEAD_LETTER = "ocr-worker.q.dead-letter"

# Exit status a pool worker uses when its own hard per-document watchdog
# kills it (pipeline.py's `_on_hard_timeout`). Distinct from every other way
# a pool worker can die so the parent can tell "this document timed out and
# the failure row is ALREADY written" apart from "this worker was OOM-killed
# / crashed and the reaper still has to reclaim the lease" -- pebble surfaces
# both as ProcessExpired, differing only in `.exitcode`. 69 is arbitrary but
# outside the 0-2 range a normal interpreter exit uses and outside the
# 128+signal range a signal-kill produces.
OCR_TIMEOUT_EXIT_CODE = 69


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="", env_file=".env", extra="ignore")

    app_name: str = "ocr-worker"
    log_level: str = "INFO"

    # -- Postgres (same instance/database as dms-core; see README for the
    # least-privilege follow-up noted there) --
    db_url: str = "postgresql+psycopg://ocr_worker_user:ocr_worker_pass@localhost:5433/dms_core_db"
    db_pool_size: int = 5

    # -- RabbitMQ --
    rabbitmq_url: str = "amqp://dms:changeme@localhost:5672/"
    # Left unset (the default), prefetch tracks `ocr_worker_processes`
    # exactly -- see `effective_prefetch_count` below for why holding more
    # unacked deliveries than there are pool workers to run them is wrong.
    # An explicit PREFETCH_COUNT still overrides it.
    prefetch_count: int | None = None
    max_delivery_attempts: int = 5

    # -- Process pool (Phase 1 stability -- see docs/ocr/failure-analysis.md
    # #2: one shared PaddleOCR/Docling instance across FastAPI's threadpool
    # AND the consumer's ThreadPoolExecutor caused real, measured contention
    # under concurrency (2.7x slowdown at just 4 concurrent requests)) --
    ocr_worker_processes: int = 2
    # Bounds memory growth from any per-call native-library leak over the
    # container's lifetime; each pool worker is recycled after this many
    # documents.
    ocr_worker_max_tasks_per_process: int = 50
    # Real problem, real fix (see docs/ocr/failure-analysis.md #2/B.2 in the
    # implementation plan): PaddleX defaults to PADDLE_PDX_CPU_NUM_THREADS=10
    # per inference call if unset -- on a 4-core container, every concurrent
    # OCR call already wants 10 threads for itself. threads_per_process =
    # cpu_count // ocr_worker_processes is derived at startup (ocr_engine.py/
    # main.py), not configured directly here.

    # -- Per-document timeout, scaled by page count (see
    # docs/ocr/failure-analysis.md #1: a real 35-page scanned PDF measured
    # 15.6s/page; a single fixed timeout is wrong for both a 1-page and a
    # 50-page document). Defaults include ~60% headroom over that measured
    # rate for slower hardware/contention.
    #
    # Since Phase 1 close-out this budget is computed from the document's
    # REAL page count, read once the file is actually opened inside the
    # pool worker (pipeline.py's DocumentDeadline) -- not from
    # ocr_max_pages. A hung 1-page photo now gets 60 + 1*25 = 85s, not the
    # 1310s worst-case ceiling it used to hold a pool worker for.
    ocr_timeout_base_s: float = 60
    ocr_timeout_per_page_s: float = 25
    # Grace between the SOFT deadline (checked between pages, raises
    # OcrTimeoutError and lets the worker record TIMEOUT and carry on) and
    # the HARD one (a watchdog thread that records TIMEOUT itself and then
    # os._exit()s the pool worker, because a single wedged native
    # PaddleOCR/Docling call can't be interrupted from Python). Sized to
    # cover one page's worth of work plus the failure-commit transaction.
    ocr_timeout_hard_grace_s: float = 30

    # Real measured rate (15.6s/page) makes the production-prompt spec's own
    # OCR_MAX_PAGES=200 default ~52 minutes of one worker process monopolized
    # by a single document -- with only ocr_worker_processes=2 by default,
    # that's half the pool gone for the better part of an hour on one file.
    # 50 pages is ~13 minutes worst-case and comfortably covers realistic
    # single-document uploads; still operator-adjustable per deployment.
    ocr_max_pages: int = 50
    ocr_max_file_size_bytes: int = 100 * 1024 * 1024

    # -- Job leasing / reaper (see V36__ocr_job_leasing.sql) --
    ocr_lease_duration_s: int = 1800
    ocr_reaper_interval_s: int = 30
    # Linear backoff: a job's Nth requeue waits N * this many seconds
    # before it becomes claimable again (enforced by claim_job's
    # next_attempt_at check) -- simple and deterministic given
    # max_delivery_attempts is already small (=5), not worth a more
    # elaborate exponential formula.
    ocr_reaper_backoff_base_s: int = 30

    # -- MinIO / object storage --
    minio_endpoint: str = "localhost:9000"
    minio_access_key: str = "minioadmin"
    minio_secret_key: str = "minioadmin"
    minio_secure: bool = False
    minio_source_bucket: str = "oakdms-documents"
    minio_ocr_output_bucket: str = "oakdms-ocr-output"

    # -- OCR behavior (OS-NFR-001/002/003) --
    min_confidence_threshold: float = 0.95
    ocr_languages: str = "en"
    ocr_render_dpi: int = 200

    # Selectable-field extraction (My Documents upload page): a much lower
    # bar than min_confidence_threshold above -- that one gates the whole
    # document's `flags.low_confidence`/`requires_review`, this one only
    # decides whether one individual requested field's own value is
    # trustworthy enough to mark "extracted" vs. "low_confidence" (the
    # value is returned either way, never dropped -- see extraction.py).
    field_low_confidence_threshold: float = 0.6

    # -- API --
    api_host: str = "0.0.0.0"
    api_port: int = 8090

    # -- Internal-only access (see README "Auth & service exposure") --
    # ocr-worker has no login of its own and is never reachable from
    # outside the docker network (no published host port). This token is
    # defense in depth on top of that network boundary, not an
    # authentication scheme -- dms-core is the only intended caller.
    internal_service_token: str = "changeme-local-dev-token"

    @property
    def effective_prefetch_count(self) -> int:
        """Unset `prefetch_count` means "exactly as many as this worker can
        actually start", i.e. `ocr_worker_processes`.

        Before Phase 1 close-out this defaulted to a flat 4 while
        `ocr_worker_processes` defaulted to 2, so RabbitMQ handed this
        consumer two deliveries it had no pool worker free to run -- they
        sat unacked, invisible to any other consumer, for however long the
        two in-flight documents took (up to ~1000s for a 35-page scan in
        the Phase 1 run). Prefetch above the pool size buys no throughput
        here (the pool, not the queue, is the bottleneck) and only delays
        redistribution, so they are tied together.

        NOTE: each consumer sets QoS on its OWN channel, so with both
        ScanCompletedConsumer and OcrRequestedConsumer connected the
        process-wide ceiling is 2 x this. That is deliberate and currently
        moot -- nothing publishes `document.version.scan.completed` until
        scan-worker exists (see README "Known upstream gap") -- but it is
        the thing to revisit if it ever does.
        """
        return self.prefetch_count if self.prefetch_count is not None else self.ocr_worker_processes


def get_settings() -> Settings:
    return Settings()
