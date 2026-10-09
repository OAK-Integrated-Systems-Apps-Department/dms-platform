from __future__ import annotations

import json
import logging
import os
import time
import uuid
from dataclasses import dataclass, field, replace
from typing import Callable

from sqlalchemy.orm import sessionmaker, Session

from .confidence import ConfidenceEvaluator
from .config import OCR_TIMEOUT_EXIT_CODE, Settings
from .db import session_scope
from .deadline import DocumentDeadline
from .errors import (
    MissingReadGrantError,
    EngineError,
    FileTooLargeError,
    OcrEngineError,
    OcrTimeoutError,
    TooManyPagesError,
    UnsupportedFormatError,
)
from .events import build_ocr_completed_payload, build_ocr_failed_payload
from .extraction import (
    ExtractedField,
    FieldSpec,
    LineBlock,
    extract_requested_fields,
    extract_structured_fields,
    field_for_zone_name,
    fields_to_dict,
    requested_fields_to_output_dict,
)
from .layout import LayoutEngine, SUPPORTED_MIME_TYPES, sniff_and_normalize_mime_type
from .ocr_engine import OcrEngine
from . import quality as quality_mod
from . import review as review_mod
from . import searchable_pdf
from . import line_items as line_items_mod
from . import validation as validation_mod
from . import routing
from .orientation import NullOrientationClassifier, OrientationClassifier, decide as decide_orientation
from .repository import OcrRepository, VersionNotEligible, VersionNotFound
from .content_client import DocumentContentClient, ReadGrant
from .zones import ZoneRegion, validate_normalized_box

logger = logging.getLogger(__name__)


def _check_deadline(deadline: DocumentDeadline | None, where: str) -> None:
    """The SOFT half of the per-document timeout: called at each point in
    the page/zone loop where the pipeline is between two units of native
    work and can still fail cleanly.

    Raising here is far better than letting the hard watchdog fire -- the
    exception rides the ordinary `_finalize_failure` path (TIMEOUT recorded,
    `.failed` event published, lease released) and the pool worker survives
    to take the next document. The watchdog only exists for the case where
    execution never comes back to one of these points at all.
    """
    if deadline is None or not deadline.expired():
        return
    budget = deadline.budget_s or 0.0
    raise OcrTimeoutError(
        f"exceeded the {budget:.0f}s budget for this document ({where})"
    )


# The OCR output JSON's own schema version (section 3.11). See its use
# in `_finalize_success` for why this is "added at 2", not "bumped to 2".
OUTPUT_SCHEMA_VERSION = 2


def _page_geometry(layout_doc, page_index: int, dpi: int):
    """This page's geometry, or None when the document has none.

    Tolerant of a `LayoutDocument` implementation that predates
    `page_geometry` (the Protocol gained it in Phase 2) rather than
    requiring every fake in the test suite to grow the method at once.
    A missing geometry means `bbox_norm` is null, which is honest -- it
    never means "assume a default page size", because guessing here
    would put a confidently wrong highlight on a real document.
    """
    getter = getattr(layout_doc, "page_geometry", None)
    if getter is None:
        return None
    try:
        return getter(page_index, dpi)
    except NotImplementedError:
        return None


def _norm(geometry, bbox):
    """Normalized form of a pixel bbox, or None when there is no geometry.

    Output contract v2 (docs/ocr/ocr-production-prompt.md section 3.5):
    every bbox carries 0-1 fractions of page width/height alongside the
    pixel box that is kept for backward compatibility. The normalized
    form is the one every consumer should use -- it survives a DPI
    change, which the pixel box does not.
    """
    if geometry is None or bbox is None:
        return None
    return geometry.normalize(tuple(bbox))


def _merge_native_text_and_tables(native_blocks, tables, geometry=None) -> tuple[str, list[dict], list[dict]]:
    """Combines Docling's flat paragraph text with its (optional) real
    table structure into one reading-order `blocks`/`text`, plus a
    separate structured `tables` list.

    Docling (with do_table_structure=True) moves a table's cell text out
    of the flat text stream entirely -- see layout.py's
    native_text_blocks_for_page docstring. Without this merge, enabling
    table structure would silently drop every table's content from
    `text`/`blocks`, which would be a real regression for anything that
    only reads those (full-text search, the generic regex field
    extractor). So each table's cells are re-inserted into `blocks` in
    row-then-column order, positioned by the table's own bbox among the
    surrounding paragraph blocks (sorted by top-then-left), while the
    real grid (rows/cols/spans/headers) is kept separately in `tables`
    for a consumer that wants the actual structure.
    """
    positioned: list[tuple[int, int, list[dict], dict | None]] = []
    for b in native_blocks:
        positioned.append((
            b.bbox[1],
            b.bbox[0],
            [{
                "text": b.text,
                "bbox": list(b.bbox),
                "bbox_norm": _norm(geometry, b.bbox),
                "confidence": 1.0,
            }],
            None,
        ))

    for t in tables:
        ordered_cells = sorted(t.cells, key=lambda c: (c.row, c.col))
        cell_blocks = [
            {
                "text": c.text,
                "bbox": list(c.bbox) if c.bbox else None,
                "bbox_norm": _norm(geometry, c.bbox),
                "confidence": 1.0,
            }
            for c in ordered_cells
            if c.text
        ]
        table_dict = {
            "bbox": list(t.bbox),
            "bbox_norm": _norm(geometry, t.bbox),
            "num_rows": t.num_rows,
            "num_cols": t.num_cols,
            "cells": [
                {
                    "row": c.row,
                    "col": c.col,
                    "row_span": c.row_span,
                    "col_span": c.col_span,
                    "text": c.text,
                    "bbox": list(c.bbox) if c.bbox else None,
                    "bbox_norm": _norm(geometry, c.bbox),
                    "column_header": c.column_header,
                    "row_header": c.row_header,
                }
                for c in ordered_cells
            ],
        }
        positioned.append((t.bbox[1], t.bbox[0], cell_blocks, table_dict))

    positioned.sort(key=lambda entry: (entry[0], entry[1]))
    blocks = [block for _, _, block_list, _ in positioned for block in block_list]
    tables_out = [table_dict for _, _, _, table_dict in positioned if table_dict is not None]
    text = "\n".join(b["text"] for b in blocks)
    return text, blocks, tables_out


def _upright_page_heights(geometry_by_page: dict, orientation_by_page: dict) -> dict[int, float]:
    """Page heights in the space `lines_by_page` is expressed in.

    Not the same as the page's own height: a 90/270 correction swaps a
    page's dimensions, and extraction's "value stacked below the label"
    radius is a fraction of page height (see `_spatial_value_on_page`).
    Feeding it a portrait height for a page OCR read as landscape would
    make that radius wrong by the page's aspect ratio in both directions.
    """
    heights: dict[int, float] = {}
    for page, geometry in geometry_by_page.items():
        orientation = orientation_by_page.get(page)
        if orientation is None:
            heights[page] = geometry.exact_height_px
        else:
            _, heights[page] = orientation.upright_page_size(
                geometry.exact_width_px, geometry.exact_height_px
            )
    return heights


def _restore_field_bboxes(field_results, orientation_by_page: dict):
    """Maps each spatially-matched field's bbox out of the corrected
    reading space and back into its page's own.

    Returns a new list rather than mutating: `RequestedFieldResult` is a
    frozen dataclass, deliberately -- it is the exact row
    `document_ocr_field_results` stores.

    Only touches a result that actually has a page and a bbox: a
    pattern-rule or flattened-text match has neither (see
    `extract_requested_fields`), and a zone-derived one was never in the
    corrected space to begin with -- `_run_zone_ocr` does not reorient.
    """
    if not field_results or not orientation_by_page:
        return field_results
    restored = []
    for result in field_results:
        orientation = orientation_by_page.get(result.page)
        if orientation is None or result.bbox is None or not orientation.applied:
            restored.append(result)
            continue
        restored.append(replace(result, bbox=tuple(orientation.to_original(result.bbox))))
    return restored


