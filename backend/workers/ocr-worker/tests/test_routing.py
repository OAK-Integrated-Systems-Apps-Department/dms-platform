"""Phase 2 Step 7: per-page routing.

The rule this replaces was one line -- "Docling found any text on this
page, so use it" -- and it cost two entire conditions of the eval corpus,
both stuck at 0.0% field accuracy:

- `garbage_text_layer`: a real scan carrying an INVISIBLE `(cid:NN)` text
  layer, which is what a bad scanner-OCR pass leaves behind. A human sees
  a clean page; the router saw a text layer, took the native path, and
  returned confident nonsense.
- `hybrid_page`: a typed header over a scanned body. The header made the
  page "native", so PaddleOCR never ran and every field in the body was
  silently lost.

Both are failures of the same kind: the old rule asked *whether* there
was text, never whether it was any good or whether it covered the page.

The asymmetry that shapes every threshold here: re-OCRing a good native
page trades exact text for a guess, which is a real loss. So each check
is deliberately reluctant to override a text layer, and the tests below
push on both directions -- the junk that must be caught, and the
perfectly ordinary page that must not be.
"""

from __future__ import annotations

import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "src"))

from ocr_worker.routing import (  # noqa: E402
    HYBRID,
    NATIVE_TEXT,
    OCR,
    decide_route,
    garbage_ratio,
    plausibility,
)

A4 = (0, 0, 1653.0, 2339.0)  # an A4 page at the default render scale
PAGE_AREA = A4[2] * A4[3]

REAL_TEXT = (
    "Contoso Supplies PLC\nINVOICE\n15 Harbour Road, Colombo 03\n"
    "Invoice Number: INV-2026-05426\nInvoice Date: 11/07/2026\n"
    "Total Amount: 41,252.70\nCurrency: EUR\nDepartment: Operations"
)


def _defaults(**overrides):
    kwargs = {
        "max_garbage_ratio": 0.30,
        "min_plausibility": 0.50,
        "min_uncovered_image_coverage": 0.15,
    }
    kwargs.update(overrides)
    return kwargs


def _band(y0: float, y1: float, x0: float = 0.0, x1: float = 1653.0):
    return (x0, y0, x1, y1)


# ----------------------------------------------------------------------
# garbage_ratio
# ----------------------------------------------------------------------
def test_a_cid_layer_is_almost_entirely_garbage():
    """The exact shape `garbage_text_layer` produces."""
    text = " ".join(f"(cid:{n})" for n in range(20, 60))
    assert garbage_ratio(text) == pytest.approx(1.0)


def test_ordinary_text_has_no_garbage():
    assert garbage_ratio(REAL_TEXT) == 0.0


def test_whitespace_is_not_counted_as_good_content():
    """A page of `(cid:12) (cid:13) ...` is nearly half spaces. Counting
    those in the denominator would score it ~0.5 and let it slip under a
    0.6 threshold -- so the denominator excludes whitespace."""
    text = "(cid:12) (cid:13) (cid:14) (cid:15)"
    assert garbage_ratio(text) > 0.9


def test_replacement_characters_count_as_garbage():
    assert garbage_ratio("Invoice ����") > 0.3


def test_a_few_unmapped_glyphs_do_not_condemn_a_real_text_layer():
    """The case the threshold exists to protect: a real invoice whose
    logo font contributes a couple of unmapped glyphs is still a good
    text layer, and re-OCRing it would lose exact text for a guess."""
    text = REAL_TEXT + " (cid:3)(cid:4)"
    assert garbage_ratio(text) < 0.30


def test_empty_text_is_not_garbage():
    assert garbage_ratio("") == 0.0


# ----------------------------------------------------------------------
# plausibility
# ----------------------------------------------------------------------
def test_real_prose_is_plausible():
    assert plausibility(REAL_TEXT) > 0.8


def test_glyph_soup_is_not():
    assert plausibility("xkcdvn zzqrtl bcdfgh mnprst wxyzkj") < 0.5


def test_a_page_of_numbers_is_not_accused():
    """Numbers are not words, but they are not implausible either. This
    metric may only ever accuse text that genuinely looks like letters --
    otherwise a totals page would be re-OCR'd for no reason."""
    assert plausibility("41,252.70  1,200.00  18.50  96.00  2026-07-11") == 1.0


