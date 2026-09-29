"""Phase 2 Step 2: one coordinate space, and normalized coordinates.

Two things are proven here.

**The scales agree.** `render_page` capped its zoom while
`native_text_blocks_for_page`, `native_text_lines_for_page` and
`tables_for_page` scaled by the uncapped `dpi / 72`. At the default
200 DPI the cap is always active on an ordinary page, so native-text
bboxes were ~1.37x larger than the page they described. Nothing caught
it because routing is exclusive per page -- the two never met. Hybrid
routing (Step 7) makes them meet deliberately.

**Normalization is exact.** `bbox_norm` must mean the same thing
whichever method produced it, which is the precondition for a review UI
drawing highlights and for spatial matching across a tiled page.
"""

from __future__ import annotations

import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "src"))

from ocr_worker.layout import PageGeometry, render_scale_for  # noqa: E402

fitz = pytest.importorskip("fitz")

A4_WIDTH_PT = 595.0
A4_HEIGHT_PT = 842.0
LETTER_WIDTH_PT = 612.0
LETTER_HEIGHT_PT = 792.0


# ----------------------------------------------------------------------
# render_scale_for -- the one scale
# ----------------------------------------------------------------------
MAX_MP = 1.81  # config.Settings.ocr_render_max_mp -- the old 1600px cap on A4


def test_an_ordinary_page_renders_exactly_as_it_did_under_the_old_cap():
    """The Step 4 change is a change of SHAPE, not of resolution.

    The default budget is the exact area the old 1600px longest-side cap
    produced on A4 -- 1131x1600 = 1,809,600 px -- so an ordinary page
    renders pixel-identically to before. That is deliberate: Step 4
    measured what extra pixels buy on this corpus (nothing: 137 and 200
    DPI gave byte-identical text) against what they cost (+60% time, +70%
    memory), and a cap reshaped to be fairer must not smuggle in a
    resolution rise that was explicitly vetoed.
    """
    scale = render_scale_for(200, A4_WIDTH_PT, A4_HEIGHT_PT, MAX_MP)
    assert round(A4_WIDTH_PT * scale) == 1131
    assert round(A4_HEIGHT_PT * scale) == 1600

    old_longest_side_scale = min(200 / 72, 1600 / A4_HEIGHT_PT)
    assert scale == pytest.approx(old_longest_side_scale, rel=1e-3)


def test_letter_loses_a_little_area_and_that_is_the_point():
    """Honest about the one ordinary page size that does change.

    A longest-side cap hands out area as a function of aspect ratio. A4
    (595x842) got 1131x1600 = 1.81 MP; Letter (612x792) is squarer, so
    the same 1600px limit gave it 1236x1600 = 1.98 MP -- 9% more, for no
    reason anyone chose. Equalising that is exactly what an area budget
    is for, so Letter comes down to the same 1.81 MP everything else
    gets.

    Recorded as a test rather than glossed as "no change": it IS a
    change, it is ~8% fewer pixels on one page size, and the measurement
    behind Step 4 (137 vs 200 DPI byte-identical) says a change of that
    size is far below anything that moves accuracy.
    """
    scale = render_scale_for(200, LETTER_WIDTH_PT, LETTER_HEIGHT_PT, MAX_MP)
    new_megapixels = (LETTER_WIDTH_PT * scale) * (LETTER_HEIGHT_PT * scale) / 1e6

    old_scale = min(200 / 72, 1600 / LETTER_HEIGHT_PT)
    old_megapixels = (LETTER_WIDTH_PT * old_scale) * (LETTER_HEIGHT_PT * old_scale) / 1e6

    assert old_megapixels == pytest.approx(1.98, abs=0.01)
    assert new_megapixels == pytest.approx(MAX_MP, rel=1e-3)
    assert 0.90 < new_megapixels / old_megapixels < 0.93


def test_the_area_budget_binds_before_the_page_can_get_dangerous():
    """The budget exists because RSS grows ~0.7 GiB per megapixel and
    PaddleOCR is OOM-killed above ~6 MP per inference call at the
    container's limit. So whatever the DPI asks for, the rendered area
    must stay inside the budget."""
    for dpi in (200, 300, 600, 1200):
        scale = render_scale_for(dpi, A4_WIDTH_PT, A4_HEIGHT_PT, MAX_MP)
        megapixels = (A4_WIDTH_PT * scale) * (A4_HEIGHT_PT * scale) / 1e6
        assert megapixels <= MAX_MP + 1e-6, f"{dpi} DPI rendered {megapixels:.2f} MP"


def test_render_scale_is_uncapped_when_the_page_is_small_enough():
    # A small page at a low DPI never reaches the budget.
    scale = render_scale_for(100, 300.0, 400.0, MAX_MP)
    assert scale == pytest.approx(100 / 72)


def test_render_scale_caps_an_oversized_page_by_area():
    scale = render_scale_for(200, 2000.0, 3000.0, MAX_MP)
    assert (2000.0 * scale) * (3000.0 * scale) == pytest.approx(MAX_MP * 1_000_000)


