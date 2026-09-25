"""Per-document timeout, armed with the document's REAL page count.

Phase 1 shipped one uniform ceiling for every task --
`ocr_timeout_base_s + ocr_max_pages * ocr_timeout_per_page_s`, 1310s at the
defaults -- because the dispatching side (the consumer/API in the parent
process) cannot know a document's page count without fetching and opening
the file itself, which is exactly the work it is handing to the pool
worker. The consequence, recorded as Phase 1 open item #1: a *one-page*
photo that hangs occupied one of two pool workers for ~22 minutes.

The fix is to move the timeout to where the page count is actually known --
inside the pool worker, the moment `LayoutEngine.load()` has opened the
document. `OcrPipeline` creates a `DocumentDeadline` before it starts
inference and calls `arm(page_count)` from `_run_inference` as soon as the
document is open. Two layers, because neither alone is enough:

1. **Soft** (`check()`): called between pages. Raises `OcrTimeoutError`,
   which is an ordinary classified pipeline failure -- the pool worker
   survives, records `TIMEOUT: ...` through the normal
   `_finalize_failure` path, and goes on to the next document. This is the
   good outcome and the common one for a document that is merely slower
   than its budget.
2. **Hard** (`arm()`'s watchdog thread): fires at the budget plus
   `ocr_timeout_hard_grace_s`. A single wedged native PaddleOCR/Docling
   call never returns to a `check()`, and Python cannot interrupt it, so
   the watchdog runs the caller's `on_expiry` callback (pipeline.py writes
   the same `TIMEOUT` failure record from this thread -- safe, because the
   run phase deliberately holds no open transaction) and then hard-exits
   the process. `pebble` replaces that one worker; the rest of the pool is
   untouched.

`pebble`'s own `schedule(timeout=...)` ceiling stays as a third, outermost
backstop for the case where even this watchdog thread never gets scheduled
-- see `worker_pool.compute_task_timeout_s`.

Nothing here imports the rest of the service, so it is unit-testable
without a pool worker, a database, or a real document.
"""
from __future__ import annotations

import logging
import threading
import time
from typing import Callable

logger = logging.getLogger(__name__)


class DocumentDeadline:
    """One document's timeout budget. Not reusable: a `DocumentDeadline` is
    created per `process_version` call and settled exactly once.

    Thread-safety matters here for one specific race -- the main thread
    finishing inference at the same moment the watchdog thread fires. Both
    go through `_lock` and the first to set `_settled` wins, so the
    document is never both finalized normally *and* hard-killed.
    """

    def __init__(
        self,
        *,
        base_s: float,
        per_page_s: float,
        hard_grace_s: float,
        on_expiry: Callable[[float], None] | None = None,
    ):
        self._base_s = base_s
        self._per_page_s = per_page_s
        self._hard_grace_s = hard_grace_s
        self._on_expiry = on_expiry
        self._lock = threading.Lock()
        self._timer: threading.Timer | None = None
        self._budget_s: float | None = None
        self._expires_at: float | None = None
        self._settled = False

    def budget_for(self, page_count: int) -> float:
        return self._base_s + page_count * self._per_page_s

    @property
    def budget_s(self) -> float | None:
        """The armed budget, or None if `arm()` was never reached (the
        document failed before it could be opened).
        """
        return self._budget_s

    def arm(self, page_count: int) -> float:
        """Start the clock, now that the real page count is known. Returns
        the budget in seconds. Calling it twice is a no-op after the first
        (the second call keeps the original deadline) -- a document is
        opened once per run.
        """
        budget = self.budget_for(page_count)
        with self._lock:
            if self._settled or self._expires_at is not None:
                return self._budget_s if self._budget_s is not None else budget
            self._budget_s = budget
            self._expires_at = time.monotonic() + budget
            if self._on_expiry is not None:
                self._timer = threading.Timer(budget + self._hard_grace_s, self._fire)
                self._timer.daemon = True
                self._timer.start()
        logger.info(
            "Per-document timeout armed: %d page(s) -> %.0fs soft / %.0fs hard",
            page_count, budget, budget + self._hard_grace_s,
        )
        return budget

    def remaining_s(self) -> float | None:
        with self._lock:
            if self._expires_at is None:
                return None
            return self._expires_at - time.monotonic()

    def expired(self) -> bool:
        remaining = self.remaining_s()
        return remaining is not None and remaining <= 0

    def settle(self) -> bool:
        """Disarm the watchdog. Returns False if the hard watchdog already
        claimed this document, in which case the caller must not write
        anything -- the watchdog is already finalizing it and the process
        is about to exit.
        """
        with self._lock:
            if self._settled:
                return False
            self._settled = True
            if self._timer is not None:
                self._timer.cancel()
                self._timer = None
            return True

    def _fire(self) -> None:
        with self._lock:
            if self._settled:
                return
            self._settled = True
            budget = self._budget_s or 0.0
        self._on_expiry(budget)  # type: ignore[misc]  # only armed when not None