def test_identifiers_do_not_drag_plausibility_down_on_their_own():
    """Real documents are full of INV-2026-05426 and PO-74300. A
    dictionary would mark all of them implausible; a shape heuristic
    should not."""
    assert plausibility("INV-2026-05426 PO-74300 CC-4450 GB 384 2910 55") >= 0.5


# ----------------------------------------------------------------------
# decide_route -- the two conditions that were at 0.0%
# ----------------------------------------------------------------------
def test_an_invisible_cid_layer_over_a_scan_is_routed_to_ocr():
    """`garbage_text_layer`, end to end through the decision."""
    junk = " ".join(f"(cid:{n})" for n in range(20, 60))
    decision = decide_route(
        native_text=junk,
        native_boxes=[_band(200, 460)],
        image_boxes=[A4],
        page_box=A4,
        **_defaults(),
    )
    assert decision.route == OCR
    assert "unreadable glyphs" in decision.reason
    assert decision.garbage_ratio > 0.9


def test_a_typed_header_over_a_scanned_body_is_routed_hybrid():
    """`hybrid_page`: the header is genuinely good text, so it must be
    kept -- and the body is a large image nothing explains, so it must be
    OCR'd. Neither pure route is right."""
    decision = decide_route(
        native_text=REAL_TEXT,
        native_boxes=[_band(0, 120)],
        image_boxes=[_band(120, 2339)],
        page_box=A4,
        **_defaults(),
    )
    assert decision.route == HYBRID
    assert decision.ocr_regions == [_band(120, 2339)]
    assert decision.uncovered_image_coverage > 0.9


def test_garbage_is_tested_before_coverage():
    """Order matters, and getting it wrong hides the worst page.

    A junk layer produces junk BOXES too. If coverage were checked first,
    those boxes would count as explaining the image underneath, the page
    would look like an ordinary native page, and the one document that
    most needs OCR would be the one that never gets it.
    """
    junk = " ".join(f"(cid:{n})" for n in range(20, 80))
    decision = decide_route(
        native_text=junk,
        native_boxes=[A4],          # junk boxes covering the whole page
        image_boxes=[A4],
        page_box=A4,
        **_defaults(),
    )
    assert decision.route == OCR


# ----------------------------------------------------------------------
# ...and the pages that must NOT be re-routed
# ----------------------------------------------------------------------
def test_an_ordinary_native_page_stays_native():
    decision = decide_route(
        native_text=REAL_TEXT,
        native_boxes=[_band(100, 1800)],
        image_boxes=[],
        page_box=A4,
        **_defaults(),
    )
    assert decision.route == NATIVE_TEXT
    assert decision.garbage_ratio == 0.0


def test_a_letterhead_logo_does_not_trigger_a_second_inference_pass():
    """The cost-control case. If any image at all made a page hybrid,
    every native PDF with a logo would pay for an extra OCR pass on every
    upload, for nothing."""
    logo = (60.0, 40.0, 300.0, 160.0)  # ~0.5% of an A4 page
    decision = decide_route(
        native_text=REAL_TEXT,
        native_boxes=[_band(200, 1800)],
        image_boxes=[logo],
        page_box=A4,
        **_defaults(),
    )
    assert decision.route == NATIVE_TEXT
    assert decision.uncovered_image_coverage < 0.15


def test_a_background_image_the_text_layer_explains_is_not_ocr_re_read():
    """A native PDF drawn over a full-page background (a watermark, a
    letterhead template) has an image covering everything -- but the text
    layer sits on top of it and explains it, so there is nothing to
    recover by OCR."""
    decision = decide_route(
        native_text=REAL_TEXT,
        native_boxes=[A4],  # text boxes spanning the page
        image_boxes=[A4],
        page_box=A4,
        **_defaults(),
    )
    assert decision.route == NATIVE_TEXT


def test_a_sparse_native_page_is_not_re_ocred():
    """A cover sheet with a title and nothing else. An earlier version had
    a "too few characters to trust" rule that sent this to OCR, throwing
    away exact text for a guess."""
    decision = decide_route(
        native_text="Quarterly Report",
        native_boxes=[_band(300, 380)],
        image_boxes=[],
        page_box=A4,
        **_defaults(),
    )
    assert decision.route == NATIVE_TEXT


