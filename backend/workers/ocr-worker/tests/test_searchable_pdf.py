"""Searchable PDF rendition (Phase 4a, production prompt 3.11).

The properties that matter are observable from the produced PDF, so these
build one and read it back rather than asserting on calls.
"""
from __future__ import annotations

import io

import fitz
import pytest
from PIL import Image

from ocr_worker.searchable_pdf import (
    SearchablePdfBuilder,
    boxes_for_overlay,
    result_of,
    searchable_pdf_key,
)

# The OCR render these boxes are expressed in: 1000px across a 612pt page.
PPP = 1000 / 612.0


def _source_pdf(*, visible_text=None, image_px=None, width_pt=612.0, height_pt=792.0, pages=1):
    """A real source document for the rendition to copy."""
    doc = fitz.open()
    for _ in range(pages):
        page = doc.new_page(width=width_pt, height=height_pt)
        if image_px:
            buf = io.BytesIO()
            Image.new("RGB", image_px, "white").save(buf, format="PNG")
            page.insert_image(fitz.Rect(0, 0, width_pt, height_pt), stream=buf.getvalue())
        if visible_text:
            page.insert_text(fitz.Point(72, 100), visible_text, fontsize=12)
    data = doc.tobytes()
    doc.close()
    return fitz.open(stream=data, filetype="pdf")


LINES = [
    ("Riverside Hardware", (84, 92, 508, 136)),
    ("Receipt No: RCPT-33210", (82, 197, 612, 249)),
    ("Date: 2026-05-04", (82, 254, 455, 300)),
]


def _build(boxes, source=None, ppp=PPP):
    src = source or _source_pdf(image_px=(1200, 1600))
    builder = SearchablePdfBuilder()
    builder.add_source_page(src, 0, boxes, pixels_per_point=ppp)
    data = builder.finish()
    src.close()
    return data


class TestTextLayer:
    def test_every_line_is_extractable(self):
        doc = fitz.open(stream=_build(LINES), filetype="pdf")
        try:
            text = doc[0].get_text()
        finally:
            doc.close()
        for line, _ in LINES:
            assert line in text

    def test_the_text_is_invisible_not_white_on_white(self):
        """White-on-white would be visible over a dark scan and would
        print. Render mode 3 emits no marks at all."""
        doc = fitz.open(stream=_build(LINES), filetype="pdf")
        try:
            content = doc[0].read_contents().decode("latin-1", "replace")
        finally:
            doc.close()
        assert "3 Tr" in content

    def test_lines_land_near_the_ink_they_were_read_from(self):
        doc = fitz.open(stream=_build(LINES), filetype="pdf")
        try:
            found = doc[0].search_for("Receipt No: RCPT-33210")
        finally:
            doc.close()
        assert found, "the line was written but cannot be located"
        rect = found[0]
        assert rect.x0 == pytest.approx(82 / PPP, abs=10)
        assert rect.y1 == pytest.approx(249 / PPP, abs=10)

    def test_a_long_line_is_fitted_to_its_box_width(self):
        """These boxes are LINES, so a height-only font size leaves a long
        line's characters bunched at the left and a viewer's highlight
        drifting away from the ink across the line."""
        doc = fitz.open(stream=_build(LINES), filetype="pdf")
        try:
            found = doc[0].search_for("Receipt No: RCPT-33210")
        finally:
            doc.close()
        box_pt_width = (612 - 82) / PPP
        assert found[0].width == pytest.approx(box_pt_width, rel=0.25)


