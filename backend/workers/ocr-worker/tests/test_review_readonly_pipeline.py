"""`render_page_png` and `preview_rubber_band` must write NOTHING.

This is the whole reason they exist. `process_rubber_band_request`
finalizes -- it claims a job, replaces the version's entire field set and
page list with whatever the one crop produced, flips `ocr_status`, and
publishes an outbox event. A review screen that re-OCRs one number would,
through that path, wipe every other field on the document.

So these tests count rows before and after. A future change that "just
reuses" the finalizing path fails here rather than in production.
"""
from __future__ import annotations

import pytest
from sqlalchemy import text as sql_text

from ocr_worker.layout import NativeTextBlock
from ocr_worker.ocr_engine import WordResult

from test_pipeline_transaction import FakeLayoutDocument, _real_page_image, make_pipeline


TABLES = [
    "processing_jobs",
    "outbox_events",
    "ocr_outputs",
    "document_ocr_field_results",
]


def _counts(session_factory) -> dict[str, int]:
    out = {}
    with session_factory() as session:
        for table in TABLES:
            out[table] = session.execute(sql_text(f"SELECT count(*) FROM {table}")).scalar_one()
        row = session.execute(
            sql_text("SELECT ocr_status, requires_review FROM document_versions")
        ).first()
        out["ocr_status"] = row[0]
        out["requires_review"] = row[1]
    return out


def _layout_doc(page_count=3):
    # A REAL PIL image, not the crop-only stub: render_page_png encodes
    # whatever comes back, so a stub would prove nothing about the bytes
    # the browser receives.
    return FakeLayoutDocument(
        page_count=page_count,
        rendered_image=_real_page_image(),
        native_text_by_page={1: [NativeTextBlock(text="Total 1,234.56", bbox=(10, 10, 300, 40))]},
    )


class TestPreviewRubberBand:
    def test_it_returns_the_text_in_the_box(self, session_factory, seeded_version):
        pipeline, _, ocr_engine = make_pipeline(
            session_factory,
            layout_doc=_layout_doc(),
            ocr_text="1,234.56",
            ocr_confidence=0.91,
            ocr_words=[WordResult(text="1,234.56", confidence=0.91, bbox=(1, 2, 3, 4))],
        )

        answer = pipeline.preview_rubber_band(
            seeded_version["version_id"], 0, (0.1, 0.2, 0.3, 0.4)
        )

        assert answer["text"] == "1,234.56"
        assert answer["confidence"] == pytest.approx(0.91)
        assert answer["page"] == 0
        assert answer["bbox_norm"] == [0.1, 0.2, 0.3, 0.4]
        assert answer["words"] == [{"text": "1,234.56", "confidence": 0.91}]
        assert ocr_engine.calls, "it really did OCR the crop"

    def test_it_writes_nothing(self, session_factory, seeded_version):
        pipeline, storage, _ = make_pipeline(
            session_factory, layout_doc=_layout_doc(), ocr_text="1,234.56"
        )
        before = _counts(session_factory)

        pipeline.preview_rubber_band(seeded_version["version_id"], 0, (0.1, 0.2, 0.3, 0.4))

        assert _counts(session_factory) == before
        assert storage.written == {}, "no OCR output artifact either"

    def test_a_degenerate_box_is_rejected_before_any_work(self, session_factory, seeded_version):
        pipeline, _, ocr_engine = make_pipeline(session_factory, layout_doc=_layout_doc())

        with pytest.raises(ValueError):
            pipeline.preview_rubber_band(seeded_version["version_id"], 0, (0.5, 0.5, 0.5, 0.5))

        assert ocr_engine.calls == []

    def test_a_page_past_the_end_is_a_value_error(self, session_factory, seeded_version):
        pipeline, _, _ = make_pipeline(session_factory, layout_doc=_layout_doc(page_count=2))

        with pytest.raises(ValueError, match="out of range"):
            pipeline.preview_rubber_band(seeded_version["version_id"], 5, (0.1, 0.1, 0.9, 0.9))


class TestRenderPage:
    def test_it_returns_a_png_with_the_real_pixel_size(self, session_factory, seeded_version):
        pipeline, _, _ = make_pipeline(session_factory, layout_doc=_layout_doc())

        render = pipeline.render_page_png(seeded_version["version_id"], 0)

        assert render.png.startswith(b"\x89PNG\r\n\x1a\n")
        assert render.width_px > 0 and render.height_px > 0
        assert render.page_count == 3

    def test_it_writes_nothing(self, session_factory, seeded_version):
        pipeline, storage, ocr_engine = make_pipeline(session_factory, layout_doc=_layout_doc())
        before = _counts(session_factory)

        pipeline.render_page_png(seeded_version["version_id"], 0)

        assert _counts(session_factory) == before
        assert storage.written == {}
        # A preview is a render, never an inference -- the pool worker it
        # occupies is one the document queue is not getting.
        assert ocr_engine.calls == []

    def test_a_page_past_the_end_is_a_value_error(self, session_factory, seeded_version):
        pipeline, _, _ = make_pipeline(session_factory, layout_doc=_layout_doc(page_count=2))

        with pytest.raises(ValueError, match="out of range"):
            pipeline.render_page_png(seeded_version["version_id"], 7)


class TestPageCount:
    """V42: the review screen offers a page selector before it has
    fetched a single page, so the count has to be a column, not the
    length of an array inside an artifact in object storage."""

    def test_a_full_run_records_the_page_count(self, session_factory, seeded_version):
        pipeline, _, _ = make_pipeline(session_factory, layout_doc=_layout_doc(page_count=3))

        pipeline.process_version(seeded_version["version_id"])

        with session_factory() as session:
            count = session.execute(
                sql_text("SELECT page_count FROM document_versions")
            ).scalar_one()
        assert count == 3

    def test_a_zone_run_does_not_shrink_it(self, session_factory, seeded_version):
        """A zone run visits one page of a 3-page document. Writing
        len(pages) from there would report the document as 1 page and the
        reviewer would lose two thirds of it."""
        pipeline, _, _ = make_pipeline(session_factory, layout_doc=_layout_doc(page_count=3))
        pipeline.process_version(seeded_version["version_id"])

        pipeline.preview_rubber_band(seeded_version["version_id"], 0, (0.1, 0.1, 0.9, 0.9))

        with session_factory() as session:
            count = session.execute(
                sql_text("SELECT page_count FROM document_versions")
            ).scalar_one()
        assert count == 3