def test_a_scan_whose_only_text_is_a_stamped_page_number_becomes_hybrid():
    """The case that "too few characters" was aimed at, handled better:
    the page number is kept AND the scan is read, instead of the number
    being thrown away with a pure-OCR route."""
    decision = decide_route(
        native_text="Page 1 of 3",
        native_boxes=[(1400.0, 2250.0, 1600.0, 2300.0)],
        image_boxes=[A4],
        page_box=A4,
        **_defaults(),
    )
    assert decision.route == HYBRID


def test_no_text_layer_at_all_goes_to_ocr():
    decision = decide_route(
        native_text="", native_boxes=[], image_boxes=[A4], page_box=A4, **_defaults()
    )
    assert decision.route == OCR
    assert decision.reason == "no native text layer"


def test_a_page_with_no_geometry_cannot_be_measured_and_says_so():
    decision = decide_route(
        native_text=REAL_TEXT, native_boxes=[], image_boxes=[], page_box=(0, 0, 0, 0), **_defaults()
    )
    assert decision.route == OCR
    assert "geometry" in decision.reason


# ----------------------------------------------------------------------
# Coverage arithmetic
# ----------------------------------------------------------------------
def test_overlapping_text_boxes_are_not_counted_twice():
    """Text boxes on a real page overlap constantly -- a line box and its
    table cell, a heading and its paragraph. Summing box areas would
    report coverage above 1.0 and make a text-covered region look like an
    uncovered image (or vice versa)."""
    half = _band(0, 1169.5)
    decision = decide_route(
        native_text=REAL_TEXT,
        native_boxes=[half, half, half],  # the same box three times
        image_boxes=[],
        page_box=A4,
        **_defaults(),
    )
    assert decision.text_coverage == pytest.approx(0.5, abs=0.01)


def test_the_metrics_travel_with_the_verdict():
    """The verdict alone is unfalsifiable in a bug report. "This invoice
    came back empty" is answerable from "routed native_text at garbage
    ratio 0.02 with 91% text coverage"; it is not answerable from the
    word `native_text`."""
    decision = decide_route(
        native_text=REAL_TEXT,
        native_boxes=[_band(100, 1800)],
        image_boxes=[],
        page_box=A4,
        **_defaults(),
    )
    out = decision.to_dict()
    assert out["route"] == NATIVE_TEXT
    assert set(out) == {
        "route", "reason", "text_coverage", "image_coverage",
        "uncovered_image_coverage", "garbage_ratio", "plausibility",
        "native_chars", "ocr_regions",
    }
    assert out["reason"]
    assert 0.0 <= out["text_coverage"] <= 1.0


# ----------------------------------------------------------------------
# Through the real pipeline
# ----------------------------------------------------------------------
from ocr_worker.layout import NativeTextBlock, PageGeometry  # noqa: E402
from ocr_worker.ocr_engine import WordResult  # noqa: E402
from test_pipeline_transaction import FakeLayoutDocument, make_pipeline  # noqa: E402

PIL_Image = pytest.importorskip("PIL.Image")

GEOMETRY = PageGeometry(
    page_no=0, width_pt=595.0, height_pt=842.0, render_scale=2.7778,
    width_px=1653, height_px=2339,
)


def _page_image(width=1653, height=2339):
    return PIL_Image.new("RGB", (width, height), "white")


def test_a_garbage_text_layer_is_ocred_instead_of_returned(session_factory, seeded_version):
    """The `garbage_text_layer` condition, end to end. Before Step 7 this
    page returned `(cid:20) (cid:21) ...` as its text with confidence 1.0
    and PaddleOCR was never called."""
    junk = " ".join(f"(cid:{n})" for n in range(20, 60))
    layout_doc = FakeLayoutDocument(
        page_count=1,
        native_text_by_page={1: [NativeTextBlock(text=junk, bbox=(0, 200, 1653, 460))]},
        geometry=GEOMETRY,
        image_regions={0: [(0, 0, 1653, 2339)]},
        rendered_image=_page_image(),
    )
    pipeline, _, ocr_engine = make_pipeline(
        session_factory, layout_doc=layout_doc, ocr_text="Invoice No: INV-4471", ocr_confidence=0.96,
    )

    outcome = pipeline.process_version(seeded_version["version_id"])

    page = outcome.output["pages"][0]
    assert page["method"] == "paddleocr"
    assert len(ocr_engine.calls) == 1
    assert "cid:" not in page["text"]
    assert page["routing"]["route"] == "paddleocr"
    assert page["routing"]["garbage_ratio"] > 0.9


