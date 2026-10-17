from __future__ import annotations

import logging
import multiprocessing
import os
import uuid
from typing import TYPE_CHECKING

from pebble import ProcessPool, ProcessFuture

from .config import Settings

if TYPE_CHECKING:
    from .extraction import FieldSpec
    from .pipeline import OcrPipeline, PageRender, ProcessingOutcome

logger = logging.getLogger(__name__)

# One OcrPipeline per pool-worker PROCESS (set by _pool_worker_init, read by
# the _run_* task functions below) -- never the parent's pipeline. Safe as a
# plain module global specifically because `spawn` gives each pool worker
# its own fresh interpreter and address space; nothing here is shared
# across processes.
_pipeline: "OcrPipeline | None" = None


def _pool_worker_init(settings: Settings, threads_per_process: int, ready_counter) -> None:
    """pebble.ProcessPool's `initializer`, run once per pool-worker process
    before it accepts any task.

    CPU thread-capping env vars MUST be set before paddleocr/numpy/cv2 are
    ever imported (native BLAS/OpenMP thread pools read them once at
    library load -- see docs/ocr/failure-analysis.md's real, measured
    PADDLE_PDX_CPU_NUM_THREADS=10-per-process finding). That is why this
    function's own imports of .layout/.ocr_engine/.pipeline are deferred
    to its body rather than module scope: on `spawn`, pebble/multiprocessing
    imports this module (to resolve this function by qualified name)
    BEFORE calling it, so anything imported at THIS module's top level
    would already be too late.
    """
    os.environ["PADDLE_PDX_CPU_NUM_THREADS"] = str(threads_per_process)
    os.environ["OMP_NUM_THREADS"] = str(threads_per_process)
    os.environ["MKL_NUM_THREADS"] = str(threads_per_process)

    # `spawn` gives this process a fresh interpreter -- main.py's own
    # configure_logging() call never ran here, so without this,
    # logging.basicConfig's default (root logger, WARNING) would silently
    # drop this function's own INFO-level warm-up confirmation below AND,
    # far more importantly, every log line pipeline.py/repository.py emit
    # while actually processing a real document in this same process
    # (verified live: the "warmed up" line below never appeared in
    # `docker logs` before this fix, even though warm-up had genuinely
    # completed).
    from .logging_config import configure_logging

    configure_logging(settings.log_level)

    from .db import make_session_factory
    from .layout import LayoutEngine
    from .ocr_engine import PaddleOcrEngine
    from .orientation import NullOrientationClassifier, PaddleDocOrientationClassifier
    from .pipeline import OcrPipeline
    from .content_client import DocumentContentClient

    global _pipeline
    layout_engine = LayoutEngine(
        table_timeout_per_page_s=settings.ocr_table_timeout_per_page_s,
        normalize_rotation=settings.ocr_normalize_page_rotation,
    )
    ocr_engine = PaddleOcrEngine()
    orientation_classifier = (
        PaddleDocOrientationClassifier()
        if settings.ocr_orientation_enabled
        else NullOrientationClassifier()
    )
    session_factory = make_session_factory(settings)

    def load_known_vendors(tenant_id):
        """Vendors this tenant's reviewers have already confirmed.

        Its own short session, opened and closed per document: the
        pipeline calls this during inference, which by design runs with no
        transaction held (claim / run / commit, Phase 1). Reusing the
        claim's session would reopen one for the whole inference and undo
        that.
        """
        from .db import session_scope
        from .repository import OcrRepository

        with session_scope(session_factory) as session:
            return OcrRepository(session).known_vendor_names(tenant_id)

    _pipeline = OcrPipeline(
        settings=settings,
        session_factory=session_factory,
        content=DocumentContentClient(settings),
        layout_engine=layout_engine,
        ocr_engine=ocr_engine,
        orientation_classifier=orientation_classifier,
        known_vendors_loader=load_known_vendors,
    )

    # Eager warm-up: pay the real model-construction cost now, once, not on
    # this process's first real document.
    layout_engine.warm_up()
    ocr_engine.warm_up(lang=settings.ocr_languages)
    # Same reasoning, and the same /readyz gate: the orientation model is
    # small (1 MiB marginal RSS, measured) but it still downloads its
    # weights on first use, and paying that inside the first real
    # document's deadline is exactly what eager warm-up exists to avoid.
    orientation_classifier.warm_up()

    with ready_counter.get_lock():
        ready_counter.value += 1
    logger.info(
        "ocr-worker pool worker pid=%d warmed up (threads_per_process=%d)", os.getpid(), threads_per_process
    )