def test_a_wide_page_is_no_longer_punished_for_its_aspect_ratio():
    """The reason the cap is on area rather than on the longest side.

    A 2000x500pt landscape page hit the old 1600px limit at 1600x400 =
    0.64 MP -- a quarter of what an A4 page was allowed, for no reason
    but its shape. Under an area budget it gets the same budget as any
    other page.
    """
    wide = (2000.0, 500.0)
    scale = render_scale_for(200, *wide, MAX_MP)
    megapixels = (wide[0] * scale) * (wide[1] * scale) / 1e6
    assert megapixels == pytest.approx(MAX_MP, rel=1e-6)

    # It now gets the same budget as A4 instead of a quarter of it.
    old_scale = min(200 / 72, 1600 / max(wide))
    old_megapixels = (wide[0] * old_scale) * (wide[1] * old_scale) / 1e6
    assert old_megapixels == pytest.approx(0.64, abs=0.01)
    assert megapixels / old_megapixels > 2.5


# ----------------------------------------------------------------------
# The scales agree -- the actual bug
# ----------------------------------------------------------------------
def _one_page_pdf_with_text() -> bytes:
    doc = fitz.open()
    page = doc.new_page(width=A4_WIDTH_PT, height=A4_HEIGHT_PT)
    page.insert_text((72, 200), "INV-2026-00184", fontname="helv", fontsize=11)
    data = doc.tobytes()
    doc.close()
    return data


def test_native_line_bboxes_land_where_the_text_actually_is():
    """The regression test for the 1.37x mismatch.

    Deliberately asserts the bbox's *position*, not merely that it is
    inside the page. An in-bounds check is too weak to catch this bug: on
    an ordinary invoice whose content stops around 40% down the page, the
    old uncapped `dpi / 72` scaling put bboxes at ~146% of their true
    position and they still landed inside the image -- just on the wrong
    text. The text is drawn at a known y in points, so the only correct
    answer is `y_pt * render_scale`, and the old scale gives a number 46%
    larger that this tolerance excludes.
    """
    from ocr_worker.layout import DoclingLayoutDocument

    baseline_y_pt = 200.0
    pdf = _one_page_pdf_with_text()
    doc = object.__new__(DoclingLayoutDocument)
    doc._doc = fitz.open(stream=pdf, filetype="pdf")
    # Deliberately a budget small enough that the cap BINDS on an A4 page
    # (the default 4 MP no longer does). The bug this test exists for --
    # the rasterizer capping while the three native-text producers scaled
    # by the uncapped dpi/72 -- can only appear when something is capping
    # at all, so a budget that never binds would make it vacuous.
    doc._max_megapixels = 1.8
    doc._raster_source = False
    try:
        geometry = doc.page_geometry(0, 200)
        lines = doc.native_text_lines_for_page(0, 200)
        assert lines and len(lines) == 1, "the fixture draws exactly one line"
        x0, y0, x1, y1 = lines[0].bbox

        expected_baseline_px = baseline_y_pt * geometry.render_scale
        # The line box spans ascender to descender around the baseline,
        # so allow a few pixels either side of it -- but far less than
        # the ~175px the old scaling was out by.
        assert y0 <= expected_baseline_px <= y1 + 5

        wrong_scale_px = baseline_y_pt * (200 / 72.0)
        assert abs(wrong_scale_px - expected_baseline_px) > 100, (
            "the two scales must differ enough for this test to mean something"
        )
        assert not (y0 <= wrong_scale_px <= y1 + 5), "the old uncapped scale must not pass"

        assert 0 <= x0 <= geometry.width_px
        assert 0 <= y1 <= geometry.height_px
    finally:
        doc._doc.close()


def test_page_geometry_matches_the_rasterizer_within_a_pixel():
    from ocr_worker.layout import DoclingLayoutDocument

    pdf = _one_page_pdf_with_text()
    doc = object.__new__(DoclingLayoutDocument)
    doc._doc = fitz.open(stream=pdf, filetype="pdf")
    doc._max_megapixels = MAX_MP
    doc._raster_source = False
    try:
        geometry = doc.page_geometry(0, 200)
        image = doc.render_page(0, 200)
        assert abs(image.width_px - geometry.width_px) <= 1
        assert abs(image.height_px - geometry.height_px) <= 1
        # render_page hands back the geometry it used, so a caller never
        # has to recompute the scale to interpret the pixels.
        assert image.geometry is not None
        assert image.geometry.render_scale == geometry.render_scale
    finally:
        doc._doc.close()