class TestSourceIsCopiedNotRasterized:
    def test_the_source_image_is_preserved(self):
        """A user who uploads a 300 DPI scan must not get a copy
        downsampled to whatever OCR_RENDER_MAX_MP needed."""
        doc = fitz.open(stream=_build(LINES), filetype="pdf")
        try:
            images = doc[0].get_images()
            assert len(images) == 1
            # Width of the embedded raster, from the resource dictionary.
            assert images[0][2] == 1200
        finally:
            doc.close()

    def test_a_vector_source_stays_vector(self):
        """Nothing is rasterized, so a page with no image yields none."""
        src = _source_pdf(visible_text="VECTOR TEXT")
        doc = fitz.open(stream=_build([], source=src), filetype="pdf")
        try:
            assert doc[0].get_images() == []
            assert "VECTOR TEXT" in doc[0].get_text()
        finally:
            doc.close()

    def test_the_page_keeps_the_sources_own_point_size(self):
        src = _source_pdf(width_pt=595.0, height_pt=842.0)
        doc = fitz.open(stream=_build([], source=src), filetype="pdf")
        try:
            assert doc[0].rect.width == pytest.approx(595.0, abs=1)
            assert doc[0].rect.height == pytest.approx(842.0, abs=1)
        finally:
            doc.close()

    def test_multiple_pages_are_added_in_order(self):
        src = _source_pdf(pages=3)
        builder = SearchablePdfBuilder()
        for n in range(3):
            builder.add_source_page(src, n, [(f"page{n}", (60, 80, 400, 130))], pixels_per_point=PPP)
        assert builder.page_count == 3
        data = builder.finish()
        src.close()

        doc = fitz.open(stream=data, filetype="pdf")
        try:
            assert doc.page_count == 3
            assert "page0" in doc[0].get_text()
            assert "page2" in doc[2].get_text()
        finally:
            doc.close()


class TestOverlaySelection:
    class _Page:
        def __init__(self, method, blocks):
            self.method = method
            self.blocks = blocks

    def test_a_native_text_page_gets_no_overlay(self):
        """It already has an exact text layer; a second one means Ctrl-F
        finds every word twice."""
        page = self._Page("native_text", [{"text": "Invoice", "bbox": (1, 2, 3, 4)}])
        assert boxes_for_overlay(page) == []

    def test_a_paddleocr_page_gets_every_block(self):
        page = self._Page("paddleocr", [
            {"text": "a", "bbox": (1, 2, 3, 4)},
            {"text": "b", "bbox": (5, 6, 7, 8)},
        ])
        assert len(boxes_for_overlay(page)) == 2

    def test_a_hybrid_page_gets_only_its_ocrd_blocks(self):
        page = self._Page("hybrid", [
            {"text": "header", "bbox": (1, 2, 3, 4), "source": "native_text"},
            {"text": "body", "bbox": (5, 6, 7, 8), "source": "paddleocr"},
        ])
        assert boxes_for_overlay(page) == [("body", (5, 6, 7, 8))]

    def test_a_block_with_no_text_or_no_bbox_is_skipped(self):
        page = self._Page("paddleocr", [
            {"text": "", "bbox": (1, 2, 3, 4)},
            {"text": "x", "bbox": None},
            {"text": "ok", "bbox": (5, 6, 7, 8)},
        ])
        assert boxes_for_overlay(page) == [("ok", (5, 6, 7, 8))]


