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
    prefetch_count: int = 4
    worker_pool_size: int = 4
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
    ocr_timeout_base_s: int = 60
    ocr_timeout_per_page_s: int = 25

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


def get_settings() -> Settings:
    return Settings()