@dataclass
class PageResult:
    """One entry of the output contract's `pages` array. `page_number` is
    1-indexed, matching the contract's own example and Docling's native
    numbering.

    Every block carries both a pixel `bbox` in the full page's raster
    space and, since schema v2, a `bbox_norm` in 0-1 page fractions.
    Both are produced from this page's single `geometry`, regardless of
    which method produced the block, so a consumer can treat native_text
    and paddleocr blocks uniformly -- which was only true in intent
    before Phase 2 (see `layout.PageGeometry`). `geometry` is None for a
    reflowable `.docx`, and then every `bbox_norm` on the page is null.

    `tables` is only ever non-empty for a `native_text` page: real
    row/column/cell structure from Docling's table-structure model
    (`layout.py`'s `tables_for_page`), for a consumer that needs the
    actual grid rather than just reading order. Every table cell's text
    is ALSO present in `blocks`/`text` in reading-order position (see
    `_merge_native_text_and_tables`), so a consumer that ignores `tables`
    entirely still sees complete text -- `tables` is additive structure,
    not the only place a table's content appears.
    """

    page_number: int
    method: str  # "native_text" | "paddleocr"
    text: str
    confidence: float
    blocks: list[dict] = field(default_factory=list)
    tables: list[dict] = field(default_factory=list)
    geometry: "Any | None" = None  # layout.PageGeometry, None for a .docx
    # What preprocessing this page went through, per production prompt
    # section 3.4 ("each step individually toggleable in config and
    # recorded in the output"). Empty for a native-text page, which is
    # never rasterized and so never preprocessed. Records the decision
    # even when nothing was applied -- "the classifier looked and said
    # upright" and "the classifier never ran" are different facts, and
    # only the second is a reason to suspect the config.
    preprocessing: dict = field(default_factory=dict)
    # Which of native_text / paddleocr / hybrid this page took and every
    # number that decision was made from (production prompt 3.6). The
    # metrics are carried, not just the verdict: "this invoice came back
    # empty" is answerable from "routed native_text at garbage ratio 0.02
    # and 91% text coverage", and unanswerable from the word alone.
    routing: dict = field(default_factory=dict)
    # Per-page boolean facts a consumer has to branch on, kept apart from
    # the metric dicts above so that "is anything wrong with this page"
    # is one lookup rather than a scan of four nested structures.
    # `poor_image_quality` (production prompt 3.3) and
    # `table_structure_skipped` both live here. Absent keys mean false;
    # the dict carries only what is true, so an empty dict reads as a
    # clean page.
    flags: dict = field(default_factory=dict)
    # Measured per-page image quality (production prompt 3.3): effective
    # DPI, blur, contrast and skew. Empty for a page that was never
    # rasterized, which is the same distinction `preprocessing` draws --
    # "measured and fine" and "never measured" are different facts.
    quality: dict = field(default_factory=dict)

    def to_dict(self) -> dict:
        out = {
            "page_number": self.page_number,
            "method": self.method,
            "text": self.text,
            "confidence": self.confidence,
            "blocks": self.blocks,
            "tables": self.tables,
            "preprocessing": self.preprocessing,
            "routing": self.routing,
            "flags": self.flags,
            "quality": self.quality,
        }
        # Schema v2: the page's own dimensions, so a consumer can turn a
        # normalized bbox back into pixels or points without having to
        # reproduce this worker's render-scale arithmetic. Their absence
        # was a real gap -- see docs/ocr/failure-analysis.md 2.6, where a
        # pixel bbox carried no scale reference anywhere in the output.
        out["geometry"] = self.geometry.to_dict() if self.geometry is not None else None
        return out


@dataclass
class ProcessingOutcome:
    status: str  # "completed" | "failed" | "skipped"
    version_id: uuid.UUID
    output: dict | None = None  # the exact JSON also stored at extracted_text_ref -- see "Output contract"
    error: str | None = None


@dataclass
class PageRender:
    """One rendered page, for the review screen (Phase 4c).

    `width_px`/`height_px` travel with the bytes because the frontend
    needs them to place highlights BEFORE the image has loaded, and
    because the renderer may have downscaled to stay under
    `ocr_render_max_mp` -- the caller must not assume dpi x page size.
    """

    png: bytes
    width_px: int
    height_px: int
    page_count: int