class TestRobustness:
    def test_no_pages_produces_none_not_an_empty_pdf(self):
        assert SearchablePdfBuilder().finish() is None

    def test_a_page_with_no_recognised_text_is_still_copied(self):
        """An image-only page with nothing recognised is still worth a
        rendition -- the user can see it."""
        doc = fitz.open(stream=_build([]), filetype="pdf")
        try:
            assert doc.page_count == 1
            assert len(doc[0].get_images()) == 1
        finally:
            doc.close()

    @pytest.mark.parametrize("bad", [
        [("", (10, 10, 100, 40))],
        [("   ", (10, 10, 100, 40))],
        [("x", None)],
        [("x", (10, 10))],
        [(None, (10, 10, 100, 40))],
    ])
    def test_a_malformed_box_is_skipped_not_fatal(self, bad):
        assert _build(bad) is not None

    def test_a_degenerate_box_is_skipped(self):
        """A one-pixel detection artifact must not become a selectable
        token where the page has no text."""
        doc = fitz.open(stream=_build([("speck", (10, 10, 10, 10))]), filetype="pdf")
        try:
            assert "speck" not in doc[0].get_text()
        finally:
            doc.close()

    def test_a_missing_source_is_recorded_as_a_failure(self):
        builder = SearchablePdfBuilder()
        builder.add_source_page(None, 0, LINES, pixels_per_point=PPP)
        assert builder.page_count == 0
        assert builder.failures, "a failure with no reason is a silent one"
        assert "page 1" in builder.failures[0]

    def test_a_bad_page_index_is_recorded_and_the_others_survive(self):
        src = _source_pdf(pages=1)
        builder = SearchablePdfBuilder()
        builder.add_source_page(src, 5, LINES, pixels_per_point=PPP)   # out of range
        builder.add_source_page(src, 0, LINES, pixels_per_point=PPP)
        data, failures = result_of(builder)
        src.close()

        assert data is not None, "one bad page must not lose the document"
        assert len(failures) == 1
        doc = fitz.open(stream=data, filetype="pdf")
        try:
            assert doc.page_count == 1
        finally:
            doc.close()

    def test_a_zero_scale_skips_the_overlay_but_keeps_the_page(self):
        """No render scale means no way to place text -- the copied page
        is still worth having."""
        doc = fitz.open(stream=_build(LINES, ppp=0.0), filetype="pdf")
        try:
            assert doc.page_count == 1
            assert "Riverside" not in doc[0].get_text()
        finally:
            doc.close()


class TestResultOf:
    def test_it_returns_the_bytes_and_the_reasons_together(self):
        src = _source_pdf()
        builder = SearchablePdfBuilder()
        builder.add_source_page(src, 0, LINES, pixels_per_point=PPP)
        data, failures = result_of(builder)
        src.close()
        assert data is not None
        assert failures == []

    def test_reasons_survive_a_build_that_produced_nothing(self):
        builder = SearchablePdfBuilder()
        builder.add_source_page(None, 0, LINES, pixels_per_point=PPP)
        data, failures = result_of(builder)
        assert data is None
        assert len(failures) == 1


def test_the_object_key_is_tenant_first():
    key = searchable_pdf_key("tenant-1", "version-2", "rend-3")
    assert key.startswith("tenants/tenant-1/")
    assert key.endswith(".pdf")
    assert "version-2" in key and "rend-3" in key


