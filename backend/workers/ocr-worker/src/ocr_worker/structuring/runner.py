"""When and where structured extraction runs.

Called by OcrPipeline.process_version only AFTER the OCR transaction has
committed, so a slow or failing model can never roll back, delay, or fail
OCR itself. The result is its own MinIO artifact next to the OCR output
(storage.structured_output_key); a `pending` placeholder is written first
in async mode so a poller can tell "queued" from "never ran".

One bounded executor (LLM_MAX_CONCURRENCY, default 1) for the whole
process: the model is loaded once in the model server and every document
queues for it, instead of several documents competing for one small GPU.
"""
from __future__ import annotations

import logging
import threading
import uuid
from collections.abc import Callable
from concurrent.futures import ThreadPoolExecutor

from ..storage import ObjectStorage, structured_output_key
from .errors import StructuringError
from .field_merge import POLICIES as FIELD_POLICIES
from .grounding import POLICIES
from .llm_client import build_llm_client
from .prompts import TargetField
from .service import StructuredExtractionService, validated_result

logger = logging.getLogger(__name__)

MODES = ("off", "async", "sync")


class NoOcrOutput(Exception):
    pass


class StructuredExtractionRunner:
    def __init__(self, settings, storage: ObjectStorage, service: StructuredExtractionService):
        mode = settings.llm_extraction_mode.strip().lower()
        if mode not in MODES:
            raise ValueError(f"LLM_EXTRACTION_MODE must be one of {MODES}, got {settings.llm_extraction_mode!r}")
        if settings.llm_grounding_policy not in POLICIES:
            raise ValueError(f"LLM_GROUNDING_POLICY must be one of {POLICIES}, got {settings.llm_grounding_policy!r}")
        if settings.llm_field_policy not in FIELD_POLICIES:
            raise ValueError(f"LLM_FIELD_POLICY must be one of {FIELD_POLICIES}, got {settings.llm_field_policy!r}")
        self._mode = mode
        self._settings = settings
        self._storage = storage
        self._service = service
        self._executor: ThreadPoolExecutor | None = None
        self._executor_lock = threading.Lock()

    @property
    def mode(self) -> str:
        return self._mode

    @property
    def enabled(self) -> bool:
        return self._mode != "off"

    def _pool(self) -> ThreadPoolExecutor:
        with self._executor_lock:
            if self._executor is None:
                self._executor = ThreadPoolExecutor(max_workers=max(1, self._settings.llm_max_concurrency), thread_name_prefix="llm-structuring")
            return self._executor

    # ------------------------------------------------------------------
    @property
    def has_client(self) -> bool:
        return self._service.client is not None

    def after_ocr(
        self,
        *,
        version_id: uuid.UUID,
        extracted_text_ref: str | None,
        pages: list[dict],
        targets: list[TargetField] | None = None,
        on_result: Callable[[dict], dict] | None = None,
    ) -> dict | None:
        """Returns the structured result in sync mode, None otherwise.
        `on_result(result)` runs once structuring has finished (in the
        background in async mode) and before the result is stored; the keys
        it returns are added to the stored result."""
        if not self.enabled or not extracted_text_ref:
            return None
        if self._mode == "sync":
            return self.run(version_id=version_id, extracted_text_ref=extracted_text_ref, pages=pages, targets=targets, on_result=on_result)
        self._write_pending(version_id, extracted_text_ref)
        self._pool().submit(self._run_in_background, version_id, extracted_text_ref, pages, targets, on_result)
        return None

    def run(
        self,
        *,
        version_id: uuid.UUID,
        extracted_text_ref: str,
        pages: list[dict],
        targets: list[TargetField] | None = None,
        on_result: Callable[[dict], dict] | None = None,
    ) -> dict:
        result = self._service.extract(pages, document_version_id=str(version_id), ocr_output_ref=extracted_text_ref, targets=targets)
        if on_result is not None:
            try:
                result.update(on_result(result) or {})
            except Exception:  # noqa: BLE001 - the hand-off logs its own failures; the result is still stored
                logger.exception("structured-result hand-off failed for version %s", version_id)
        try:
            self._storage.put_ocr_output_json(structured_output_key(extracted_text_ref), result)
        except Exception:  # noqa: BLE001 - the caller still gets the result; the store failure is logged
            logger.exception("could not store structured result for version %s", version_id)
        return result

    def rerun(self, *, tenant_id: uuid.UUID, version_id: uuid.UUID, wait: bool) -> dict:
        """Structures the version's newest stored OCR output again (e.g. after
        the model was pulled, or to try another LLM_MODEL). No OCR re-run.

        FOLLOW-UP: "newest" is by MinIO upload time among every OCR output
        under the version's prefix, and zone / rubber-band runs store their
        (partial, cropped) output under the same prefix -- so a rubber-band
        request made after the full-document run would be the one
        re-structured. Not fixed here because no caller in this repo issues
        zone/rubber-band requests yet (dms-core only calls /ocr/process). A
        fix needs the run kind recorded -- e.g. a `kind` field in the OCR
        output JSON or an ocr_outputs column -- and this lookup filtering
        on it.
        """
        ocr_key = self._storage.latest_ocr_output_key(tenant_id, version_id)
        if ocr_key is None:
            raise NoOcrOutput(f"no OCR output stored for version {version_id}; run OCR first")
        if wait:
            return self.run(version_id=version_id, extracted_text_ref=ocr_key, pages=self._load_pages(ocr_key))
        pending = self._write_pending(version_id, ocr_key)
        self._pool().submit(self._rerun_in_background, version_id, ocr_key)
        return pending

    def latest(self, *, tenant_id: uuid.UUID, version_id: uuid.UUID) -> dict | None:
        key = self._storage.latest_ocr_output_key(tenant_id, version_id, structured=True)
        return self._storage.get_ocr_output_json(key) if key else None

    def health(self) -> dict:
        info = {"mode": self._mode, **self._service.model_info}
        client = self._service.client
        if client is None:
            error = self._service.client_error
            return {**info, "reachable": False, "model_available": False, "error": error.to_dict() if error else None}
        try:
            return {**info, **client.health()}
        except StructuringError as exc:
            return {**info, "reachable": False, "model_available": False, "error": exc.to_dict()}

    def warmup_in_background(self) -> None:
        client = self._service.client
        if not self.enabled or client is None:
            return

        def _warm():
            try:
                client.warmup()
                logger.info("LLM %s loaded and pinned by the model server", self._settings.llm_model)
            except StructuringError as exc:
                logger.warning("LLM warmup failed: %s", exc.code)

        threading.Thread(target=_warm, name="llm-warmup", daemon=True).start()

    def shutdown(self) -> None:
        # FOLLOW-UP: a version whose requested fields are waiting on the LLM
        # is held at ocr_status 'processing' until on_result runs
        # (pipeline._apply_llm_fields). cancel_futures drops queued runs, and
        # a crash/restart loses in-flight ones, so those versions stay
        # 'processing' for good and the upload form polls forever. Not fixed
        # here: needs a startup sweep that completes versions left
        # 'processing' with committed OCR output (their rule-based field rows
        # are already written), or a staleness timeout on dms-core's side.
        if self._executor is not None:
            self._executor.shutdown(wait=False, cancel_futures=True)

    # ------------------------------------------------------------------
    def _write_pending(self, version_id: uuid.UUID, extracted_text_ref: str) -> dict:
        pending = validated_result({
            "status": "pending",
            "document_version_id": str(version_id),
            "ocr_output_ref": extracted_text_ref,
            "model": self._service.model_info,
        })
        self._storage.put_ocr_output_json(structured_output_key(extracted_text_ref), pending)
        return pending

    def _load_pages(self, ocr_key: str) -> list[dict]:
        return self._storage.get_ocr_output_json(ocr_key).get("pages") or []

    def _run_in_background(self, version_id: uuid.UUID, extracted_text_ref: str, pages: list[dict], targets=None, on_result=None) -> None:
        try:
            self.run(version_id=version_id, extracted_text_ref=extracted_text_ref, pages=pages, targets=targets, on_result=on_result)
        except Exception:  # noqa: BLE001 - a background failure must be logged, never raised into the pool
            logger.exception("background structured extraction failed for version %s", version_id)

    def _rerun_in_background(self, version_id: uuid.UUID, ocr_key: str) -> None:
        try:
            self.run(version_id=version_id, extracted_text_ref=ocr_key, pages=self._load_pages(ocr_key))
        except Exception:  # noqa: BLE001
            logger.exception("background structured re-run failed for version %s", version_id)


def build_structuring_runner(settings, storage: ObjectStorage) -> StructuredExtractionRunner:
    client, error = None, None
    if settings.llm_extraction_mode.strip().lower() != "off":
        try:
            client = build_llm_client(settings)
        except StructuringError as exc:
            # Loud in the log, recorded on every result -- but OCR keeps
            # working; a bad LLM setting must not take OCR down with it.
            logger.error("structured extraction cannot use the configured LLM: %s", exc)
            error = exc
    return StructuredExtractionRunner(settings, storage, StructuredExtractionService(settings, client, error))
