from __future__ import annotations

import asyncio
import logging

from sqlalchemy.orm import sessionmaker, Session

from .config import Settings
from .db import session_scope
from .errors import WorkerCrashError
from .events import build_ocr_failed_payload, build_ocr_requested_payload
from .models import Document, DocumentVersion
from .repository import OcrRepository

logger = logging.getLogger(__name__)


class Reaper:
    """Background sweep for 'processing' jobs whose lease expired without
    a matching completion -- a crashed/hung worker (OOM kill, a killed-
    by-timeout pool worker whose caller only knows to leave the row alone
    for this to reclaim, a container restart) never comes back on its own
    to move these out of 'processing'. Without this, "zero stuck
    processing versions" (Phase 1's own exit criterion) is not actually
    met by B.2/B.3 alone.

    Runs entirely against `repository.py`'s already-existing, already-CAS-
    guarded `find_expired_leases`/`reap_requeue`/`reap_dead_letter`
    primitives (built in checkpoint 1) -- this class is just the loop that
    calls them on a timer. Safe under multiple concurrent ocr-worker
    instances each running their own reaper: every write is the same
    guarded CAS UPDATE `claim_job` itself uses, so at most one instance
    wins each row with no additional locking primitive.
    """

    def __init__(self, settings: Settings, session_factory: sessionmaker[Session]):
        self._settings = settings
        self._session_factory = session_factory
        self._task: asyncio.Task | None = None
        self._stop_event = asyncio.Event()

    async def start(self) -> None:
        self._task = asyncio.create_task(self._loop())
        logger.info("Reaper started, interval=%ds", self._settings.ocr_reaper_interval_s)

    async def stop(self) -> None:
        self._stop_event.set()
        if self._task is not None:
            await self._task

    async def _loop(self) -> None:
        loop = asyncio.get_running_loop()
        while not self._stop_event.is_set():
            try:
                await loop.run_in_executor(None, self._reap_once)
            except Exception:
                # A DB outage or similar must not kill the reaper loop
                # permanently -- try again next tick.
                logger.exception("Reaper tick failed")
            try:
                await asyncio.wait_for(self._stop_event.wait(), timeout=self._settings.ocr_reaper_interval_s)
            except asyncio.TimeoutError:
                pass

    def _reap_once(self) -> None:
        """One tick: a single short transaction covering every currently-
        expired lease (bounded by find_expired_leases' own limit=50
        default) -- in practice only a handful of rows are ever expired at
        once, so this stays well within the "short transaction" principle
        B.3 established without needing one transaction per row.
        """
        with session_scope(self._session_factory) as session:
            repo = OcrRepository(session)
            expired = repo.find_expired_leases()
            for job in expired:
                version = session.get(DocumentVersion, job.reference_id)
                if version is None:
                    # The version row itself is gone (should not happen in
                    # practice -- nothing deletes document_versions) --
                    # nothing meaningful to reap into, skip rather than
                    # crash the whole tick over one orphaned row.
                    logger.error("Reaper: processing_jobs row %s references missing version %s", job.job_id, job.reference_id)
                    continue
                document = session.get(Document, version.document_id)

                if job.attempts < self._settings.max_delivery_attempts:
                    self._requeue(repo, job=job, version=version, document=document)
                else:
                    self._dead_letter(repo, job=job, version=version, document=document)

    def _requeue(self, repo: OcrRepository, *, job, version: DocumentVersion, document: Document) -> None:
        backoff_seconds = self._settings.ocr_reaper_backoff_base_s * job.attempts
        payload = build_ocr_requested_payload(
            tenant_id=version.tenant_id, document_id=document.document_id, version_id=version.version_id,
            correlation_id=job.correlation_id,
        )
        won = repo.reap_requeue(
            job_id=job.job_id, backoff_seconds=backoff_seconds, tenant_id=version.tenant_id,
            version_id=version.version_id, event_payload=payload,
        )
        if won:
            logger.warning(
                "Reaper requeued job %s (version %s): attempts=%d/%d, backoff=%ds",
                job.job_id, version.version_id, job.attempts, self._settings.max_delivery_attempts, backoff_seconds,
            )

    def _dead_letter(self, repo: OcrRepository, *, job, version: DocumentVersion, document: Document) -> None:
        error = WorkerCrashError(
            f"exceeded max_delivery_attempts ({self._settings.max_delivery_attempts})"
        ).formatted()
        payload = build_ocr_failed_payload(
            tenant_id=version.tenant_id, document_id=document.document_id, version_id=version.version_id,
            error=error, correlation_id=job.correlation_id,
        )
        won = repo.reap_dead_letter(job_id=job.job_id, version=version, error=error, event_payload=payload)
        if won:
            logger.error("Reaper dead-lettered job %s (version %s): %s", job.job_id, version.version_id, error)
