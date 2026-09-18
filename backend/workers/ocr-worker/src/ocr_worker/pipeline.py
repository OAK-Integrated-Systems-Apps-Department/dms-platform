from __future__ import annotations

import logging
import uuid
from dataclasses import dataclass, field

from sqlalchemy.orm import sessionmaker, Session

from .confidence import ConfidenceEvaluator
from .config import Settings
from .db import session_scope
from .events import build_ocr_completed_payload, build_ocr_failed_payload
from .extraction import ExtractedField, extract_structured_fields, field_for_zone_name, fields_to_dict
from .layout import LayoutEngine, SUPPORTED_MIME_TYPES
from .ocr_engine import OcrEngine
from .repository import OcrRepository, VersionNotEligible, VersionNotFound
from .storage import ObjectStorage, ocr_output_key
from .zones import ZoneRegion, validate_normalized_box

logger = logging.getLogger(__name__)


class UnsupportedMimeType(Exception):
    pass


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
    status: str  # "completed" | "failed"
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
    # Event-path / dev-trigger: full document
    # ------------------------------------------------------------------
    def process_version(self, version_id: uuid.UUID, correlation_id: uuid.UUID | None = None) -> ProcessingOutcome:
        with session_scope(self._session_factory) as session:
            repo = OcrRepository(session)
            ctx = repo.get_version_for_processing(version_id)  # raises VersionNotFound/VersionNotEligible
            job = repo.start_job(tenant_id=ctx.version.tenant_id, version_id=version_id, correlation_id=correlation_id)

            try:
                if ctx.version.mime_type not in SUPPORTED_MIME_TYPES:
                    raise UnsupportedMimeType(ctx.version.mime_type)

                file_bytes = self._storage.get_object_bytes(ctx.version.storage_object_key)
                zone_regions = repo.get_zone_regions(ctx.document_type_id)

                with self._layout_engine.load(file_bytes, ctx.version.mime_type, ctx.version.original_filename) as layout_doc:
                    if zone_regions:
                        pages, fields = self._run_zone_ocr(layout_doc, zone_regions)
                    else:
                        pages, fields = self._run_full_document(layout_doc)

                return self._finalize_success(repo=repo, job=job, ctx=ctx, pages=pages, fields=fields, correlation_id=correlation_id)
            except (VersionNotFound, VersionNotEligible):
                raise
            except Exception as exc:  # noqa: BLE001 - deliberately broad: any failure here must be flagged, never silently dropped (OS-NFR-006)
                return self._finalize_failure(repo=repo, job=job, ctx=ctx, exc=exc, correlation_id=correlation_id)

    def _run_full_document(self, layout_doc) -> tuple[list[PageResult], dict[str, dict]]:
        """OS-FR-001/002, per the native-text-vs-paddleocr decision logic:
        a page with a real native text layer (Docling extracts it
        directly from the PDF) is used as-is -- it is never re-OCR'd. A
        page with no text layer (scanned, photographed, or an image-only
        PDF page) is rasterized and run through PaddleOCR instead.
        """
        pages: list[PageResult] = []
        for page_index in range(layout_doc.page_count()):  # PyMuPDF: 0-indexed
            page_number = page_index + 1  # output contract / Docling: 1-indexed
            native_blocks = layout_doc.native_text_blocks_for_page(page_number, self._settings.ocr_render_dpi)

            if native_blocks:
                tables = layout_doc.tables_for_page(page_number, self._settings.ocr_render_dpi) or []
                text, blocks, table_dicts = _merge_native_text_and_tables(native_blocks, tables)
                pages.append(PageResult(page_number=page_number, method="native_text", text=text, confidence=1.0, blocks=blocks, tables=table_dicts))
            else:
                page_image = layout_doc.render_page(page_index, self._settings.ocr_render_dpi)
                result = self._ocr_engine.recognize(page_image.image, lang=self._settings.ocr_languages)
                blocks = [{"text": w.text, "bbox": list(w.bbox), "confidence": w.confidence} for w in result.words]
                pages.append(PageResult(page_number=page_number, method="paddleocr", text=result.text, confidence=result.mean_confidence, blocks=blocks))

        full_text = "\n\n".join(p.text for p in pages)
        overall_confidence = sum(p.confidence for p in pages) / len(pages) if pages else 0.0
        fields = fields_to_dict(extract_structured_fields(full_text, base_confidence=overall_confidence))
        return pages, fields

    def _run_zone_ocr(self, layout_doc, regions: list[ZoneRegion]) -> tuple[list[PageResult], dict[str, dict]]:
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

            extracted_fields.append(field_for_zone_name(region.field, result.text, result.mean_confidence, region.name, bbox=crop_box))

        # Recompute each page's confidence as the mean of its own blocks
        # (a page can accumulate zones from multiple regions).
        for page_result in pages_by_number.values():
            confidences = [b["confidence"] for b in page_result.blocks]
            page_result.confidence = sum(confidences) / len(confidences) if confidences else 0.0

        pages = sorted(pages_by_number.values(), key=lambda p: p.page_number)
        return pages, fields_to_dict(extracted_fields)

    # ------------------------------------------------------------------
    # On-demand synchronous flows (OS-FR-003 named zones, OS-FR-004 rubber-band)
    # ------------------------------------------------------------------
    def process_zone_request(self, version_id: uuid.UUID, zone_names: list[str] | None, correlation_id: uuid.UUID | None = None) -> ProcessingOutcome:
        with session_scope(self._session_factory) as session:
            repo = OcrRepository(session)
            ctx = repo.get_version_for_processing(version_id)
            job = repo.start_job(tenant_id=ctx.version.tenant_id, version_id=version_id, correlation_id=correlation_id)

            try:
                all_regions = repo.get_zone_regions(ctx.document_type_id)
                if not all_regions:
                    raise ValueError(f"no zone template configured for document type {ctx.document_type_name!r}")
                regions = [r for r in all_regions if zone_names is None or r.name in zone_names]
                if not regions:
                    raise ValueError(f"none of {zone_names} match the configured zones for {ctx.document_type_name!r}")

                if ctx.version.mime_type not in SUPPORTED_MIME_TYPES:
                    raise UnsupportedMimeType(ctx.version.mime_type)

                file_bytes = self._storage.get_object_bytes(ctx.version.storage_object_key)
                with self._layout_engine.load(file_bytes, ctx.version.mime_type, ctx.version.original_filename) as layout_doc:
                    pages, fields = self._run_zone_ocr(layout_doc, regions)

                return self._finalize_success(repo=repo, job=job, ctx=ctx, pages=pages, fields=fields, correlation_id=correlation_id)
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
            job = repo.start_job(tenant_id=ctx.version.tenant_id, version_id=version_id, correlation_id=correlation_id)

            try:
                if ctx.version.mime_type not in SUPPORTED_MIME_TYPES:
                    raise UnsupportedMimeType(ctx.version.mime_type)

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
    def _finalize_success(self, *, repo: OcrRepository, job, ctx, pages: list[PageResult], fields: dict, correlation_id: uuid.UUID | None) -> ProcessingOutcome:
        overall_confidence = sum(p.confidence for p in pages) / len(pages) if pages else 0.0
        decision = self._confidence.evaluate(overall_confidence)
        flags = {"low_confidence": decision.flagged, "requires_review": decision.flagged}

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
        )
        repo.complete_success(
            job=job,
            version=ctx.version,
            extracted_text_ref=object_key,
            language=self._settings.ocr_languages,
            event_payload=payload,
        )
        return ProcessingOutcome(status="completed", version_id=ctx.version.version_id, output=output_json)

    def _finalize_failure(self, *, repo: OcrRepository, job, ctx, exc: Exception, correlation_id: uuid.UUID | None) -> ProcessingOutcome:
        logger.exception("OCR processing failed for version %s", ctx.version.version_id)
        payload = build_ocr_failed_payload(
            tenant_id=ctx.version.tenant_id,
            document_id=ctx.document.document_id,
            version_id=ctx.version.version_id,
            error=str(exc),
            correlation_id=correlation_id,
        )
        repo.complete_failure(job=job, version=ctx.version, error=str(exc), event_payload=payload)
        return ProcessingOutcome(status="failed", version_id=ctx.version.version_id, error=str(exc))