def test_a_hybrid_page_keeps_its_text_layer_and_ocrs_the_image(session_factory, seeded_version):
    """The `hybrid_page` condition. Before Step 7 the header's text made
    the page native and the entire scanned body -- every field on it --
    was silently dropped."""
    header = NativeTextBlock(text="Contoso Supplies PLC INVOICE", bbox=(56, 40, 900, 110))
    layout_doc = FakeLayoutDocument(
        page_count=1,
        native_text_by_page={1: [header]},
        geometry=GEOMETRY,
        image_regions={0: [(0, 120, 1653, 2339)]},
        rendered_image=_page_image(),
    )
    pipeline, _, ocr_engine = make_pipeline(
        session_factory,
        layout_doc=layout_doc,
        ocr_text="Invoice Number: INV-2026-05426",
        ocr_confidence=0.94,
        ocr_words=[WordResult(text="Invoice Number: INV-2026-05426", confidence=0.94, bbox=(40, 60, 700, 100))],
    )

    outcome = pipeline.process_version(seeded_version["version_id"])

    page = outcome.output["pages"][0]
    assert page["method"] == "hybrid"
    assert page["routing"]["route"] == "hybrid"
    assert len(ocr_engine.calls) == 1  # the image region only, not the page

    # Both sources present, each labelled, in reading order.
    assert [b["source"] for b in page["blocks"]] == ["native_text", "paddleocr"]
    assert "Contoso Supplies PLC" in page["text"]
    assert "INV-2026-05426" in page["text"]

    # The OCR block's bbox was re-based from crop-local pixels into the
    # full page, so it sits below the header rather than on top of it.
    native_block, ocr_block = page["blocks"]
    assert ocr_block["bbox"][1] > native_block["bbox"][3]


def test_a_hybrid_pages_ocr_region_is_cropped_not_the_whole_page(session_factory, seeded_version):
    """Only the uncovered image region is OCR'd. Sending the whole page
    would re-read the header that the text layer already has exactly, and
    pay for the pixels twice."""
    header = NativeTextBlock(text="Contoso Supplies PLC INVOICE", bbox=(56, 40, 900, 110))
    layout_doc = FakeLayoutDocument(
        page_count=1,
        native_text_by_page={1: [header]},
        geometry=GEOMETRY,
        image_regions={0: [(0, 120, 1653, 2339)]},
        rendered_image=_page_image(),
    )
    pipeline, _, ocr_engine = make_pipeline(
        session_factory, layout_doc=layout_doc, ocr_text="body", ocr_confidence=0.9,
    )

    pipeline.process_version(seeded_version["version_id"])

    cropped = ocr_engine.calls[0]
    assert cropped.size == (1653, 2339 - 120)


def test_disabling_routing_restores_the_old_rule_exactly(session_factory, seeded_version):
    """The toggle has to be a real rollback, not a different set of bugs:
    with it off, a junk text layer is trusted again, exactly as before."""
    junk = " ".join(f"(cid:{n})" for n in range(20, 60))
    layout_doc = FakeLayoutDocument(
        page_count=1,
        native_text_by_page={1: [NativeTextBlock(text=junk, bbox=(0, 200, 1653, 460))]},
        geometry=GEOMETRY,
        image_regions={0: [(0, 0, 1653, 2339)]},
        rendered_image=_page_image(),
    )
    pipeline, _, ocr_engine = make_pipeline(
        session_factory, layout_doc=layout_doc, ocr_text="x", ocr_routing_enabled=False,
    )

    outcome = pipeline.process_version(seeded_version["version_id"])

    page = outcome.output["pages"][0]
    assert page["method"] == "native_text"
    assert ocr_engine.calls == []
    assert "cid:" in page["text"]
    assert page["routing"]["reason"].startswith("routing disabled")


def test_an_ordinary_native_page_is_untouched_by_routing(session_factory, seeded_version):
    layout_doc = FakeLayoutDocument(
        page_count=1,
        native_text_by_page={1: [NativeTextBlock(text=REAL_TEXT, bbox=(56, 100, 1600, 1800))]},
        geometry=GEOMETRY,
        image_regions={0: []},
    )
    pipeline, _, ocr_engine = make_pipeline(session_factory, layout_doc=layout_doc)

    outcome = pipeline.process_version(seeded_version["version_id"])

    page = outcome.output["pages"][0]
    assert page["method"] == "native_text"
    assert ocr_engine.calls == []
    assert page["routing"]["route"] == "native_text"