class OcrPipeline:
    """Implements workflow steps 2-6 of section 2.4. Every trigger --
    RabbitMQ consumer, POST /ocr/process/{version_id}, POST /ocr/zone,
    POST /ocr/rubber-band, and scripts/dev/publish_fake_scan_event.py's
    consumer-side effect -- funnels through this class, so "the trigger
    source shouldn't change the processing logic" holds by construction.
    """

    def __init__(
        self,
        settings: Settings,
        session_factory: sessionmaker[Session],
        content: DocumentContentClient,
        layout_engine: LayoutEngine,
        ocr_engine: OcrEngine,
        orientation_classifier: OrientationClassifier | None = None,
        # tenant_id -> vendor names this tenant has already CONFIRMED (a
        # human accepted or corrected them in the review screen). Injected
        # rather than read inline so the eval can stand a seeded list in
        # for a review history it does not have, and so this stays
        # testable without a corrections table. None means "no list",
        # which is what a tenant that has filed nothing yet has.
        known_vendors_loader: "Callable[[uuid.UUID], list[str]] | None" = None,
    ):
        self._settings = settings
        self._known_vendors_loader = known_vendors_loader
        self._session_factory = session_factory
        self._content = content
        self._layout_engine = layout_engine
        self._ocr_engine = ocr_engine
        # Defaults to the no-op rather than to the real model so a test
        # (and `OCR_ORIENTATION_ENABLED=false`) gets a pipeline that needs
        # no paddleocr at all, while still running the identical code path
        # -- see orientation.NullOrientationClassifier.
        self._orientation = orientation_classifier or NullOrientationClassifier()
        self._confidence = ConfidenceEvaluator(settings.min_confidence_threshold)

    def _known_vendors_for(self, tenant_id) -> list[str]:
        """Never fails a document. A missing or broken vendor list costs
        one tier of one field; raising here would cost the whole run."""
        if self._known_vendors_loader is None or tenant_id is None:
            return []
        try:
            return list(self._known_vendors_loader(tenant_id) or [])
        except Exception:  # noqa: BLE001
            logger.warning("could not load known vendors for tenant %s", tenant_id, exc_info=True)
            return []

    # ------------------------------------------------------------------
    # Per-document timeout (see deadline.py)
    # ------------------------------------------------------------------
    def _make_deadline(
        self, *, job_id: uuid.UUID, version_id: uuid.UUID, correlation_id: uuid.UUID | None
    ) -> DocumentDeadline:
        return DocumentDeadline(
            base_s=self._settings.ocr_timeout_base_s,
            per_page_s=self._settings.ocr_timeout_per_page_s,
            hard_grace_s=self._settings.ocr_timeout_hard_grace_s,
            on_expiry=lambda budget_s: self._on_hard_timeout(
                job_id=job_id, version_id=version_id, correlation_id=correlation_id, budget_s=budget_s
            ),
        )

    def _on_hard_timeout(
        self, *, job_id: uuid.UUID, version_id: uuid.UUID, correlation_id: uuid.UUID | None, budget_s: float
    ) -> None:
        """Runs on `DocumentDeadline`'s watchdog thread when even the soft
        between-pages check never got a turn -- i.e. one native
        PaddleOCR/Docling call has wedged and will not return.

        Writes the TIMEOUT failure record FIRST, then hard-exits. Doing it
        in that order is the whole point: before this, a timeout left the
        `processing_jobs` row claimed with a live lease and relied entirely
        on the reaper to notice `ocr_lease_duration_s` (1800s by default)
        later -- so a document that timed out at 85s still showed
        `processing` for another half hour, and came back as
        `WORKER_CRASH`, not `TIMEOUT`. Recording it here makes the status
        correct and immediate, and leaves the reaper as the backstop for
        what it is actually for (a worker that died without warning).

        `os._exit`, not `sys.exit`: this is a non-main thread, so
        `SystemExit` would only unwind this thread and leave the wedged
        inference running. `_exit` skips atexit/finalizers deliberately --
        there is nothing safe to flush from a process whose native
        extension is stuck mid-call.
        """
        logger.error(
            "Hard per-document timeout after %.0fs (+%ds grace) for version %s -- recording TIMEOUT and "
            "terminating this pool worker",
            budget_s, self._settings.ocr_timeout_hard_grace_s, version_id,
        )
        try:
            with session_scope(self._session_factory) as session:
                repo = OcrRepository(session)
                job = repo.get_job(job_id)
                ctx = repo.get_version_for_processing(version_id)
                self._finalize_failure(
                    repo=repo, job=job, ctx=ctx,
                    exc=OcrTimeoutError(
                        f"exceeded the {budget_s:.0f}s budget for this document "
                        f"({self._settings.ocr_timeout_base_s}s + pages x {self._settings.ocr_timeout_per_page_s}s)"
                    ),
                    correlation_id=correlation_id,
                )
        except Exception:
            # Nothing left to escalate to -- this thread is about to end the
            # process. Log it and let the reaper reclaim the lease the old
            # way rather than silently exiting with no trace of why.
            logger.exception("Could not record the TIMEOUT failure for version %s; leaving it to the reaper", version_id)
        finally:
            logging.shutdown()
            os._exit(OCR_TIMEOUT_EXIT_CODE)

    # ------------------------------------------------------------------
    # Event-path / dev-trigger: full document
    # ------------------------------------------------------------------
    def process_version(
        self,
        version_id: uuid.UUID,
        correlation_id: uuid.UUID | None = None,
        requested_fields: list[FieldSpec] | None = None,
        resolve_fields_from_db: bool = False,
        read_token: str | None = None,
    ) -> ProcessingOutcome:
        """`requested_fields`, when given (even as an empty list), restricts
        extraction to exactly those fields (see extraction.py's
        extract_requested_fields) -- selectable OCR field extraction for
        the My Documents upload page. `None` preserves the original
        behavior: the legacy zone-template + generic invoice-regex fallback
        over everything, unchanged.

        `resolve_fields_from_db=True` (what the RabbitMQ consumers pass, and
        only them) fills `requested_fields` in from
        `document_versions.requested_fields` during the claim transaction,
        per docs/events/ocr-scanning-events.md's Phase 1 Decision 1 -- the
        `.requested` event carries no resolved specs precisely so that a
        rerun which widens the requested set is picked up by an already
        in-flight run. A version with an empty `requested_fields` column
        still resolves to `None`, i.e. the legacy behavior above; see
        `OcrRepository.resolve_requested_field_specs`. The dev-trigger
        endpoint passes its specs explicitly instead and never sets this.

        Three short, separate transactions (Phase 1 stability -- see
        docs/ocr/failure-analysis.md #1/#3: the previous single-transaction
        design held a Postgres connection/transaction open for the FULL
        inference duration -- verified live at 545s for one real 35-page
        document -- racing dms-core's own much shorter HTTP timeout and,
        as a direct consequence, freezing outbox_events.created_at at the
        transaction's start since Postgres's now() is fixed for a
        transaction's whole lifetime):

        1. CLAIM -- a compare-and-swap UPDATE (OcrRepository.claim_job), no
           inference. Returns "skipped" if another worker/a still-live
           lease already owns this version's job.
        2. RUN -- no open transaction at all. document fetch, Docling
           conversion, PaddleOCR inference (`_run_inference`).
        3. COMMIT -- a fresh transaction (today's `_finalize_success`/
           `_finalize_failure`), re-fetching the job/version rows since the
           ones from phase 1 belong to a session that's already closed.

        The per-document timeout (`deadline.py`) is armed during phase 2,
        as soon as the document is open and its real page count is known.
        Phase 2 deliberately holds no transaction, which is precisely what
        makes it safe for the hard watchdog to write the TIMEOUT failure
        record from its own thread: there is no row lock for it to block
        on. The same is NOT true of `process_zone_request`/
        `process_rubber_band_request` below, which keep one transaction
        open across their inference, so a watchdog write from another thread
        would block on their own row lock -- they get no deadline and stay
        bounded only by the outer pebble ceiling (they are single-page,
        synchronous, caller-visible operations).
        """
        with session_scope(self._session_factory) as session:
            repo = OcrRepository(session)
            ctx = repo.get_version_for_processing(version_id)  # raises VersionNotFound/VersionNotEligible
            # Dev/smoke-test convenience (POST /ocr/process/{id} without a
            # pre-existing queued row) -- a no-op for the real production
            # path, where dms-core already inserted the row in the same
            # transaction as the .requested outbox event.
            repo.ensure_queued_job(tenant_id=ctx.version.tenant_id, version_id=version_id, correlation_id=correlation_id)
            job = repo.claim_job(version_id=version_id, lease_seconds=self._settings.ocr_lease_duration_s)
            if job is None:
                # Several distinct causes, and telling them apart matters:
                # "already owned by a live lease" alone sent a whole
                # debugging session down the wrong path when the real
                # reason was an unelapsed retry backoff (see
                # OcrRepository.reap_requeue). Report what the row
                # actually says.
                logger.info(
                    "Version %s: no claimable job (%s); ack-and-skip",
                    version_id, repo.describe_unclaimable(version_id),
                )
                return ProcessingOutcome(status="skipped", version_id=version_id)
            job_id = job.job_id
            tenant_id = ctx.version.tenant_id
            mime_type = ctx.version.mime_type
            storage_object_key = ctx.version.storage_object_key
            original_filename = ctx.version.original_filename
            zone_regions = repo.get_zone_regions(ctx.document_type_id)
            if requested_fields is None and resolve_fields_from_db:
                requested_fields = repo.resolve_requested_field_specs(
                    version=ctx.version, document_type_id=ctx.document_type_id
                )
                logger.info(
                    "Version %s: resolved %s requested field(s) from document_versions.requested_fields",
                    version_id, "no" if requested_fields is None else len(requested_fields),
                )

        deadline = self._make_deadline(job_id=job_id, version_id=version_id, correlation_id=correlation_id)
        try:
            pages, fields, field_results, file_repaired, validation_failures, validation_errors, line_items, rendition = self._run_inference(
                mime_type=mime_type,
                version_id=version_id,
                read_token=read_token,
                original_filename=original_filename,
                tenant_id=tenant_id,
                zone_regions=zone_regions,
                requested_fields=requested_fields,
                deadline=deadline,
            )
            result: tuple | Exception = (pages, fields, field_results, file_repaired, validation_failures, validation_errors, line_items, rendition)
        except Exception as exc:  # noqa: BLE001 - deliberately broad: any failure here must be flagged, never silently dropped (OS-NFR-006)
            result = exc

        if not deadline.settle():
            # The hard watchdog won the race: it has already written (or is
            # writing) this job's TIMEOUT failure record and is about to
            # os._exit() this process. Writing a second, contradictory
            # outcome here would be the one way this can corrupt state, so
            # block until that exit lands rather than racing it.
            logger.error("Version %s: hard timeout watchdog already claimed this job; standing down", version_id)
            time.sleep(self._settings.ocr_timeout_hard_grace_s)
            raise OcrTimeoutError(f"version {version_id} was claimed by the hard timeout watchdog")

        with session_scope(self._session_factory) as session:
            repo = OcrRepository(session)
            job = repo.get_job(job_id)
            ctx = repo.get_version_for_processing(version_id)
            if isinstance(result, Exception):
                return self._finalize_failure(repo=repo, job=job, ctx=ctx, exc=result, correlation_id=correlation_id)
            pages, fields, field_results, file_repaired, validation_failures, validation_errors, line_items, rendition = result
            return self._finalize_success(
                repo=repo, job=job, ctx=ctx, pages=pages, fields=fields, field_results=field_results,
                correlation_id=correlation_id, file_repaired=file_repaired,
                requested_field_specs=requested_fields,
                validation_failures=validation_failures,
                validation_errors=validation_errors,
                line_items=line_items,
                rendition=rendition,
                # A full-document run visits every page, so this IS the
                # document's page count. A zone or rubber-band run visits
                # a subset and passes nothing, rather than overwriting the
                # real count with "1".
                page_count=len(pages),
            )

    def _fetch_document(self, *, version_id: uuid.UUID, read_token: str | None) -> bytes:
        """The document's plaintext bytes, from dms-core.

        Not from object storage: dms-core encrypts every object at rest, so a
        direct read returns ciphertext. See content_client.py for the two
        credentials this presents and why the file is written to tmpfs.

        The bytes are read back inside the download's context manager, so the
        tmpfs file exists for as long as it takes to read it and no longer.
        The in-memory shape is unchanged from the old MinIO read, so the size
        guard immediately after this call still does its job.
        """
        if not read_token:
            # A job with no grant cannot read anything. Raised rather than
            # skipped so the failure is visible and classified, instead of a
            # version that quietly never gets OCR'd.
            raise MissingReadGrantError(
                f"no read grant for version {version_id} - the event predates the internal-read "
                f"endpoint, or dms-core could not mint one"
            )
        grant = ReadGrant(token=read_token, version_id=str(version_id))
        with self._content.download(grant) as path:
            return path.read_bytes()

    def _run_inference(
        self,
        *,
        mime_type: str,
        version_id: uuid.UUID,
        read_token: str | None,
        original_filename: str,
        zone_regions: list,
        requested_fields: list[FieldSpec] | None,
        tenant_id: uuid.UUID | None = None,
        deadline: DocumentDeadline | None = None,
    ) -> tuple[list[PageResult], dict, list | None, bool]:
        """Phase 2 of process_version -- no DB transaction open. Raises a
        classified `errors.OcrEngineError` subclass on every recognized
        failure (see errors.py); anything else propagates as-is (still
        caught by process_version's own broad except and recorded via
        `errors.EngineError` as a catch-all, never a raw, unclassified
        `str(exc)` -- see docs/ocr/failure-analysis.md #9's live finding
        that a real encrypted PDF and a real corrupt PDF both surfaced
        opaque internal exception text with no error code at all).
        """
        file_bytes = self._fetch_document(version_id=version_id, read_token=read_token)
        if len(file_bytes) > self._settings.ocr_max_file_size_bytes:
            raise FileTooLargeError(
                f"{len(file_bytes)} bytes exceeds ocr_max_file_size_bytes={self._settings.ocr_max_file_size_bytes}"
            )

        # Real magic-byte sniffing, not the caller-supplied mime_type --
        # see docs/ocr/failure-analysis.md #4: dms-core stores the
        # browser's own Content-Type verbatim, and a real HEIC upload
        # showed Chrome itself sends application/octet-stream, not
        # image/heic. Sniffing the actual bytes recovers the real type
        # regardless of what the declared one said.
        mime_type = sniff_and_normalize_mime_type(file_bytes, mime_type)
        if mime_type not in SUPPORTED_MIME_TYPES:
            raise UnsupportedFormatError(mime_type)

        # Both of these run inside `load()`, the moment PyMuPDF has opened
        # the file and BEFORE Docling's conversion -- the single most
        # expensive step in the pipeline (~104s for a 35-page scanned PDF,
        # measured live). Checking the page cap after `load()` returned
        # meant an oversized document paid that cost in full before being
        # rejected, and arming the deadline afterwards left that same step
        # outside the budget it exists to bound. See LayoutEngine.load.
        def on_page_count(page_count: int) -> None:
            if page_count > self._settings.ocr_max_pages:
                raise TooManyPagesError(
                    f"{page_count} pages exceeds ocr_max_pages={self._settings.ocr_max_pages}"
                )
            # Deliberately after the cap check, so an oversized document is
            # rejected rather than handed an enormous budget.
            if deadline is not None:
                deadline.arm(page_count)

        with self._layout_engine.load(
            file_bytes, mime_type, original_filename, on_page_count=on_page_count,
            max_megapixels=self._settings.ocr_render_max_mp,
        ) as layout_doc:
            if zone_regions:
                pages, extracted_fields = self._run_zone_ocr(layout_doc, zone_regions, deadline=deadline)
                zone_derived_fields = extracted_fields
                # No real per-line position data for a zone-cropped run
                # (each region's whole crop already IS the value) -- any
                # requested field NOT covered by a zone falls back to
                # flattened-text matching only. Currently moot in
                # practice: no document type in this app has zone rows
                # configured yet (see document_type_ocr_zones' own
                # "nothing writes to it outside test fixtures" note).
                lines_by_page: dict[int, list[LineBlock]] = {}
                orientation_by_page: dict[int, "Any"] = {}
                line_confidences_by_page: dict[int, list] = {}
                rendition = (None, [])
            else:
                # No zone/field template configured for this document
                # type -- extracted_fields here is the LEGACY generic
                # regex fallback (invoice_number/total_amount/...), not a
                # real zone match. It must never be fed to
                # extract_requested_fields as a "zone template hit"
                # (priority 1) -- that would let an unrelated regex guess
                # bypass the new dispatch's own, more field-appropriate
                # strategy for a requested field.
                pages, extracted_fields, lines_by_page, orientation_by_page, line_confidences_by_page, rendition = self._run_full_document(layout_doc, deadline=deadline)
                zone_derived_fields = []
            file_repaired = getattr(layout_doc, "file_repaired", False)

        # Which pages gave up on table structure (see layout.py's
        # `_install_table_structure_bound`). Recorded per page rather than
        # per document because a 40-page contract losing tables on one
        # page is a very different fact from losing them on all forty,
        # and `requires_review` in Phase 3 has to be able to tell.
        skipped = getattr(self._layout_engine, "table_structure_skipped_pages", None)
        for page_no in (skipped() if callable(skipped) else set()):
            page_result = next((p for p in pages if p.page_number == page_no + 1), None)
            if page_result is not None:
                page_result.flags["table_structure_skipped"] = True

        validation_failures: list[str] = []
        validation_errors: list[dict] = []
        line_items: list = []

        # 1-indexed, matching ExtractedField.page / RequestedFieldResult.page.
        geometry_by_page = {p.page_number: p.geometry for p in pages if p.geometry is not None}
        field_results = None
        if requested_fields is not None:
            full_text = "\n\n".join(p.text for p in pages)
            zone_fields_by_key = {f.field: f for f in zone_derived_fields}
            field_results = extract_requested_fields(
                specs=requested_fields,
                text=full_text,
                zone_fields=zone_fields_by_key,
                lines_by_page=lines_by_page,
                page_heights_px=_upright_page_heights(geometry_by_page, orientation_by_page),
                field_confidence_threshold=self._settings.field_low_confidence_threshold,
                line_confidences=line_confidences_by_page,
                default_currency=self._settings.ocr_default_currency,
                known_vendors=self._known_vendors_for(tenant_id),
            )
            # `lines_by_page` was in the corrected reading space, so a
            # spatially-matched field's bbox comes back in it too. Put it
            # back in the page's own space before it reaches the output
            # (and document_ocr_field_results, which the review UI's
            # highlights are drawn from).
            field_results = _restore_field_bboxes(field_results, orientation_by_page)

            # Validation (production prompt 3.9), wired in at last. The
            # rules existed and nothing called them, so a total that
            # disagreed with its own subtotal+tax was reported as
            # `extracted` with full confidence.
            #
            # A failure never deletes a value -- every one of them is a
            # real reading of the document, and which of three numbers was
            # misread is not knowable from the arithmetic alone. It
            # downgrades the status of EVERY field the rule involved, so a
            # human resolves the contradiction rather than the pipeline
            # guessing.
            # Invoice line items (production prompt 3.8), read from the
            # table grids TableFormer already produced for these pages.
            line_items = line_items_mod.extract_from_tables(
                {p.page_number: p.tables for p in pages if p.tables},
                locale=self._settings.ocr_locale,
            )

            validation = validation_mod.validate_field_results(
                field_results,
                options_by_field={
                    spec.field_key: spec.options for spec in (requested_fields or [])
                },
                data_types_by_field={
                    spec.field_key: spec.data_type for spec in (requested_fields or [])
                },
                full_text=full_text,
                locale=self._settings.ocr_locale,
                line_items=[i.for_validation() for i in line_items] or None,
            )
            if not validation.ok:
                flagged = validation.fields_to_flag()
                field_results = [
                    replace(r, status="low_confidence")
                    if r.field_key in flagged and r.status == "extracted"
                    else r
                    for r in field_results
                ]
                validation_failures = [e.rule for e in validation.errors]
                logger.info(
                    "Validation flagged %d field(s) across %d rule(s): %s",
                    len(flagged), len(validation.errors),
                    ", ".join(sorted({e.rule for e in validation.errors})),
                )
                validation_errors = validation.to_dict()

            fields = requested_fields_to_output_dict(field_results, geometry_by_page)
        else:
            fields = fields_to_dict(extracted_fields, geometry_by_page)

        return (pages, fields, field_results, file_repaired, validation_failures,
                validation_errors, line_items, rendition)

    # ------------------------------------------------------------------
    # Preprocessing (production prompt 3.4) -- see orientation.py
    # ------------------------------------------------------------------
    def _orient(self, page_image):
        """`(image_for_ocr, decision)` for one rendered page.

        Runs only on the rasterized path. A native-text page needs no
        orientation work even when it is visually sideways: its text comes
        out of the PDF's own text layer in reading order regardless of how
        the page is displayed, and PyMuPDF already applies a page's
        `/Rotate` and a JPEG's EXIF orientation when it rasterizes (both
        verified live -- see `test_layout.py`).
        """
        if not self._settings.ocr_orientation_enabled:
            return page_image.image, None
        image, decision = decide_orientation(
            self._orientation,
            page_image.image,
            original_size=(page_image.width_px, page_image.height_px),
            min_confidence=self._settings.ocr_orientation_min_confidence,
        )
        if decision.applied:
            logger.info(
                "page %d arrived rotated %d deg (confidence %.3f); corrected before OCR",
                page_image.page_no + 1,
                decision.angle,
                decision.confidence,
            )
        return image, decision

    @staticmethod
    def _preprocessing_dict(decision) -> dict:
        return {"orientation": decision.to_dict()} if decision is not None else {}

    # ------------------------------------------------------------------
    # Image quality (production prompt 3.3) -- see quality.py
    # ------------------------------------------------------------------
    def _assess_quality(self, page_result, image, layout_doc, page_index: int, geometry) -> None:
        """Measure one rasterized page and flag it if it is poor.

        Measured on the image OCR actually consumed, i.e. after any
        orientation correction. That makes the reported `skew_angle` the
        RESIDUAL skew -- the small angle left after whole-page rotation
        was handled -- which is the number deskewing (3.4 step 2) needs.
        Measuring before would report 90 degrees for a sideways page and
        say nothing about whether its text is level.

        Only ever adds to the page. Nothing here can fail a document:
        production prompt 3.3 says a page is never rejected for quality,
        and `quality.assess_page` does not raise.
        """
        if not self._settings.ocr_quality_enabled:
            return

        width_pt = getattr(geometry, "width_pt", None) if geometry is not None else None
        height_pt = getattr(geometry, "height_pt", None) if geometry is not None else None
        native_px = None
        getter = getattr(layout_doc, "native_pixel_size_for_page", None)
        if callable(getter):
            native_px = getter(page_index)

        measured, reasons = quality_mod.assess_page(
            image,
            width_pt=width_pt,
            height_pt=height_pt,
            native_pixel_size=native_px,
            min_dpi=self._settings.ocr_quality_min_dpi,
            min_blur=self._settings.ocr_quality_min_blur,
            min_contrast=self._settings.ocr_quality_min_contrast,
        )
        page_result.quality = measured
        if reasons:
            page_result.flags["poor_image_quality"] = True
            page_result.flags["poor_image_quality_reasons"] = reasons
            logger.info(
                "page %d flagged poor_image_quality (%s): dpi=%s blur=%s contrast=%s",
                page_result.page_number, ", ".join(reasons),
                measured.get("effective_dpi"), measured.get("blur_score"),
                measured.get("contrast"),
            )

    # ------------------------------------------------------------------
    # Per-page routing (production prompt 3.6) -- see routing.py
    # ------------------------------------------------------------------
    def _route(self, layout_doc, page_index: int, native_blocks, geometry):
        """Which of `native_text` / `paddleocr` / `hybrid` this page takes.

        With routing disabled this reproduces the old rule exactly -- any
        native text at all means native_text -- so the toggle is a real
        rollback and not merely a different set of bugs.
        """
        page_number = page_index + 1
        if not self._settings.ocr_routing_enabled:
            return routing.RouteDecision(
                route=routing.NATIVE_TEXT if native_blocks else routing.OCR,
                reason="routing disabled (OCR_ROUTING_ENABLED=false)",
                native_chars=sum(len(b.text) for b in native_blocks or []),
            )

        blocks = native_blocks or []
        native_text = "\n".join(b.text for b in blocks)
        native_boxes = [b.bbox for b in blocks if b.bbox]

        if geometry is None:
            # Every routing metric except the garbage check is an AREA
            # fraction, and without geometry there is no area to take a
            # fraction of. A `.docx` is the real case: reflowable, never
            # rasterized, so there is nothing to OCR even if we wanted to.
            # Falling back to the old rule is the only safe answer --
            # guessing a page size here would invent coverage numbers and
            # route real documents on them.
            return routing.RouteDecision(
                route=routing.NATIVE_TEXT if blocks else routing.OCR,
                reason="no page geometry; coverage cannot be measured",
                native_chars=len(native_text.strip()),
            )

        getter = getattr(layout_doc, "image_regions_for_page", None)
        image_boxes = getter(page_index, self._settings.ocr_render_dpi) if getter else []
        page_box = (0, 0, geometry.exact_width_px, geometry.exact_height_px)

        decision = routing.decide_route(
            native_text=native_text,
            native_boxes=native_boxes,
            image_boxes=image_boxes,
            page_box=page_box,
            max_garbage_ratio=self._settings.ocr_routing_max_garbage_ratio,
            min_plausibility=self._settings.ocr_routing_min_plausibility,
            min_uncovered_image_coverage=self._settings.ocr_routing_min_uncovered_image,
        )
        if decision.route != routing.NATIVE_TEXT and blocks:
            # Only worth a log line when the decision OVERRODE a text
            # layer that the old rule would have trusted -- that is the
            # case that used to fail silently, and the one an operator
            # investigating a bad read needs to see.
            logger.info(
                "page %d routed %s instead of native_text: %s "
                "(garbage %.2f, plausibility %.2f, text coverage %.2f, uncovered image %.2f)",
                page_number, decision.route, decision.reason,
                decision.garbage_ratio, decision.plausibility,
                decision.text_coverage, decision.uncovered_image_coverage,
            )
        return decision

    def _run_hybrid_page(self, layout_doc, page_index: int, native_blocks, decision, geometry):
        """A good text layer plus image regions it does not explain: keep
        the text, OCR the images, merge in reading order.

        Deliberately NOT orientation-corrected. A page whose native text
        layer reads correctly has already told us which way up it is, and
        rotating it would invalidate the native boxes that are being
        merged with the OCR ones -- the exact coordinate-space collision
        Step 2 existed to remove, which hybrid routing is the first thing
        to make possible.
        """
        page_number = page_index + 1
        page_image = layout_doc.render_page(page_index, self._settings.ocr_render_dpi)
        geometry = page_image.geometry or geometry

        positioned: list[tuple[float, float, dict]] = []
        for block in native_blocks or []:
            positioned.append((block.bbox[1], block.bbox[0], {
                "text": block.text,
                "bbox": list(block.bbox),
                "bbox_norm": _norm(geometry, block.bbox),
                "confidence": 1.0,
                "source": "native_text",
            }))

        ocr_confidences: list[float] = []
        for region in decision.ocr_regions:
            crop_box = (
                max(0, int(region[0])), max(0, int(region[1])),
                min(page_image.width_px, int(region[2])), min(page_image.height_px, int(region[3])),
            )
            if crop_box[2] <= crop_box[0] or crop_box[3] <= crop_box[1]:
                continue
            cropped = page_image.image.crop(crop_box)
            result = self._ocr_engine.recognize(cropped, lang=self._settings.ocr_languages)
            for word in result.words:
                # Crop-local pixels back into full-page pixels, so an OCR
                # block and a native block on this page are in one space.
                page_bbox = [
                    word.bbox[0] + crop_box[0], word.bbox[1] + crop_box[1],
                    word.bbox[2] + crop_box[0], word.bbox[3] + crop_box[1],
                ]
                positioned.append((page_bbox[1], page_bbox[0], {
                    "text": word.text,
                    "bbox": page_bbox,
                    "bbox_norm": _norm(geometry, page_bbox),
                    "confidence": word.confidence,
                    "source": "paddleocr",
                }))
                ocr_confidences.append(word.confidence)

        positioned.sort(key=lambda entry: (entry[0], entry[1]))
        blocks = [block for _, _, block in positioned]
        text = "\n".join(block["text"] for block in blocks)
        # Native text is certain; OCR is not. A page that is mostly
        # native with a small OCR'd region should not be reported as
        # uncertain as its worst word, nor as certain as its text layer.
        confidence = (
            sum(b["confidence"] for b in blocks) / len(blocks) if blocks else 0.0
        )
        logger.info(
            "page %d hybrid: %d native blocks + %d OCR'd blocks from %d image region(s)",
            page_number, len(native_blocks or []), len(ocr_confidences), len(decision.ocr_regions),
        )
        page_result = PageResult(
            page_number=page_number,
            method=routing.HYBRID,
            text=text,
            confidence=confidence,
            blocks=blocks,
            geometry=geometry,
            routing=decision.to_dict(),
        )
        # A hybrid page IS rasterized (that is what the image regions were
        # cropped from), so it gets the same quality assessment as a fully
        # OCR'd one. Measured on the whole page rather than on the crops:
        # the user is told to re-scan a page, not a region.
        self._assess_quality(page_result, page_image.image, layout_doc, page_index, geometry)
        lines = [(block["text"], tuple(block["bbox"])) for block in blocks]
        return page_result, lines

    def _run_full_document(
        self, layout_doc, deadline: DocumentDeadline | None = None
    ) -> tuple[list[PageResult], list[ExtractedField], dict[int, list[LineBlock]], dict]:
        """OS-FR-001/002, per the native-text-vs-paddleocr decision logic:
        a page with a real native text layer (Docling extracts it
        directly from the PDF) is used as-is -- it is never re-OCR'd. A
        page with no text layer (scanned, photographed, or an image-only
        PDF page) is rasterized and run through PaddleOCR instead.

        Also builds `lines_by_page` (1-indexed, real per-visual-line
        text+bbox) for extract_requested_fields' spatial field matching --
        native_text_lines_for_page (PyMuPDF's own line-level dict, NOT
        Docling's paragraph-merged native_text_blocks_for_page: see that
        method's own docstring for why) for a native-text page, or
        PaddleOCR's own `result.words` for a rasterized one -- each of
        ITS entries is already exactly one detected text LINE (per
        ocr_engine.py's own comment on `rec_texts`/`rec_boxes`), so no
        extra line-clustering is needed there either.

        The fourth return value maps a 1-indexed page number to its
        `orientation.PageOrientation` for every rasterized page, because
        `lines_by_page` is in the CORRECTED (reading) space while the
        output contract is in the page's own -- whoever consumes those
        lines owes their results a trip back through it.
        """
        pages: list[PageResult] = []
        lines_by_page: dict[int, list[LineBlock]] = {}
        line_confidences_by_page: dict[int, list] = {}
        orientation_by_page: dict[int, "Any"] = {}
        # Built INSIDE the page loop, so page N's raster is released before
        # page N+1 is rendered. Collecting the images to build the
        # rendition afterwards would add roughly a gigabyte on a 35-page
        # scan to a document that already peaks near its limit.
        pdf_builder = (
            searchable_pdf.SearchablePdfBuilder()
            if self._settings.ocr_searchable_pdf_enabled
            else None
        )
        for page_index in range(layout_doc.page_count()):  # PyMuPDF: 0-indexed
            page_number = page_index + 1  # output contract / Docling: 1-indexed
            _check_deadline(deadline, f"before page {page_number} of {layout_doc.page_count()}")
            native_blocks = layout_doc.native_text_blocks_for_page(page_number, self._settings.ocr_render_dpi)
            geometry = _page_geometry(layout_doc, page_index, self._settings.ocr_render_dpi)
            decision = self._route(layout_doc, page_index, native_blocks, geometry)

            if decision.route == routing.NATIVE_TEXT:
                tables = layout_doc.tables_for_page(page_number, self._settings.ocr_render_dpi) or []
                text, blocks, table_dicts = _merge_native_text_and_tables(native_blocks, tables, geometry)
                pages.append(PageResult(page_number=page_number, method="native_text", text=text, confidence=1.0, blocks=blocks, tables=table_dicts, geometry=geometry, routing=decision.to_dict()))
                native_lines = layout_doc.native_text_lines_for_page(page_index, self._settings.ocr_render_dpi)
                if native_lines:
                    lines_by_page[page_number] = [(b.text, b.bbox) for b in native_lines]
            elif decision.route == routing.HYBRID:
                _check_deadline(deadline, f"before hybrid page {page_number}")
                page_result, hybrid_lines = self._run_hybrid_page(
                    layout_doc, page_index, native_blocks, decision, geometry
                )
                pages.append(page_result)
                if hybrid_lines:
                    lines_by_page[page_number] = hybrid_lines
                    line_confidences_by_page[page_number] = [
                        (tuple(b["bbox"]), b["confidence"])
                        for b in page_result.blocks if b.get("bbox") and b.get("confidence") is not None
                    ]
            else:
                page_image = layout_doc.render_page(page_index, self._settings.ocr_render_dpi)
                # The rasterizer's own pixmap dimensions win over the
                # predicted ones -- PyMuPDF rounds, and normalizing an OCR
                # box against a size a pixel off from the image it was
                # detected in is a needless source of drift.
                geometry = page_image.geometry or geometry
                _check_deadline(deadline, f"before orienting page {page_number}")
                image_for_ocr, orientation = self._orient(page_image)
                result = self._ocr_engine.recognize(image_for_ocr, lang=self._settings.ocr_languages)
                # Every bbox goes back into the ORIGINAL page raster's
                # space before it leaves here -- OCR saw an upright page,
                # but the viewer and the review UI see the page as stored.
                # See orientation.PageOrientation.
                boxes = [
                    orientation.to_original(w.bbox) if orientation is not None else w.bbox
                    for w in result.words
                ]
                blocks = [
                    {
                        "text": w.text,
                        "bbox": list(bbox),
                        "bbox_norm": _norm(geometry, bbox),
                        "confidence": w.confidence,
                    }
                    for w, bbox in zip(result.words, boxes)
                ]
                ocr_page = PageResult(page_number=page_number, method="paddleocr", text=result.text, confidence=result.mean_confidence, blocks=blocks, geometry=geometry, preprocessing=self._preprocessing_dict(orientation), routing=decision.to_dict())
                self._assess_quality(ocr_page, image_for_ocr, layout_doc, page_index, geometry)
                pages.append(ocr_page)
                if orientation is not None:
                    orientation_by_page[page_number] = orientation
                if result.words:
                    # Deliberately the UNROTATED-back boxes: spatial field
                    # matching asks "what is to the right of / below this
                    # label", which only means anything in reading
                    # orientation. On a page that arrived sideways, "right
                    # of" in the stored raster is "below" to a reader.
                    # These are mapped back to the page's own space once
                    # matching is done -- see _run_inference.
                    lines_by_page[page_number] = [(w.text, w.bbox) for w in result.words]
                    # Per-line recognition confidence, so a field's score
                    # can carry the uncertainty of the tokens it actually
                    # came from (production prompt 3.10). Only the OCR'd
                    # paths have one; a native-text page has no
                    # character-level uncertainty and is left absent,
                    # which field_confidence reads as 1.0.
                    line_confidences_by_page[page_number] = [(w.bbox, w.confidence) for w in result.words]

            # The rendition page, for every route. A native-text page is
            # included deliberately: the rendition is one artifact for the
            # whole document, and skipping pages that happened to have a
            # text layer would produce a PDF missing pages 3 and 7 -- a
            # worse object than not producing one at all.
            #
            # `page_image` is the ORIGINAL raster, never the
            # orientation-corrected one: the rendition must look like the
            # document as stored, and the word boxes are in that same
            # space (they were mapped back after OCR).
            if pdf_builder is not None:
                self._add_rendition_page(
                    pdf_builder, layout_doc, page_index, page_number,
                    pages[-1] if pages else None,
                    orientation_by_page.get(page_number),
                )

        full_text = "\n\n".join(p.text for p in pages)
        overall_confidence = sum(p.confidence for p in pages) / len(pages) if pages else 0.0
        extracted_fields = extract_structured_fields(full_text, base_confidence=overall_confidence)
        rendition = searchable_pdf.result_of(pdf_builder) if pdf_builder is not None else (None, [])
        return (pages, extracted_fields, lines_by_page, orientation_by_page,
                line_confidences_by_page, rendition)

    def _add_rendition_page(self, builder, layout_doc, page_index: int, page_number: int,
                            page_result, orientation=None) -> None:
        """Copy this page from the source into the rendition, overlaying
        invisible text only where recognition actually happened.

        Copying rather than rendering is what keeps a 300 DPI scan at 300
        DPI: the rendition must not hand a user back a copy downsampled to
        whatever `OCR_RENDER_MAX_MP` OCR happened to need. It also means
        no page raster is decoded here at all.

        Never raises; the builder records its own per-page reasons.
        """
        if page_result is None or page_result.page_number != page_number:
            return
        source_doc = None
        getter = getattr(layout_doc, "source_document", None)
        if callable(getter):
            source_doc = getter()
        if source_doc is None:
            # A .docx has no page to copy -- it is reflowable and was
            # never rasterized. Recording the reason is the honest
            # outcome; a rendition of it would be an invention.
            builder.failures.append(f"page {page_number}: source has no copyable page")
            return

        geometry = page_result.geometry
        scale = getattr(geometry, "render_scale", 0.0) or 0.0
        builder.add_source_page(
            source_doc, page_index,
            searchable_pdf.boxes_for_overlay(page_result),
            pixels_per_point=scale,
            # A page that arrived sideways has boxes mapped back into its
            # own space, which leaves them tall and narrow -- the run has
            # to be rotated to follow the ink. See `text_rotation_for`.
            text_rotation=searchable_pdf.text_rotation_for(orientation),
        )

    def _run_zone_ocr(
        self, layout_doc, regions: list[ZoneRegion], deadline: DocumentDeadline | None = None
    ) -> tuple[list[PageResult], list[ExtractedField]]:
        """OS-FR-003: always PaddleOCR on the cropped region, never a
        native-text lookup -- the decision logic's item 4 is explicit that
        zone/rubber-band OCR always runs PaddleOCR regardless of whether
        the underlying page has a native text layer, since the user (or
        the template) is asking about a specific visual region.
        """
        pages_by_number: dict[int, PageResult] = {}
        extracted_fields: list[ExtractedField] = []
        rendered_pages: dict[int, "Any"] = {}

        for region in regions:
            _check_deadline(deadline, f"before zone {region.name!r}")
            if region.page not in rendered_pages:
                rendered_pages[region.page] = layout_doc.render_page(region.page, self._settings.ocr_render_dpi)
            page_image = rendered_pages[region.page]
            page_number = region.page + 1

            crop_box = region.to_pixel_box(page_image.width_px, page_image.height_px)
            cropped = page_image.image.crop(crop_box)
            result = self._ocr_engine.recognize(cropped, lang=self._settings.ocr_languages)

            offset_x, offset_y = crop_box[0], crop_box[1]
            geometry = page_image.geometry
            blocks = []
            for w in result.words:
                # Re-based from crop-local pixels into full-page pixels
                # before normalizing, so a zone result's coordinates mean
                # the same thing as a full-page one's.
                page_bbox = [
                    w.bbox[0] + offset_x, w.bbox[1] + offset_y,
                    w.bbox[2] + offset_x, w.bbox[3] + offset_y,
                ]
                blocks.append({
                    "text": w.text,
                    "bbox": page_bbox,
                    "bbox_norm": _norm(geometry, page_bbox),
                    "confidence": w.confidence,
                })

            page_result = pages_by_number.setdefault(page_number, PageResult(page_number=page_number, method="paddleocr", text="", confidence=0.0, blocks=[], geometry=geometry))
            page_result.text = f"{page_result.text}\n[{region.name}] {result.text}".strip()
            page_result.blocks.extend(blocks)

            extracted_fields.append(
                field_for_zone_name(region.field, result.text, result.mean_confidence, region.name, bbox=crop_box, page=page_number)
            )

        # Recompute each page's confidence as the mean of its own blocks
        # (a page can accumulate zones from multiple regions).
        for page_result in pages_by_number.values():
            confidences = [b["confidence"] for b in page_result.blocks]
            page_result.confidence = sum(confidences) / len(confidences) if confidences else 0.0

        pages = sorted(pages_by_number.values(), key=lambda p: p.page_number)
        return pages, extracted_fields

    # ------------------------------------------------------------------
    # On-demand synchronous flows (OS-FR-003 named zones, OS-FR-004 rubber-band)
    # ------------------------------------------------------------------
    def process_zone_request(self, version_id: uuid.UUID, zone_names: list[str] | None, correlation_id: uuid.UUID | None = None, read_token: str | None = None) -> ProcessingOutcome:
        with session_scope(self._session_factory) as session:
            repo = OcrRepository(session)
            ctx = repo.get_version_for_processing(version_id)
            repo.ensure_queued_job(tenant_id=ctx.version.tenant_id, version_id=version_id, correlation_id=correlation_id)
            job = repo.claim_job(version_id=version_id, lease_seconds=self._settings.ocr_lease_duration_s)
            if job is None:
                raise ValueError(f"version {version_id} already has an OCR job in flight")

            try:
                all_regions = repo.get_zone_regions(ctx.document_type_id)
                if not all_regions:
                    raise ValueError(f"no zone template configured for document type {ctx.document_type_name!r}")
                regions = [r for r in all_regions if zone_names is None or r.name in zone_names]
                if not regions:
                    raise ValueError(f"none of {zone_names} match the configured zones for {ctx.document_type_name!r}")

                if ctx.version.mime_type not in SUPPORTED_MIME_TYPES:
                    raise UnsupportedFormatError(ctx.version.mime_type)

                file_bytes = self._fetch_document(version_id=ctx.version.version_id, read_token=read_token)
                with self._layout_engine.load(
                    file_bytes, ctx.version.mime_type, ctx.version.original_filename,
                    max_megapixels=self._settings.ocr_render_max_mp,
                ) as layout_doc:
                    pages, extracted_fields = self._run_zone_ocr(layout_doc, regions)

                # Zone-derived fields carry a real page and a real bbox (the
                # zone's own pixel box), so unlike the regex path they have
                # something to normalize -- and the review UI's highlights
                # come from exactly these.
                zone_geometry_by_page = {p.page_number: p.geometry for p in pages if p.geometry is not None}
                return self._finalize_success(
                    repo=repo, job=job, ctx=ctx, pages=pages,
                    fields=fields_to_dict(extracted_fields, zone_geometry_by_page),
                    correlation_id=correlation_id,
                )
            except (VersionNotFound, VersionNotEligible):
                raise
            except Exception as exc:  # noqa: BLE001
                return self._finalize_failure(repo=repo, job=job, ctx=ctx, exc=exc, correlation_id=correlation_id)


    # ------------------------------------------------------------------
    # Read-only operations for the review UI (Phase 4c).
    #
    # Neither claims a job, writes a row, or publishes an event. That is
    # the whole point: a reviewer scrolling a document or dragging a box
    # over a number is INSPECTING it, and the existing
    # process_rubber_band_request finalizes -- it replaces the version's
    # entire field set and page list with whatever the one crop produced.
    # Wiring the review screen to that would wipe every other field on
    # the document each time somebody re-checked one of them.
    # ------------------------------------------------------------------

    def render_page_png(
        self, version_id: uuid.UUID, page: int, dpi: int | None = None, read_token: str | None = None
    ) -> "PageRender":
        """One page as a PNG, in the SAME frame the stored bboxes live in.

        Rendered on demand rather than stored: the review screen looks at
        a handful of pages of a small minority of documents, and keeping a
        PNG per page of every document forever to serve that would cost
        far more storage than it saves work. The render is the cheap half
        of OCR (no inference), so it is not worth a cache either.

        The frame matters more than the resolution. `bbox_norm` is
        normalized against the page geometry produced by this same
        `render_page` after the same rotation normalization, and
        orientation-corrected boxes were already mapped back to it by
        `_restore_field_bboxes` -- so a highlight drawn at
        (x0*w, y0*h, x1*w, y1*h) over this image lands where the text is,
        at any DPI.
        """
        import io

        dpi = dpi or self._settings.ocr_preview_dpi
        with session_scope(self._session_factory) as session:
            repo = OcrRepository(session)
            ctx = repo.get_version_for_processing(version_id)
            if ctx.version.mime_type not in SUPPORTED_MIME_TYPES:
                raise UnsupportedFormatError(ctx.version.mime_type)
            file_bytes = self._fetch_document(version_id=ctx.version.version_id, read_token=read_token)

        with self._layout_engine.load(
            file_bytes, ctx.version.mime_type, ctx.version.original_filename,
            max_megapixels=self._settings.ocr_render_max_mp,
        ) as layout_doc:
            page_count = layout_doc.page_count()
            if page < 0 or page >= page_count:
                raise ValueError(f"page {page} out of range (document has {page_count} pages)")
            page_image = layout_doc.render_page(page, dpi)
            buffer = io.BytesIO()
            page_image.image.convert("RGB").save(buffer, format="PNG", optimize=True)
            return PageRender(
                png=buffer.getvalue(),
                width_px=page_image.width_px,
                height_px=page_image.height_px,
                page_count=page_count,
            )

    def preview_rubber_band(
        self, version_id: uuid.UUID, page: int, box: tuple[float, float, float, float],
        read_token: str | None = None
    ) -> dict:
        """OCR one box and hand back what it says. Nothing is written.

        The reviewer decides whether the answer is right; only their
        decision is persisted, by dms-core, as a correction.
        """
        validate_normalized_box(*box)

        with session_scope(self._session_factory) as session:
            repo = OcrRepository(session)
            ctx = repo.get_version_for_processing(version_id)
            if ctx.version.mime_type not in SUPPORTED_MIME_TYPES:
                raise UnsupportedFormatError(ctx.version.mime_type)
            file_bytes = self._fetch_document(version_id=ctx.version.version_id, read_token=read_token)

        with self._layout_engine.load(
            file_bytes, ctx.version.mime_type, ctx.version.original_filename,
            max_megapixels=self._settings.ocr_render_max_mp,
        ) as layout_doc:
            page_count = layout_doc.page_count()
            if page < 0 or page >= page_count:
                raise ValueError(f"page {page} out of range (document has {page_count} pages)")
            page_image = layout_doc.render_page(page, self._settings.ocr_render_dpi)
            x0, y0, x1, y1 = box
            pixel_box = (
                round(x0 * page_image.width_px), round(y0 * page_image.height_px),
                round(x1 * page_image.width_px), round(y1 * page_image.height_px),
            )
            cropped = page_image.image.crop(pixel_box)
            result = self._ocr_engine.recognize(cropped, lang=self._settings.ocr_languages)

        return {
            "version_id": str(version_id),
            "page": page,
            "bbox_norm": [x0, y0, x1, y1],
            "text": result.text,
            "confidence": result.mean_confidence,
            "words": [
                {"text": w.text, "confidence": w.confidence}
                for w in result.words
            ],
        }

    def process_rubber_band_request(
        self,
        version_id: uuid.UUID,
        page: int,
        box: tuple[float, float, float, float],
        correlation_id: uuid.UUID | None = None,
        read_token: str | None = None,
    ) -> ProcessingOutcome:
        validate_normalized_box(*box)  # ambiguous/degenerate selection -> ValueError, caller maps to 422

        with session_scope(self._session_factory) as session:
            repo = OcrRepository(session)
            ctx = repo.get_version_for_processing(version_id)
            repo.ensure_queued_job(tenant_id=ctx.version.tenant_id, version_id=version_id, correlation_id=correlation_id)
            job = repo.claim_job(version_id=version_id, lease_seconds=self._settings.ocr_lease_duration_s)
            if job is None:
                raise ValueError(f"version {version_id} already has an OCR job in flight")

            try:
                if ctx.version.mime_type not in SUPPORTED_MIME_TYPES:
                    raise UnsupportedFormatError(ctx.version.mime_type)

                file_bytes = self._fetch_document(version_id=ctx.version.version_id, read_token=read_token)
                with self._layout_engine.load(
                    file_bytes, ctx.version.mime_type, ctx.version.original_filename,
                    max_megapixels=self._settings.ocr_render_max_mp,
                ) as layout_doc:
                    page_image = layout_doc.render_page(page, self._settings.ocr_render_dpi)
                    x0, y0, x1, y1 = box
                    pixel_box = (
                        round(x0 * page_image.width_px), round(y0 * page_image.height_px),
                        round(x1 * page_image.width_px), round(y1 * page_image.height_px),
                    )
                    cropped = page_image.image.crop(pixel_box)
                    result = self._ocr_engine.recognize(cropped, lang=self._settings.ocr_languages)

                offset_x, offset_y = pixel_box[0], pixel_box[1]
                geometry = page_image.geometry
                blocks = []
                for w in result.words:
                    page_bbox = [
                        w.bbox[0] + offset_x, w.bbox[1] + offset_y,
                        w.bbox[2] + offset_x, w.bbox[3] + offset_y,
                    ]
                    blocks.append({
                        "text": w.text,
                        "bbox": page_bbox,
                        "bbox_norm": _norm(geometry, page_bbox),
                        "confidence": w.confidence,
                    })
                pages = [PageResult(page_number=page + 1, method="paddleocr", text=result.text, confidence=result.mean_confidence, blocks=blocks, geometry=geometry)]
                # No geometry map passed deliberately: extract_structured_fields
                # is the regex path over concatenated text, which never sets a
                # page or a bbox (see its own docstring), so there is nothing
                # to normalize and `bbox_norm` is correctly null.
                fields = fields_to_dict(extract_structured_fields(result.text, base_confidence=result.mean_confidence))

                return self._finalize_success(repo=repo, job=job, ctx=ctx, pages=pages, fields=fields, correlation_id=correlation_id)
            except (VersionNotFound, VersionNotEligible):
                raise
            except Exception as exc:  # noqa: BLE001
                return self._finalize_failure(repo=repo, job=job, ctx=ctx, exc=exc, correlation_id=correlation_id)

    # ------------------------------------------------------------------
    # Shared success/failure handling -- builds the single output JSON
    # (stored at extracted_text_ref AND returned directly by the
    # dev-trigger endpoint, per the "Output contract"), writes it, and
    # commits ocr_outputs + document_versions.ocr_status +
    # processing_jobs + outbox_events in one transaction.
    # ------------------------------------------------------------------
    def _finalize_success(
        self,
        *,
        repo: OcrRepository,
        job,
        ctx,
        pages: list[PageResult],
        fields: dict,
        correlation_id: uuid.UUID | None,
        field_results: list | None = None,
        file_repaired: bool = False,
        requested_field_specs: list | None = None,
        validation_failures: list[str] | None = None,
        validation_errors: list[dict] | None = None,
        line_items: list | None = None,
        read_token: str | None = None,
        rendition: tuple | None = None,
        page_count: int | None = None,
    ) -> ProcessingOutcome:
        overall_confidence = sum(p.confidence for p in pages) / len(pages) if pages else 0.0
        decision = self._confidence.evaluate(overall_confidence)

        # `requires_review` is no longer a synonym for `low_confidence`
        # (production prompt 3.10). The mean-page-confidence rule is kept
        # ONLY as `low_confidence` -- "the model was unsure" -- while
        # "a human must look" is now its own decision over three
        # independent inputs. A mean over pages is dominated by body text
        # and cannot see the case that matters: one mandatory field empty
        # on an otherwise confident 40-page document, or a total that
        # contradicts its own line items at perfect confidence.
        mandatory_keys = {
            spec.field_key for spec in (requested_field_specs or []) if spec.is_mandatory
        }
        review = review_mod.decide(
            field_results=field_results,
            mandatory_keys=mandatory_keys,
            validation_failures=validation_failures,
            pages=pages,
            file_repaired=file_repaired,
        )
        flags = {"low_confidence": decision.flagged, "requires_review": review.requires_review}
        flags.update({k: v for k, v in review.to_dict().items() if k != "requires_review"})
        if file_repaired:
            flags["file_repaired"] = True

        # Written BEFORE the output JSON, so a rendition that failed can
        # say so IN that JSON. Production prompt's no-silent-fallback
        # rule: "the rendition is simply absent" is indistinguishable
        # from "this deployment has renditions turned off", and a user
        # asking why they cannot search a document deserves the reason.
        rendition_failures = self._store_searchable_pdf(repo=repo, ctx=ctx, rendition=rendition,
                                                       read_token=read_token)
        if rendition_failures:
            flags["searchable_pdf_failed"] = True
            flags["searchable_pdf_failure_reasons"] = rendition_failures

        output_id = uuid.uuid4()
        output_json = {
            # Added in Phase 2, not bumped: v1 had no version marker at
            # all, so a consumer reading an old artifact sees the key
            # missing and should treat that as 1. Distinct from
            # events.py's SCHEMA_VERSION, which versions the *event*
            # payloads and is unrelated.
            #
            # v2 adds, additively per section 3.11: `bbox_norm` on every
            # block, table and cell; `geometry` on every page; and
            # `bbox_norm` on every field. Every v1 key keeps its meaning,
            # so dms-core's applyOcrFields needs no change.
            "schema_version": OUTPUT_SCHEMA_VERSION,
            "document_version_id": str(ctx.version.version_id),
            "text": "\n\n".join(p.text for p in pages),
            "pages": [p.to_dict() for p in pages],
            "fields": fields,
            "flags": flags,
        }
        # Only present when there are any. An empty list would read as
        # "this invoice has no lines", which is a claim; absence reads as
        # "no line-items table was found", which is the truth for every
        # non-invoice and every scanned invoice (see line_items.py).
        if line_items:
            output_json["line_items"] = [item.to_dict() for item in line_items]
        if validation_errors:
            # The failing rules, verbatim, next to the fields they
            # downgraded -- so a reviewer sees WHY a value is flagged
            # rather than only that it is.
            output_json["validation_errors"] = validation_errors
        # Written through dms-core, not straight to a bucket: OCR output IS the
        # document's text, so it is encrypted and keyed under the document like
        # everything else, and the purge path can find it.
        object_key = self._content.put_output(
            ReadGrant(token=read_token or "", version_id=str(ctx.version.version_id)),
            json.dumps(output_json, ensure_ascii=False, indent=2).encode("utf-8"),
        )

        payload = build_ocr_completed_payload(
            tenant_id=ctx.version.tenant_id,
            document_id=ctx.document.document_id,
            version_id=ctx.version.version_id,
            ocr_output_id=output_id,
            extracted_text_ref=object_key,
            page_count=len(pages),
            flags=flags,
            correlation_id=correlation_id,
            attempt=job.attempts,
        )
        repo.complete_success(
            job=job,
            version=ctx.version,
            extracted_text_ref=object_key,
            language=self._settings.ocr_languages,
            event_payload=payload,
            review=review.to_dict(),
            page_count=page_count,
        )
        # Rebuilt from `pages` rather than threaded through the inference
        # tuple: it is the same one-line expression `_run_inference` uses,
        # and `pages` is already here. It must be the page's OWN geometry,
        # matching `_restore_field_bboxes` having already mapped every
        # bbox back out of the corrected reading space -- the same pairing
        # `requested_fields_to_output_dict` relies on for `bbox_norm`.
        geometry_by_page = {p.page_number: p.geometry for p in pages if p.geometry is not None}
        if field_results is not None:
            repo.upsert_field_results(
                tenant_id=ctx.version.tenant_id,
                document_id=ctx.document.document_id,
                version_id=ctx.version.version_id,
                results=field_results,
                geometry_by_page=geometry_by_page,
            )
        # Always called, including with an empty list: a re-run that finds
        # NO line items must clear the previous run's, or a document whose
        # table stopped being detected keeps showing stale lines forever.
        if line_items is not None:
            repo.replace_line_items(
                tenant_id=ctx.version.tenant_id,
                document_id=ctx.document.document_id,
                version_id=ctx.version.version_id,
                items=line_items,
                geometry_by_page=geometry_by_page,
            )
        return ProcessingOutcome(status="completed", version_id=ctx.version.version_id, output=output_json)

    def _store_searchable_pdf(self, *, repo: OcrRepository, ctx, rendition: tuple | None,
                              read_token: str | None = None) -> list[str]:
        """Upload the rendition and record it (production prompt 3.11).
        Returns the reasons it did not fully happen, empty when it did.

        **Upload first, row second.** A `renditions` row must never point
        at an object that is not there; the reverse (an object with no
        row) is harmless garbage. Neither failure is allowed to fail the
        document -- its text, fields and line items are already committed
        -- but both are *reported*, which is the difference between a
        fallback and a silent one.
        """
        data, failures = rendition if rendition else (None, [])
        failures = list(failures)
        if not data:
            if failures:
                logger.warning(
                    "No searchable PDF for version %s: %s",
                    ctx.version.version_id, "; ".join(failures),
                )
            return failures
        # One call, not upload-then-record. dms-core stores the PDF and upserts
        # the renditions row in the same transaction, so the row can no longer
        # disagree with what is in storage - which the old two-step could do,
        # and which the second `except` below used to exist to report.
        #
        # It also means the key is dms-core's to choose. The worker no longer
        # computes one: two definitions of the same key format is how the row
        # ends up pointing at an object that isn't there.
        try:
            object_key = self._content.put_rendition(
                ReadGrant(token=read_token or "", version_id=str(ctx.version.version_id)), data
            )
            logger.info(
                "Searchable PDF rendition for version %s: %d bytes at %s",
                ctx.version.version_id, len(data), object_key,
            )
        except Exception as exc:  # noqa: BLE001 -- see docstring
            logger.exception(
                "Could not store the searchable PDF for version %s; no rendition recorded",
                ctx.version.version_id,
            )
            failures.append(f"upload: {type(exc).__name__}: {exc}")
        return failures

    def _finalize_failure(self, *, repo: OcrRepository, job, ctx, exc: Exception, correlation_id: uuid.UUID | None) -> ProcessingOutcome:
        logger.exception("OCR processing failed for version %s", ctx.version.version_id)
        # Classified error code (errors.py) rather than a raw, unclassified
        # str(exc) -- see docs/ocr/failure-analysis.md #9's live finding: a
        # real encrypted PDF and a real corrupt PDF both surfaced opaque
        # internal exception text (Docling's own parser error; PyMuPDF's
        # own FileDataError) with no code a caller could act on. Anything
        # not already one of the specific OcrEngineError subclasses is
        # wrapped as the generic ENGINE_ERROR catch-all -- still classified,
        # never raw.
        error_message = exc.formatted() if isinstance(exc, OcrEngineError) else EngineError(str(exc)).formatted()

        # A TIMEOUT this worker recorded gets the same budget as one the
        # reaper inferred from a dead worker. Without this, `TIMEOUT=1`
        # meant one retry when the pool ceiling killed the worker and zero
        # when the in-worker deadline fired first -- the same cause, a
        # different budget, decided only by which of two timers won.
        #
        # Deliberately TIMEOUT and nothing else. Extending it to every
        # code was tried and is wrong: an in-worker failure is a failure
        # the worker survived long enough to *classify*, and the
        # classified ones are deterministic. A document dms-core will not serve
        # (ENGINE_ERROR) fails identically on all four attempts, so the
        # retries buy nothing and cost a pool worker each. The codes that
        # genuinely benefit from a retry are the ones where the process
        # died and left no diagnosis -- which is the reaper's half, and is
        # already handled there.
        code = exc.code if isinstance(exc, OcrEngineError) else EngineError.code
        max_attempts = self._settings.max_attempts_for(code)
        if isinstance(exc, OcrTimeoutError) and job.attempts < max_attempts:
            backoff_seconds = self._settings.ocr_reaper_backoff_base_s * job.attempts
            repo.requeue_after_failure(job=job, error=error_message, backoff_seconds=backoff_seconds)
            logger.warning(
                "Version %s failed with %s on attempt %d/%d; requeued with a %ds backoff "
                "(the reaper re-requests it once that has elapsed)",
                ctx.version.version_id, code, job.attempts, max_attempts, backoff_seconds,
            )
            return ProcessingOutcome(status="failed", version_id=ctx.version.version_id, error=error_message)

        payload = build_ocr_failed_payload(
            tenant_id=ctx.version.tenant_id,
            document_id=ctx.document.document_id,
            version_id=ctx.version.version_id,
            error=error_message,
            correlation_id=correlation_id,
            attempt=job.attempts,
        )
        repo.complete_failure(job=job, version=ctx.version, error=error_message, event_payload=payload)
        return ProcessingOutcome(status="failed", version_id=ctx.version.version_id, error=error_message)