def _run_process_version(
    version_id: uuid.UUID,
    correlation_id: uuid.UUID | None,
    requested_fields: "list[FieldSpec] | None",
    resolve_fields_from_db: bool = False,
    read_token: str | None = None,
) -> "ProcessingOutcome":
    assert _pipeline is not None, "pool worker task ran before its initializer completed"
    return _pipeline.process_version(
        version_id,
        correlation_id=correlation_id,
        requested_fields=requested_fields,
        resolve_fields_from_db=resolve_fields_from_db,
        read_token=read_token,
    )


def _run_process_zone(
    version_id: uuid.UUID, zone_names: list[str] | None, correlation_id: uuid.UUID | None
) -> "ProcessingOutcome":
    assert _pipeline is not None, "pool worker task ran before its initializer completed"
    return _pipeline.process_zone_request(version_id, zone_names, correlation_id=correlation_id)


def compute_threads_per_process(cpu_count: int, worker_processes: int) -> int:
    """threads_per_process = cpu_count // worker_processes, floored to 1 --
    PaddleX otherwise defaults to PADDLE_PDX_CPU_NUM_THREADS=10 per
    inference call regardless of how many processes are competing for the
    same cores (see docs/ocr/failure-analysis.md's real, measured finding).
    A pure function so the derivation is unit-testable without spawning
    real pool-worker processes.
    """
    return max(1, cpu_count // worker_processes)


def compute_task_timeout_s(settings: Settings) -> float:
    """The OUTERMOST backstop only -- see OcrProcessPool.task_timeout_s.

    The real per-document budget is `deadline.DocumentDeadline`, armed
    inside the pool worker from the document's actual page count. This
    value is sized to always be larger than the largest budget that
    watchdog can possibly arm (`ocr_max_pages` worth) plus the grace it
    waits and the time it needs to write its own failure record, so pebble
    only ever fires when the watchdog thread itself never got scheduled.
    If pebble wins, no TIMEOUT row is written and the reaper reclaims the
    lease as `WORKER_CRASH`, which is the correct description of that case.
    """
    return longest_in_worker_hard_deadline_s(settings) + settings.ocr_pool_ceiling_grace_s


def longest_in_worker_hard_deadline_s(settings: Settings) -> float:
    """The largest deadline the in-worker watchdog can possibly arm.

    Armed from the document's REAL page count, so the largest case is a
    document at `ocr_max_pages`. Separated out because the pool ceiling is
    defined *relative to this* -- `ceiling = this + pool grace` -- and
    writing that relationship as one arithmetic expression hid it. It was
    previously spelled `base + max_pages*per_page + 2*grace`, which is the
    same number by coincidence of using the same grace twice and gave no
    hint that the second `grace` meant something different from the first.
    """
    return (
        settings.ocr_timeout_base_s
        + settings.ocr_max_pages * settings.ocr_timeout_per_page_s
        + settings.ocr_timeout_hard_grace_s
    )


def _run_process_rubber_band(
    version_id: uuid.UUID,
    page: int,
    box: tuple[float, float, float, float],
    correlation_id: uuid.UUID | None,
) -> "ProcessingOutcome":
    assert _pipeline is not None, "pool worker task ran before its initializer completed"
    return _pipeline.process_rubber_band_request(version_id, page, box, correlation_id=correlation_id)


def _run_render_page(
    version_id: uuid.UUID, page: int, dpi: int | None, read_token: str | None = None
) -> "PageRender":
    assert _pipeline is not None, "pool worker task ran before its initializer completed"
    return _pipeline.render_page_png(version_id, page, dpi, read_token=read_token)


def _run_preview_rubber_band(
    version_id: uuid.UUID, page: int, box: tuple[float, float, float, float],
    read_token: str | None = None
) -> dict:
    assert _pipeline is not None, "pool worker task ran before its initializer completed"
    return _pipeline.preview_rubber_band(version_id, page, box, read_token=read_token)



class OcrProcessPool:
    """Owns the real `pebble.ProcessPool` shared by BOTH the RabbitMQ
    consumer and the synchronous /ocr/* API endpoints -- docs/ocr/
    failure-analysis.md #2's confirmed contention was specifically from
    ONE pipeline/model instance shared across the FastAPI threadpool AND
    the consumer's ThreadPoolExecutor, so both dispatch paths must go
    through this same pool rather than one of them keeping a separate
    in-process pipeline.

    Each pool worker builds its OWN full OcrPipeline (own LayoutEngine,
    own PaddleOcrEngine, own session_factory, own storage client) inside
    `_pool_worker_init`, never sharing the parent's -- and `spawn`, not
    `fork`, since PaddlePaddle/torch are not fork-safe.
    """

    def __init__(self, settings: Settings):
        self._settings = settings
        ctx = multiprocessing.get_context("spawn")
        self._ready_counter = ctx.Value("i", 0)
        self._worker_count = settings.ocr_worker_processes
        threads_per_process = compute_threads_per_process(os.cpu_count() or 1, settings.ocr_worker_processes)
        self._pool = ProcessPool(
            max_workers=settings.ocr_worker_processes,
            max_tasks=settings.ocr_worker_max_tasks_per_process,
            initializer=_pool_worker_init,
            initargs=(settings, threads_per_process, self._ready_counter),
            context=ctx,
        )
        # Verified live: pebble.ProcessPool does NOT start any worker
        # processes at construction -- it stays in PoolStatus.CREATED, and
        # workers (running the initializer) are only spawned the first
        # time something checks pool status, via `.active` or the first
        # `.schedule()` call. Without this line, "eager" warm-up would
        # silently not happen at all: the pool would sit idle until the
        # first real request, which would then pay the full cold model-load
        # cost synchronously and /readyz would report ready long before any
        # worker actually existed. `.active` is pebble's own public,
        # documented property -- this isn't reaching into a private method.
        self._pool.active
        logger.info(
            "OcrProcessPool starting: %d workers x %d threads/process (cpu_count=%s), max_tasks_per_process=%d",
            self._worker_count, threads_per_process, os.cpu_count(), settings.ocr_worker_max_tasks_per_process,
        )
        self.warn_on_misconfigured_lease()

    def warn_on_misconfigured_lease(self) -> str | None:
        """The lease must outlast the longest a document can legitimately
        take, or the reaper stops being a backstop and starts competing
        with the worker: the lease expires mid-inference, the reaper
        requeues, the retry starts the same document from scratch, and it
        can livelock until max_delivery_attempts dead-letters a document
        that was never actually failing.

        Not hypothetical -- provoked during Phase 1 close-out by an
        overlay that shortened the lease to 60s while leftover 35-page
        scanned fixtures (~1000s each) were still queued. They cycled
        indefinitely and saturated the pool. Cheap to detect at startup,
        genuinely confusing to diagnose at runtime, so it is checked here
        and logged rather than left to be rediscovered.

        A warning, not a refusal to start: the fault-injection overlays in
        infra/local deliberately violate it, and an operator who has
        decided to is entitled to.
        """
        longest_document_s = compute_task_timeout_s(self._settings)
        if self._settings.ocr_lease_duration_s > longest_document_s:
            return None
        message = (
            "OCR_LEASE_DURATION_S=%ds is not longer than the longest a single document may take "
            "(%.0fs = OCR_TIMEOUT_BASE_S + OCR_MAX_PAGES x OCR_TIMEOUT_PER_PAGE_S + 2 x "
            "OCR_TIMEOUT_HARD_GRACE_S). The reaper can therefore reclaim a lease from a worker that "
            "is still legitimately working, and that document will restart from scratch on every "
            "retry. Raise the lease or lower OCR_MAX_PAGES/OCR_TIMEOUT_PER_PAGE_S."
        ) % (self._settings.ocr_lease_duration_s, longest_document_s)
        logger.warning(message)
        return message

    def is_ready(self) -> bool:
        with self._ready_counter.get_lock():
            return self._ready_counter.value >= self._worker_count

    def task_timeout_s(self) -> float:
        # Third-line backstop, NOT the per-document timeout. Phase 1 shipped
        # this worst-case ceiling as the only timeout, because the
        # dispatching side genuinely cannot know a document's page count
        # without doing the pool worker's own job first -- so a hung 1-page
        # photo held a worker for the full 1310s (Phase 1 open item #1).
        #
        # Phase 1 close-out moved the real budget into the pool worker,
        # where the page count IS known (deadline.py): a soft check between
        # pages, and a hard watchdog that records TIMEOUT itself and exits
        # with OCR_TIMEOUT_EXIT_CODE. This ceiling now only fires if that
        # watchdog thread never ran at all; when it does, pebble terminates
        # and replaces the one worker process (verified live) and the
        # claimed processing_jobs row is left 'processing' for the reaper,
        # exactly like any other crashed worker (WORKER_CRASH).
        return compute_task_timeout_s(self._settings)

    def schedule_process_version(
        self,
        version_id: uuid.UUID,
        correlation_id: uuid.UUID | None = None,
        requested_fields: "list[FieldSpec] | None" = None,
        resolve_fields_from_db: bool = False,
        read_token: str | None = None,
    ) -> ProcessFuture:
        # read_token is the grant dms-core minted for this job. It is the only
        # way this worker can read the document's bytes, and it is passed
        # through to the pipeline rather than stored anywhere: it is a bearer
        # credential for one version, valid for minutes.
        return self._pool.schedule(
            _run_process_version,
            args=(version_id, correlation_id, requested_fields, resolve_fields_from_db, read_token),
            timeout=self.task_timeout_s(),
        )

    def schedule_process_zone(
        self, version_id: uuid.UUID, zone_names: list[str] | None, correlation_id: uuid.UUID | None = None
    ) -> ProcessFuture:
        return self._pool.schedule(
            _run_process_zone, args=(version_id, zone_names, correlation_id), timeout=self.task_timeout_s()
        )

    def schedule_process_rubber_band(
        self,
        version_id: uuid.UUID,
        page: int,
        box: tuple[float, float, float, float],
        correlation_id: uuid.UUID | None = None,
    ) -> ProcessFuture:
        return self._pool.schedule(
            _run_process_rubber_band, args=(version_id, page, box, correlation_id), timeout=self.task_timeout_s()
        )

    def schedule_render_page(
        self, version_id: uuid.UUID, page: int, dpi: int | None = None,
        read_token: str | None = None
    ) -> ProcessFuture:
        # A page render is seconds at worst, not the multi-minute budget a
        # whole document gets -- a reviewer waiting on an image should get
        # an error long before the document timeout would fire. The pool
        # is still the right place to run it: fitz page rendering is the
        # same native code whose memory the pool exists to isolate.
        return self._pool.schedule(
            _run_render_page, args=(version_id, page, dpi, read_token),
            timeout=self._settings.ocr_preview_timeout_s,
        )

    def schedule_preview_rubber_band(
        self, version_id: uuid.UUID, page: int, box: tuple[float, float, float, float],
        read_token: str | None = None
    ) -> ProcessFuture:
        return self._pool.schedule(
            _run_preview_rubber_band, args=(version_id, page, box, read_token),
            timeout=self._settings.ocr_preview_timeout_s,
        )

    def close(self) -> None:
        self._pool.stop()
        self._pool.join()