class TestRotatedPages:
    """A page that arrived sideways has its OCR boxes mapped back into its
    own space, which leaves each line box TALL AND NARROW -- its height is
    the line's length. Writing horizontal text into that box put the
    baseline up to 43.5pt above the ink (measured on the corpus), so a
    user selecting a line highlighted empty paper.
    """

    class _Orientation:
        def __init__(self, applied, angle):
            self.applied = applied
            self.angle = angle

    def test_no_correction_means_no_rotation(self):
        from ocr_worker.searchable_pdf import text_rotation_for

        assert text_rotation_for(None) == 0
        assert text_rotation_for(self._Orientation(False, 90)) == 0

    @pytest.mark.parametrize("angle,expected", [(90, 90), (270, 90), (180, 180), (0, 0)])
    def test_the_applied_angle_selects_the_run_direction(self, angle, expected):
        """90 and 270 both use rotate=90: measured on the corpus's
        rotated_90 and rotated_270 fixtures, that lands every line within
        1.2pt of its ink for both, against 2.8pt for 270."""
        from ocr_worker.searchable_pdf import text_rotation_for

        assert text_rotation_for(self._Orientation(True, angle)) == expected

    def test_a_sideways_line_is_fitted_along_the_box_height(self):
        """The box is tall and narrow, so the run must be sized to its
        HEIGHT. Sizing to width would shrink a long line to nothing."""
        src = _source_pdf(image_px=(1200, 1600))
        builder = SearchablePdfBuilder()
        # A vertical box: 40pt wide, 300pt tall at scale 1.0.
        builder.add_source_page(
            src, 0, [("Receipt No: RCPT-33210", (100, 100, 140, 400))],
            pixels_per_point=1.0, text_rotation=90,
        )
        data = builder.finish()
        src.close()

        doc = fitz.open(stream=data, filetype="pdf")
        try:
            found = doc[0].search_for("Receipt No: RCPT-33210")
            assert found, "the sideways line was written but cannot be located"
            rect = found[0]
            # It should be tall, not wide -- i.e. it followed the box.
            assert rect.height > rect.width
            assert rect.height == pytest.approx(300, rel=0.35)
        finally:
            doc.close()

    def test_a_sideways_line_lands_inside_its_own_box(self):
        """A REAL line fills its box, which is the case the corpus
        measured at 1.2-4.2pt. A short string in a long box is the
        separate case the thickness cap exists for -- see below."""
        src = _source_pdf(image_px=(1200, 1600))
        box = (100, 100, 140, 400)
        text = "Receipt No: RCPT-33210 Date 2026-05-04"
        builder = SearchablePdfBuilder()
        builder.add_source_page(src, 0, [(text, box)], pixels_per_point=1.0, text_rotation=90)
        data = builder.finish()
        src.close()

        doc = fitz.open(stream=data, filetype="pdf")
        try:
            rect = doc[0].search_for(text)[0]
        finally:
            doc.close()
        assert rect.x1 == pytest.approx(box[2], abs=12)
        assert rect.y1 == pytest.approx(box[3], abs=12)

    def test_a_short_string_in_a_long_box_is_bounded_by_the_thickness_cap(self):
        """Without the cap a two-character cell in a long column would be
        inflated to fill it. With it, the run may exceed the box thickness
        by at most _MAX_FONT_SCALE -- bounded, not unbounded."""
        from ocr_worker.searchable_pdf import _MAX_FONT_SCALE

        src = _source_pdf(image_px=(1200, 1600))
        box = (100, 100, 140, 400)
        builder = SearchablePdfBuilder()
        builder.add_source_page(src, 0, [("SIDEWAYS", box)], pixels_per_point=1.0, text_rotation=90)
        data = builder.finish()
        src.close()

        doc = fitz.open(stream=data, filetype="pdf")
        try:
            rect = doc[0].search_for("SIDEWAYS")[0]
        finally:
            doc.close()
        thickness = box[2] - box[0]
        run_length = box[3] - box[1]
        # The rendered rect spans ascender to descender, so it exceeds the
        # nominal font size by roughly a third -- the bound that matters is
        # that the run stays near the box thickness instead of inflating
        # toward the box LENGTH, which is what the cap prevents.
        assert rect.width <= thickness * _MAX_FONT_SCALE * 1.5
        assert rect.width < run_length / 2

    def test_an_upright_line_is_unaffected_by_the_rotation_support(self):
        doc = fitz.open(stream=_build(LINES), filetype="pdf")
        try:
            found = doc[0].search_for("Receipt No: RCPT-33210")
        finally:
            doc.close()
        assert found
        assert found[0].width > found[0].height


class TestNonPdfSources:
    """`show_pdf_page` rejects a non-PDF source with "is no PDF". Assuming
    a bare image "opens as a one-page document so the same copy path
    serves every format" was wrong, and cost EVERY image upload its
    rendition silently -- most real uploads.
    """

    def test_an_image_document_must_be_converted_before_it_can_be_copied(self):
        img = fitz.open()
        try:
            buf = io.BytesIO()
            Image.new("RGB", (800, 1000), "white").save(buf, format="PNG")
            image_doc = fitz.open(stream=buf.getvalue(), filetype="png")
            try:
                assert image_doc.is_pdf is False
                builder = SearchablePdfBuilder()
                builder.add_source_page(image_doc, 0, LINES, pixels_per_point=PPP)
                assert builder.page_count == 0
                assert builder.failures, "the failure must be recorded, not silent"

                converted = fitz.open("pdf", image_doc.convert_to_pdf())
                try:
                    assert converted.is_pdf is True
                    builder2 = SearchablePdfBuilder()
                    builder2.add_source_page(converted, 0, LINES, pixels_per_point=PPP)
                    assert builder2.page_count == 1
                    assert builder2.failures == []
                finally:
                    converted.close()
            finally:
                image_doc.close()
        finally:
            img.close()