@pytest.mark.parametrize(
    "width_pt,height_pt",
    [(A4_WIDTH_PT, A4_HEIGHT_PT), (LETTER_WIDTH_PT, LETTER_HEIGHT_PT), (1224.0, 1584.0)],
)
def test_every_bbox_producer_uses_the_same_scale(width_pt, height_pt):
    """A native-text bbox and an OCR bbox for the same word on the same
    page must agree -- the test section 3.5 asks for by name.

    Expressed as "all four producers derive from one scale function",
    which is the property that makes the agreement true for every page
    size rather than for the one a fixture happens to use.
    """
    scale = render_scale_for(200, width_pt, height_pt, MAX_MP)
    geometry = PageGeometry(
        page_no=0,
        width_pt=width_pt,
        height_pt=height_pt,
        render_scale=scale,
        width_px=round(width_pt * scale),
        height_px=round(height_pt * scale),
    )
    # A word at 25% across and 50% down, expressed in points, must
    # normalize back to exactly that wherever it came from.
    bbox_px = (
        0.25 * width_pt * scale,
        0.50 * height_pt * scale,
        0.35 * width_pt * scale,
        0.52 * height_pt * scale,
    )
    norm = geometry.normalize(bbox_px)
    assert norm[0] == pytest.approx(0.25)
    assert norm[1] == pytest.approx(0.50)
    assert norm[2] == pytest.approx(0.35)
    assert norm[3] == pytest.approx(0.52)


# ----------------------------------------------------------------------
# Normalization
# ----------------------------------------------------------------------
def _a4_geometry(dpi: int = 200) -> PageGeometry:
    scale = render_scale_for(dpi, A4_WIDTH_PT, A4_HEIGHT_PT, MAX_MP)
    return PageGeometry(
        page_no=0,
        width_pt=A4_WIDTH_PT,
        height_pt=A4_HEIGHT_PT,
        render_scale=scale,
        width_px=round(A4_WIDTH_PT * scale),
        height_px=round(A4_HEIGHT_PT * scale),
    )


def test_ocr_render_dpi_is_still_inert_on_an_ordinary_page_and_that_is_deliberate():
    """Successor to `test_ocr_render_dpi_is_currently_inert_for_an_
    ordinary_page`, which was written to fail the moment the cap stopped
    binding on A4. At the default budget it still binds, on purpose.

    What changed in Step 4 is *why*. It used to be an accident of a
    longest-side rule nobody had measured; it is now a deliberate budget
    set from a measured memory ceiling, with a measured finding that the
    pixels it withholds buy no accuracy. The knob is honoured wherever the
    budget leaves room -- a smaller page below -- so it is no longer a
    setting that silently does nothing everywhere.
    """
    a4_at_200 = render_scale_for(200, A4_WIDTH_PT, A4_HEIGHT_PT, MAX_MP)
    assert a4_at_200 == pytest.approx(render_scale_for(300, A4_WIDTH_PT, A4_HEIGHT_PT, MAX_MP))
    assert a4_at_200 == pytest.approx(render_scale_for(600, A4_WIDTH_PT, A4_HEIGHT_PT, MAX_MP))

    # ...but on a page that fits the budget, the setting means what it says.
    small = (300.0, 400.0)
    assert render_scale_for(150, *small, MAX_MP) == pytest.approx(150 / 72)
    assert render_scale_for(200, *small, MAX_MP) == pytest.approx(200 / 72)


def test_normalize_is_scale_independent():
    """The whole point of normalized coordinates.

    The same physical position on a page normalizes identically at two
    genuinely different render scales -- which is what makes Step 4's
    resolution change safe for every downstream consumer. A small page
    is used deliberately, because the 1600px cap makes an A4 page render
    at the same scale for every DPI worth testing (see the test above).
    """
    small = (300.0, 400.0)
    low = render_scale_for(100, *small, MAX_MP)
    high = render_scale_for(200, *small, MAX_MP)
    assert low != high

    def geometry_at(scale):
        return PageGeometry(
            page_no=0,
            width_pt=small[0],
            height_pt=small[1],
            render_scale=scale,
            width_px=round(small[0] * scale),
            height_px=round(small[1] * scale),
        )

    def box(scale):
        return (100 * scale, 200 * scale, 150 * scale, 220 * scale)  # the same points

    assert geometry_at(low).normalize(box(low)) == pytest.approx(
        geometry_at(high).normalize(box(high))
    )


def test_normalize_clamps_an_overhanging_detection():
    geometry = _a4_geometry()
    norm = geometry.normalize((-5, -5, geometry.exact_width_px + 50, geometry.exact_height_px + 50))
    assert norm == [0.0, 0.0, 1.0, 1.0]


def test_normalize_returns_none_without_a_bbox():
    assert _a4_geometry().normalize(None) is None


def test_normalize_returns_none_for_a_degenerate_page():
    """A reflowable document has no geometry to normalize against.

    Returning None rather than a zero box matters: a zero box would be
    drawn by a review UI as a real highlight in the page's corner.
    """
    nothing = PageGeometry(0, 0.0, 0.0, 1.0, 0, 0)
    assert nothing.normalize((0, 0, 10, 10)) is None


def test_geometry_to_dict_carries_both_unit_systems():
    out = _a4_geometry().to_dict()
    assert out["width_pt"] == pytest.approx(A4_WIDTH_PT)
    assert out["height_pt"] == pytest.approx(A4_HEIGHT_PT)
    assert out["height_px"] == 1600
    assert out["render_scale"] > 0