def test_native_header_text_appears_exactly_once_in_the_merged_output(
    session_factory, seeded_version
):
    """The duplication hazard hybrid routing creates, asserted directly.

    A hybrid page has two producers writing into one block list. If the
    OCR pass were given the whole page instead of the uncovered region,
    the header would be read a second time and every downstream consumer
    -- full-text search, the regex field extractor, `Sum of line items`
    style validation -- would see it twice. Counting occurrences, not
    merely asserting presence, is the difference between a test that
    catches that and one that does not.
    """
    header_text = "Contoso Supplies PLC INVOICE"
    header = NativeTextBlock(text=header_text, bbox=(56, 40, 900, 110))
    layout_doc = FakeLayoutDocument(
        page_count=1,
        native_text_by_page={1: [header]},
        geometry=GEOMETRY,
        image_regions={0: [(0, 120, 1653, 2339)]},
        rendered_image=_page_image(),
    )
    # The OCR engine is deliberately made to return the header's own text
    # as well, which is what would happen if it were handed the whole
    # page: the assertion then fails unless only the region was cropped.
    pipeline, _, ocr_engine = make_pipeline(
        session_factory,
        layout_doc=layout_doc,
        ocr_text="body line",
        ocr_confidence=0.94,
        ocr_words=[WordResult(text="body line", confidence=0.94, bbox=(40, 60, 700, 100))],
    )

    outcome = pipeline.process_version(seeded_version["version_id"])

    page = outcome.output["pages"][0]
    assert page["method"] == "hybrid"
    assert page["text"].count(header_text) == 1
    assert [b["text"] for b in page["blocks"]].count(header_text) == 1
    assert sum(1 for b in page["blocks"] if b["source"] == "native_text") == 1


def test_only_the_uncovered_region_is_sent_to_ocr_not_the_page(session_factory, seeded_version):
    """Stated as an explicit invariant rather than inferred from the crop
    size: what reaches the engine must be strictly smaller than the page,
    and must start below the header."""
    header = NativeTextBlock(text="Contoso Supplies PLC INVOICE", bbox=(56, 40, 900, 110))
    region = (0, 120, 1653, 2339)
    layout_doc = FakeLayoutDocument(
        page_count=1,
        native_text_by_page={1: [header]},
        geometry=GEOMETRY,
        image_regions={0: [region]},
        rendered_image=_page_image(),
    )
    pipeline, _, ocr_engine = make_pipeline(
        session_factory, layout_doc=layout_doc, ocr_text="body", ocr_confidence=0.9,
    )

    pipeline.process_version(seeded_version["version_id"])

    assert len(ocr_engine.calls) == 1
    cropped = ocr_engine.calls[0]
    assert cropped.size == (region[2] - region[0], region[3] - region[1])
    assert cropped.size[1] < GEOMETRY.height_px  # strictly less than the whole page


def test_two_separate_image_regions_are_each_cropped(session_factory, seeded_version):
    """A page can carry more than one pasted scan -- a form with two
    photographed panels. Each uncovered region gets its own crop, rather
    than one bounding box swallowing the text between them."""
    header = NativeTextBlock(text="Contoso Supplies PLC INVOICE", bbox=(56, 40, 900, 110))
    top = (0, 200, 1653, 1000)
    bottom = (0, 1400, 1653, 2339)
    layout_doc = FakeLayoutDocument(
        page_count=1,
        native_text_by_page={1: [header]},
        geometry=GEOMETRY,
        image_regions={0: [top, bottom]},
        rendered_image=_page_image(),
    )
    pipeline, _, ocr_engine = make_pipeline(
        session_factory, layout_doc=layout_doc, ocr_text="panel", ocr_confidence=0.9,
    )

    outcome = pipeline.process_version(seeded_version["version_id"])

    assert len(ocr_engine.calls) == 2
    assert {img.size for img in ocr_engine.calls} == {
        (top[2] - top[0], top[3] - top[1]),
        (bottom[2] - bottom[0], bottom[3] - bottom[1]),
    }
    assert outcome.output["pages"][0]["routing"]["ocr_regions"] == 2
