from __future__ import annotations

from pydantic_settings import BaseSettings, SettingsConfigDict

DMS_EVENTS_EXCHANGE = "oak.dms.events"
DMS_EVENTS_DLX = "oak.dms.events.dlx"

ROUTING_KEY_SCAN_COMPLETED = "document.version.scan.completed"
ROUTING_KEY_OCR_COMPLETED = "document.version.ocr.completed"
ROUTING_KEY_OCR_FAILED = "document.version.ocr.failed"
ROUTING_KEY_OCR_DEAD_LETTER = "document.version.ocr.dead-letter"

QUEUE_SCAN_COMPLETED = "ocr-worker.q.scan-completed"
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

    # -- Upload validation (validation.py) -- checked before Docling or
    # PaddleOCR ever see the bytes: declared mime type vs. real file
    # signature, size/page limits, corrupted/encrypted files.
    max_document_bytes: int = 50 * 1024 * 1024
    max_document_pages: int = 500

    # -- Structured JSON extraction via a local LLM (structuring/) --
    # "off": never call the model. "async": after OCR commits, structure the
    # document on a background thread and store the result as its own MinIO
    # artifact (the OCR response/latency is unchanged). "sync": same, but
    # block and also return it under `structured` in POST /ocr/process's
    # response -- dev/testing only, a 7B model on CPU can exceed dms-core's
    # 60s OCR read timeout.
    llm_extraction_mode: str = "off"
    # "ollama" (/api/chat) or "openai_compatible" (/v1/chat/completions --
    # llama.cpp server, vLLM, LM Studio, LocalAI). Both are local runtimes;
    # nothing here talks to a hosted API.
    llm_provider: str = "ollama"
    llm_base_url: str = "http://localhost:11434"
    # The only place the model is named -- swap models with LLM_MODEL alone.
    llm_model: str = "qwen2.5:7b-instruct"
    # Only for an openai_compatible server started with its own --api-key.
    llm_api_key: str = ""
    llm_timeout_seconds: float = 600.0
    llm_temperature: float = 0.0
    llm_seed: int = 42
    # Model context window requested per call (Ollama num_ctx). Ollama
    # silently truncates prompts longer than this, so chunking (below) is
    # sized from it rather than trusting the server to refuse.
    llm_context_tokens: int = 8192
    llm_max_output_tokens: int = 2048
    # 0 = derive from llm_context_tokens - llm_max_output_tokens - prompt.
    llm_chunk_max_tokens: int = 0
    # Conservative chars-per-token estimate used to size chunks without
    # shipping a tokenizer for every possible model.
    llm_chars_per_token: float = 3.0
    # A document needing more chunks than this is structured for its first
    # llm_max_chunks chunks only, and the result is marked partial.
    llm_max_chunks: int = 20
    # Extra attempts per chunk after invalid/incomplete JSON.
    llm_max_retries: int = 1
    # How long Ollama keeps the model loaded between documents.
    llm_keep_alive: str = "30m"
    # Constrain decoding to the output JSON Schema (Ollama `format`, OpenAI
    # `response_format`). Disable only for a server that rejects schemas.
    llm_use_json_schema: bool = True
    # Hallucination guard: every extracted value must appear in the source
    # text. "nullify" replaces an unsupported value with null, "flag" keeps
    # it but lists it in warnings, "off" skips the check.
    llm_grounding_policy: str = "nullify"
    llm_include_bboxes: bool = True
    # OCR lines below this confidence are marked "?" in the model input.
    llm_low_confidence_line_threshold: float = 0.8
    # Documents structured concurrently. One is right for a single local
    # model server -- extra concurrency just queues inside the server.
    llm_max_concurrency: int = 1
    # Load the model into memory at startup so the first upload doesn't pay
    # the model load time.
    llm_warmup_on_startup: bool = False
    # Security: LLM_BASE_URL must resolve to loopback/private/docker-service
    # addresses unless the host is listed here (comma-separated) or
    # llm_allow_non_local is set -- a misconfigured URL must never ship
    # confidential documents to an external API.
    llm_allowed_hosts: str = ""
    llm_allow_non_local: bool = False


def get_settings() -> Settings:
    return Settings()
