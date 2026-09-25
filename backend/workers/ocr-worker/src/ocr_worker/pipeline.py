from __future__ import annotations

import logging
import os
import time
import uuid
from dataclasses import dataclass, field

from sqlalchemy.orm import sessionmaker, Session

from .confidence import ConfidenceEvaluator
from .config import OCR_TIMEOUT_EXIT_CODE, Settings
from .db import session_scope
from .deadline import DocumentDeadline
from .errors import (
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
from .repository import OcrRepository, VersionNotEligible, VersionNotFound
from .storage import ObjectStorage, ocr_output_key
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


def _merge_native_text_and_tables(native_blocks, tables) -> tuple[str, list[dict], list[dict]]:
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
        positioned.append((b.bbox[1], b.bbox[0], [{"text": b.text, "bbox": list(b.bbox), "confidence": 1.0}], None))

    for t in tables:
        ordered_cells = sorted(t.cells, key=lambda c: (c.row, c.col))
        cell_blocks = [
            {"text": c.text, "bbox": list(c.bbox) if c.bbox else None, "confidence": 1.0}
            for c in ordered_cells
            if c.text
        ]
        table_dict = {
            "bbox": list(t.bbox),
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


@dataclass
class PageResult:
    """One entry of the output contract's `pages` array. `page_number` is
    1-indexed, matching the contract's own example and Docling's native
    numbering. `blocks` bboxes are always in the full page's pixel space
    at `ocr_render_dpi`, regardless of which method produced them, so a
    consumer can treat native_text and paddleocr blocks uniformly.

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

    def to_dict(self) -> dict:
        return {
            "page_number": self.page_number,
            "method": self.method,
            "text": self.text,
            "confidence": self.confidence,
            "blocks": self.blocks,
            "tables": self.tables,
        }


@dataclass
class ProcessingOutcome:
    status: str  # "completed" | "failed" | "skipped"
    version_id: uuid.UUID
    output: dict | None = None  # the exact JSON also stored at extracted_text_ref -- see "Output contract"
    error: str | None = None


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
        storage: ObjectStorage,
        layout_engine: LayoutEngine,
        ocr_engine: OcrEngine,
    ):
        self._settings = settings
        self._session_factory = session_factory
        self._storage = storage
        self._layout_engine = layout_engine
        self._ocr_engine = ocr_engine
        self._confidence = ConfidenceEvaluator(settings.min_confidence_threshold)

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
        2. RUN -- no open transaction at all. MinIO fetch, Docling
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
            pages, fields, field_results, file_repaired = self._run_inference(
                mime_type=mime_type,
                storage_object_key=storage_object_key,
                original_filename=original_filename,
                zone_regions=zone_regions,
                requested_fields=requested_fields,
                deadline=deadline,
            )
            result: tuple | Exception = (pages, fields, field_results, file_repaired)
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
            pages, fields, field_results, file_repaired = result
            return self._finalize_success(
                repo=repo, job=job, ctx=ctx, pages=pages, fields=fields, field_results=field_results,
                correlation_id=correlation_id, file_repaired=file_repaired,
            )

    def _run_inference(
        self,
        *,
        mime_type: str,
        storage_object_key: str,
        original_filename: str,
        zone_regions: list,
        requested_fields: list[FieldSpec] | None,
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
        file_bytes = self._storage.get_object_bytes(storage_object_key)
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

        with self._layout_engine.load(file_bytes, mime_type, original_filename, on_page_count=on_page_count) as layout_doc:
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
            else:
                # No zone/field template configured for this document
                # type -- extracted_fields here is the LEGACY generic
                # regex fallback (invoice_number/total_amount/...), not a
                # real zone match. It must never be fed to
                # extract_requested_fields as a "zone template hit"
                # (priority 1) -- that would let an unrelated regex guess
                # bypass the new dispatch's own, more field-appropriate
                # strategy for a requested field.
                pages, extracted_fields, lines_by_page = self._run_full_document(layout_doc, deadline=deadline)
                zone_derived_fields = []
            file_repaired = getattr(layout_doc, "file_repaired", False)

        field_results = None
        if requested_fields is not None:
            full_text = "\n\n".join(p.text for p in pages)
            zone_fields_by_key = {f.field: f for f in zone_derived_fields}
            field_results = extract_requested_fields(
                specs=requested_fields,
                text=full_text,
                zone_fields=zone_fields_by_key,
                lines_by_page=lines_by_page,
                field_confidence_threshold=self._settings.field_low_confidence_threshold,
            )
            fields = requested_fields_to_output_dict(field_results)
        else:
            fields = fields_to_dict(extracted_fields)

        return pages, fields, field_results, file_repaired

    def _run_full_document(
        self, layout_doc, deadline: DocumentDeadline | None = None
    ) -> tuple[list[PageResult], list[ExtractedField], dict[int, list[LineBlock]]]:
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
        """
        pages: list[PageResult] = []
        lines_by_page: dict[int, list[LineBlock]] = {}
        for page_index in range(layout_doc.page_count()):  # PyMuPDF: 0-indexed
            page_number = page_index + 1  # output contract / Docling: 1-indexed
            _check_deadline(deadline, f"before page {page_number} of {layout_doc.page_count()}")
            native_blocks = layout_doc.native_text_blocks_for_page(page_number, self._settings.ocr_render_dpi)

            if native_blocks:
                tables = layout_doc.tables_for_page(page_number, self._settings.ocr_render_dpi) or []
                text, blocks, table_dicts = _merge_native_text_and_tables(native_blocks, tables)
                pages.append(PageResult(page_number=page_number, method="native_text", text=text, confidence=1.0, blocks=blocks, tables=table_dicts))
                native_lines = layout_doc.native_text_lines_for_page(page_index, self._settings.ocr_render_dpi)
                if native_lines:
                    lines_by_page[page_number] = [(b.text, b.bbox) for b in native_lines]
            else:
                page_image = layout_doc.render_page(page_index, self._settings.ocr_render_dpi)
                result = self._ocr_engine.recognize(page_image.image, lang=self._settings.ocr_languages)
                blocks = [{"text": w.text, "bbox": list(w.bbox), "confidence": w.confidence} for w in result.words]
                pages.append(PageResult(page_number=page_number, method="paddleocr", text=result.text, confidence=result.mean_confidence, blocks=blocks))
                if result.words:
                    lines_by_page[page_number] = [(w.text, w.bbox) for w in result.words]

        full_text = "\n\n".join(p.text for p in pages)
        overall_confidence = sum(p.confidence for p in pages) / len(pages) if pages else 0.0
        extracted_fields = extract_structured_fields(full_text, base_confidence=overall_confidence)
        return pages, extracted_fields, lines_by_page

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
            blocks = [
                {"text": w.text, "bbox": [w.bbox[0] + offset_x, w.bbox[1] + offset_y, w.bbox[2] + offset_x, w.bbox[3] + offset_y], "confidence": w.confidence}
                for w in result.words
            ]

            page_result = pages_by_number.setdefault(page_number, PageResult(page_number=page_number, method="paddleocr", text="", confidence=0.0, blocks=[]))
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
    def process_zone_request(self, version_id: uuid.UUID, zone_names: list[str] | None, correlation_id: uuid.UUID | None = None) -> ProcessingOutcome:
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

                file_bytes = self._storage.get_object_bytes(ctx.version.storage_object_key)
                with self._layout_engine.load(file_bytes, ctx.version.mime_type, ctx.version.original_filename) as layout_doc:
                    pages, extracted_fields = self._run_zone_ocr(layout_doc, regions)

                return self._finalize_success(
                    repo=repo, job=job, ctx=ctx, pages=pages, fields=fields_to_dict(extracted_fields), correlation_id=correlation_id
                )
            except (VersionNotFound, VersionNotEligible):
                raise
            except Exception as exc:  # noqa: BLE001
                return self._finalize_failure(repo=repo, job=job, ctx=ctx, exc=exc, correlation_id=correlation_id)

    def process_rubber_band_request(
        self,
        version_id: uuid.UUID,
        page: int,
        box: tuple[float, float, float, float],
        correlation_id: uuid.UUID | None = None,
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

                file_bytes = self._storage.get_object_bytes(ctx.version.storage_object_key)
                with self._layout_engine.load(file_bytes, ctx.version.mime_type, ctx.version.original_filename) as layout_doc:
                    page_image = layout_doc.render_page(page, self._settings.ocr_render_dpi)
                    x0, y0, x1, y1 = box
                    pixel_box = (
                        round(x0 * page_image.width_px), round(y0 * page_image.height_px),
                        round(x1 * page_image.width_px), round(y1 * page_image.height_px),
                    )
                    cropped = page_image.image.crop(pixel_box)
                    result = self._ocr_engine.recognize(cropped, lang=self._settings.ocr_languages)

                offset_x, offset_y = pixel_box[0], pixel_box[1]
                blocks = [
                    {"text": w.text, "bbox": [w.bbox[0] + offset_x, w.bbox[1] + offset_y, w.bbox[2] + offset_x, w.bbox[3] + offset_y], "confidence": w.confidence}
                    for w in result.words
                ]
                pages = [PageResult(page_number=page + 1, method="paddleocr", text=result.text, confidence=result.mean_confidence, blocks=blocks)]
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
    ) -> ProcessingOutcome:
        overall_confidence = sum(p.confidence for p in pages) / len(pages) if pages else 0.0
        decision = self._confidence.evaluate(overall_confidence)
        # file_repaired (docs/ocr/failure-analysis.md #9's live finding:
        # PyMuPDF silently repairs far more PDF corruption than expected,
        # e.g. 50% truncation, and still produces a real completed result)
        # forces requires_review even when confidence alone wouldn't --
        # the extracted content may be genuinely correct, but it came from
        # a file that wasn't intact, so a human should double-check it
        # rather than it being silently trusted like any other completed
        # result.
        flags = {"low_confidence": decision.flagged, "requires_review": decision.flagged or file_repaired}
        if file_repaired:
            flags["file_repaired"] = True

        output_id = uuid.uuid4()
        object_key = ocr_output_key(ctx.version.tenant_id, ctx.version.version_id, output_id)
        output_json = {
            "document_version_id": str(ctx.version.version_id),
            "text": "\n\n".join(p.text for p in pages),
            "pages": [p.to_dict() for p in pages],
            "fields": fields,
            "flags": flags,
        }
        self._storage.put_ocr_output_json(object_key, output_json)

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
        )
        if field_results is not None:
            repo.upsert_field_results(
                tenant_id=ctx.version.tenant_id,
                document_id=ctx.document.document_id,
                version_id=ctx.version.version_id,
                results=field_results,
            )
        return ProcessingOutcome(status="completed", version_id=ctx.version.version_id, output=output_json)

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
