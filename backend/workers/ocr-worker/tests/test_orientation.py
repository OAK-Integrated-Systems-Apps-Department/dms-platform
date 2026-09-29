"""Phase 2 Step 3: whole-page orientation correction.

The baseline (`eval/reports/baseline-2026-09-25.json`) measured what not
doing this costs: the same documents scored 95.3% field accuracy upright
and 11.7-14.7% rotated. So the correction itself is worth a lot -- and
getting it *wrong* on an upright page costs exactly as much in the other
direction, which is why the "unsure, so leave it alone" path is tested
as carefully as the correcting one.

Two families of test here:

**The geometry.** OCR reads a corrected, upright raster, but every bbox
that leaves the worker is in the space of the page as stored -- the space
a viewer renders and a review UI draws highlights over. The inverse map
is therefore load-bearing, and it is pinned against real PIL rotations
rather than against itself: a round-trip through `correct_image` and back
through `to_original` has to land on the pixels it started from.

**The decision.** Confidence gating, an unknown label, a classifier that
returns nothing at all.

None of this needs paddleocr -- `PaddleDocOrientationClassifier` is the
only part that does, and it is exercised by the eval run, not here.
"""

from __future__ import annotations

import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "src"))

from ocr_worker.orientation import (  # noqa: E402
    NullOrientationClassifier,
    OrientationResult,
    PageOrientation,
    correct_image,
    decide,
)

Image = pytest.importorskip("PIL.Image")


class StubClassifier:
    """Returns a fixed prediction, and records what it was shown."""

    def __init__(self, angle: int, confidence: float = 0.93):
        self._result = OrientationResult(angle=angle, confidence=confidence)
        self.seen = []

    def classify(self, image):
        self.seen.append(image)
        return self._result


class EmptyClassifier:
    """A model that produced no usable prediction at all."""

    def classify(self, image):
        return OrientationResult(angle=0, confidence=0.0)


# Deliberately non-square, and with a marker that is off-centre in both
# axes: a square page or a centred marker would let a wrong mapping (an
# axis swap, a sign flip) pass.
PAGE_W, PAGE_H = 400, 200
MARKER = (30, 20, 90, 50)


def _upright_page():
    page = Image.new("RGB", (PAGE_W, PAGE_H), "white")
    page.paste(Image.new("RGB", (MARKER[2] - MARKER[0], MARKER[3] - MARKER[1]), "black"), MARKER[:2])
    return page


def _marker_bbox(image):
    pixels = image.load()
    xs, ys = [], []
    for y in range(image.size[1]):
        for x in range(image.size[0]):
            if sum(pixels[x, y]) < 200:
                xs.append(x)
                ys.append(y)
    assert xs, "the marker vanished -- the rotation lost page content"
    return (min(xs), min(ys), max(xs) + 1, max(ys) + 1)


def _as_received(angle: int):
    """The page as it arrives: rotated `angle` degrees CLOCKWISE, which is
    how the eval corpus's `rotated_*` conditions are generated and what
    the classifier labels with that same angle."""
    return _upright_page().rotate(-angle, expand=True)


# ----------------------------------------------------------------------
# correct_image -- the rotation
# ----------------------------------------------------------------------
@pytest.mark.parametrize("angle", [90, 180, 270])
def test_correcting_a_rotated_page_reproduces_the_upright_page_exactly(angle):
    corrected = correct_image(_as_received(angle), angle)
    assert corrected.size == (PAGE_W, PAGE_H)
    assert _marker_bbox(corrected) == MARKER


def test_correcting_by_zero_returns_the_same_image_object():
    page = _upright_page()
    assert correct_image(page, 0) is page


@pytest.mark.parametrize("angle", [45, -90, 360, 1])
def test_an_angle_outside_the_four_quadrants_is_rejected(angle):
    """A 37-degree "correction" would be a silent, unrecoverable
    corruption of the page, so this raises rather than rounding."""
    with pytest.raises(ValueError):
        correct_image(_upright_page(), angle)


def test_a_90_degree_correction_keeps_the_whole_page():
    """`expand=True`, not a detail: without it PIL crops the rotation back
    to the pre-rotation box, which on a portrait page throws away most of
    the content -- and a page that is merely *shorter* still OCRs, so the
    loss would show up as mysteriously missing fields, not as an error."""
    received = _as_received(90)
    assert received.size == (PAGE_H, PAGE_W)
    assert correct_image(received, 90).size == (PAGE_W, PAGE_H)


# ----------------------------------------------------------------------
# PageOrientation.to_original -- the inverse map
# ----------------------------------------------------------------------
@pytest.mark.parametrize("angle", [90, 180, 270])
def test_a_bbox_found_in_the_corrected_page_maps_back_onto_the_original_pixels(angle):
    """The whole contract in one assertion: OCR finds the marker in the
    corrected page, and `to_original` has to name the place that same
    marker actually occupies in the page as stored."""
    received = _as_received(angle)
    corrected = correct_image(received, angle)
    orientation = PageOrientation(
        angle=angle,
        confidence=0.93,
        applied=True,
        original_width_px=received.size[0],
        original_height_px=received.size[1],
    )

    mapped = orientation.to_original(_marker_bbox(corrected))

    assert tuple(mapped) == _marker_bbox(received)


def test_an_unapplied_decision_leaves_a_bbox_untouched():
    """When nothing was rotated, OCR already ran in the page's own space,
    so mapping must be the identity -- not a rotation by a recorded but
    unapplied angle."""
    orientation = PageOrientation(
        angle=90, confidence=0.2, applied=False, original_width_px=PAGE_H, original_height_px=PAGE_W
    )
    assert orientation.to_original((10, 20, 30, 40)) == (10, 20, 30, 40)


def test_mapping_a_missing_bbox_stays_missing():
    orientation = PageOrientation(
        angle=180, confidence=0.9, applied=True, original_width_px=PAGE_W, original_height_px=PAGE_H
    )
    assert orientation.to_original(None) is None


@pytest.mark.parametrize(
    "angle,applied,expected",
    [
        (90, True, (PAGE_H, PAGE_W)),
        (270, True, (PAGE_H, PAGE_W)),
        (180, True, (PAGE_W, PAGE_H)),
        (90, False, (PAGE_W, PAGE_H)),
        (0, False, (PAGE_W, PAGE_H)),
    ],
)
def test_upright_page_size_swaps_only_for_an_applied_quarter_turn(angle, applied, expected):
    orientation = PageOrientation(
        angle=angle, confidence=0.9, applied=applied, original_width_px=0, original_height_px=0
    )
    assert orientation.upright_page_size(PAGE_W, PAGE_H) == expected


# ----------------------------------------------------------------------
# decide -- the gate
# ----------------------------------------------------------------------
def test_a_confident_rotation_is_applied_and_recorded():
    received = _as_received(90)
    image, decision = decide(
        StubClassifier(90, 0.93), received, original_size=received.size, min_confidence=0.6
    )

    assert decision.applied is True
    assert decision.angle == 90
    assert image.size == (PAGE_W, PAGE_H)
    assert decision.to_dict() == {"angle": 90, "confidence": 0.93, "applied": True}


def test_a_rotation_below_the_confidence_floor_is_recorded_but_not_applied():
    """The asymmetry that matters: an unapplied wrong guess costs nothing,
    an applied one costs ~83 accuracy points. `angle` is still reported so
    an operator can see the model wanted to rotate and did not."""
    received = _as_received(90)
    image, decision = decide(
        StubClassifier(90, 0.41), received, original_size=received.size, min_confidence=0.6
    )

    assert decision.applied is False
    assert decision.angle == 90
    assert image is received


def test_confidence_exactly_at_the_floor_is_applied():
    received = _as_received(180)
    _, decision = decide(
        StubClassifier(180, 0.6), received, original_size=received.size, min_confidence=0.6
    )
    assert decision.applied is True


def test_an_upright_page_is_never_rotated():
    page = _upright_page()
    image, decision = decide(
        NullOrientationClassifier(), page, original_size=page.size, min_confidence=0.6
    )

    assert image is page
    assert decision.applied is False
    assert decision.angle == 0
    # Still recorded: "the classifier looked and said upright" and "the
    # classifier never ran" are different facts about a document.
    assert decision.to_dict()["angle"] == 0


def test_an_angle_the_classifier_should_never_return_is_ignored_not_applied():
    """Defence against a model or a future label set that returns
    something outside the four quadrants -- the page is left alone rather
    than rotated by a number `correct_image` would reject anyway."""
    page = _upright_page()
    image, decision = decide(
        StubClassifier(45, 0.99), page, original_size=page.size, min_confidence=0.6
    )
    assert image is page
    assert decision.angle == 0
    assert decision.applied is False


def test_a_classifier_that_predicts_nothing_leaves_the_page_alone():
    page = _upright_page()
    image, decision = decide(
        EmptyClassifier(), page, original_size=page.size, min_confidence=0.6
    )
    assert image is page
    assert decision.applied is False


def test_the_recorded_original_size_is_the_one_passed_in_not_the_images():
    """`original_size` comes from the rasterizer's own pixmap dimensions
    (`layout.PageImage`), the same source `PageGeometry` normalizes
    against. Reading it off the image object instead would be a second
    source for the one number the inverse map depends on."""
    received = _as_received(90)
    _, decision = decide(
        StubClassifier(90), received, original_size=(1234, 5678), min_confidence=0.6
    )
    assert (decision.original_width_px, decision.original_height_px) == (1234, 5678)


# ----------------------------------------------------------------------
# Through the real pipeline: what actually reaches the output contract
# ----------------------------------------------------------------------
from ocr_worker.extraction import FieldSpec  # noqa: E402
from ocr_worker.ocr_engine import WordResult  # noqa: E402
from ocr_worker.models import DocumentOcrFieldResult  # noqa: E402
from test_pipeline_transaction import FakeLayoutDocument, make_pipeline  # noqa: E402


def test_a_rotated_pages_blocks_come_back_in_the_pages_own_coordinate_space(
    session_factory, seeded_version
):
    """The end-to-end version of the inverse-map test, and the reason the
    map exists: a consumer drawing highlights over the stored page must
    not have to know this worker rotated anything.

    The OCR engine here "finds" the marker where it really is in the
    corrected page, so the only correct `bbox` in the output is where
    that marker really is in the page as stored.
    """
    received = _as_received(90)
    corrected_bbox = MARKER
    expected_bbox = list(_marker_bbox(received))

    layout_doc = FakeLayoutDocument(page_count=1, native_text_by_page={}, rendered_image=received)
    pipeline, _, ocr_engine = make_pipeline(
        session_factory,
        layout_doc=layout_doc,
        ocr_text="Author: Jane Doe",
        ocr_confidence=0.97,
        ocr_words=[WordResult(text="Author: Jane Doe", confidence=0.97, bbox=corrected_bbox)],
        orientation_classifier=StubClassifier(90, 0.93),
    )

    outcome = pipeline.process_version(seeded_version["version_id"])

    assert outcome.status == "completed"
    page = outcome.output["pages"][0]
    assert page["blocks"][0]["bbox"] == expected_bbox
    # ...and the engine really did see the corrected, upright raster.
    assert ocr_engine.calls[0].size == (PAGE_W, PAGE_H)


def test_the_orientation_decision_is_recorded_in_the_page_output(session_factory, seeded_version):
    received = _as_received(180)
    layout_doc = FakeLayoutDocument(page_count=1, native_text_by_page={}, rendered_image=received)
    pipeline, _, _ = make_pipeline(
        session_factory,
        layout_doc=layout_doc,
        ocr_text="anything",
        orientation_classifier=StubClassifier(180, 0.88),
    )

    outcome = pipeline.process_version(seeded_version["version_id"])

    assert outcome.output["pages"][0]["preprocessing"] == {
        "orientation": {"angle": 180, "confidence": 0.88, "applied": True}
    }


def test_a_native_text_page_is_never_shown_to_the_classifier(session_factory, seeded_version):
    """A native-text page is never rasterized, so there is nothing to
    orient: its text comes out of the PDF's own text layer in reading
    order however the page is displayed.

    Asserts the classifier was never *called*, not merely that nothing
    was recorded. Those differ in the way that matters — a classifier
    that runs on every page and is ignored on native-text ones still
    costs time on every page of every native PDF, and a later change
    could start honouring its answer without any test noticing.
    """
    from ocr_worker.layout import NativeTextBlock

    layout_doc = FakeLayoutDocument(
        page_count=1,
        native_text_by_page={1: [NativeTextBlock(text="Invoice No: INV-4471", bbox=(10, 10, 300, 40))]},
    )
    classifier = StubClassifier(90, 0.99)  # would rotate, if it were ever asked
    pipeline, _, ocr_engine = make_pipeline(
        session_factory, layout_doc=layout_doc, orientation_classifier=classifier
    )

    outcome = pipeline.process_version(seeded_version["version_id"])

    assert classifier.seen == []
    assert ocr_engine.calls == []  # and PaddleOCR was never called either
    assert outcome.output["pages"][0]["method"] == "native_text"
    assert outcome.output["pages"][0]["preprocessing"] == {}


def test_only_the_rasterized_pages_of_a_mixed_document_are_classified(session_factory, seeded_version):
    """The routing claim, on a document that has both kinds of page: the
    classifier sees exactly the pages bound for PaddleOCR and no others."""
    from ocr_worker.layout import NativeTextBlock

    # Docling's 1-indexed page numbering, matching the real API: page 1
    # has a text layer, page 2 does not.
    layout_doc = FakeLayoutDocument(
        page_count=2,
        native_text_by_page={1: [NativeTextBlock(text="Invoice No: INV-4471", bbox=(10, 10, 300, 40))]},
        rendered_image=_upright_page(),
    )
    classifier = StubClassifier(0, 0.93)
    pipeline, _, ocr_engine = make_pipeline(
        session_factory, layout_doc=layout_doc, ocr_text="scanned side", orientation_classifier=classifier
    )

    outcome = pipeline.process_version(seeded_version["version_id"])

    assert len(classifier.seen) == 1
    assert len(ocr_engine.calls) == 1
    methods = [p["method"] for p in outcome.output["pages"]]
    assert methods == ["native_text", "paddleocr"]
    assert outcome.output["pages"][0]["preprocessing"] == {}
    assert outcome.output["pages"][1]["preprocessing"]["orientation"]["angle"] == 0


def test_disabling_orientation_records_nothing_and_rotates_nothing(session_factory, seeded_version):
    """`OCR_ORIENTATION_ENABLED=false` has to be distinguishable in the
    output from "ran and found the page upright" -- otherwise a
    misconfigured deployment looks exactly like a working one."""
    received = _as_received(90)
    layout_doc = FakeLayoutDocument(page_count=1, native_text_by_page={}, rendered_image=received)
    pipeline, _, ocr_engine = make_pipeline(
        session_factory,
        layout_doc=layout_doc,
        ocr_text="anything",
        orientation_classifier=StubClassifier(90, 0.99),
        ocr_orientation_enabled=False,
    )

    outcome = pipeline.process_version(seeded_version["version_id"])

    assert outcome.output["pages"][0]["preprocessing"] == {}
    assert ocr_engine.calls[0].size == received.size  # never rotated


def test_a_spatially_matched_fields_bbox_is_stored_in_the_pages_own_space(
    session_factory, seeded_version
):
    """The field path has its own way back, separate from `blocks`: the
    line boxes spatial matching works from stay in reading space (so
    "below the label" still means below), and only the winning field's
    bbox is mapped back -- into document_ocr_field_results, which is what
    the review UI's highlights are drawn from.
    """
    received = _as_received(90)
    label_bbox = (30, 20, 90, 50)
    value_bbox = (110, 20, 200, 50)  # to the RIGHT of the label, in reading space

    layout_doc = FakeLayoutDocument(page_count=1, native_text_by_page={}, rendered_image=received)
    pipeline, _, _ = make_pipeline(
        session_factory,
        layout_doc=layout_doc,
        ocr_text="Author: Jane Doe",
        ocr_confidence=0.97,
        ocr_words=[
            WordResult(text="Author:", confidence=0.97, bbox=label_bbox),
            WordResult(text="Jane Doe", confidence=0.97, bbox=value_bbox),
        ],
        orientation_classifier=StubClassifier(90, 0.93),
    )

    outcome = pipeline.process_version(
        seeded_version["version_id"], requested_fields=[FieldSpec("author", "text")]
    )

    assert outcome.status == "completed"
    assert outcome.output["fields"]["author"]["value"] == "Jane Doe"
    with session_factory() as session:
        row = (
            session.query(DocumentOcrFieldResult)
            .filter_by(document_id=seeded_version["document_id"], field_key="author")
            .one()
        )
        # The value was found to the right of its label in reading space,
        # which on this page is *below* it in stored space. Asserted as
        # the exact expected box, not merely "different from the reading
        # one": an off-by-one or a swapped axis would still be different.
        expected = PageOrientation(
            angle=90,
            confidence=0.93,
            applied=True,
            original_width_px=received.size[0],
            original_height_px=received.size[1],
        ).to_original(value_bbox)
        assert tuple(row.source_bbox) == tuple(expected)


# ----------------------------------------------------------------------
# PaddleDocOrientationClassifier's result parsing
#
# The model itself is exercised by the eval run, not here -- but how its
# output is READ is pure Python and worth pinning, because paddlex hands
# back numpy arrays and the obvious `result.get("scores") or []` raises
# "truth value of an array with more than one element is ambiguous". That
# is a crash no unit test with list-shaped fakes would ever reach.
# ----------------------------------------------------------------------
np = pytest.importorskip("numpy")

from ocr_worker.orientation import PaddleDocOrientationClassifier  # noqa: E402


class _FakePaddleModel:
    def __init__(self, payload):
        self._payload = payload

    def predict(self, array):
        return [self._payload]


def _classifier_with(payload):
    classifier = PaddleDocOrientationClassifier()
    classifier._model = _FakePaddleModel(payload)
    return classifier


@pytest.mark.parametrize(
    "labels,scores",
    [
        (["90", "0", "180", "270"], [0.93, 0.04, 0.02, 0.01]),
        (np.array(["90", "0", "180", "270"]), np.array([0.93, 0.04, 0.02, 0.01])),
    ],
    ids=["python-lists", "numpy-arrays"],
)
def test_the_best_label_and_its_score_are_read_whatever_container_paddlex_uses(labels, scores):
    result = _classifier_with({"label_names": labels, "scores": scores}).classify(_upright_page())
    assert result.angle == 90
    assert result.confidence == pytest.approx(0.93)


@pytest.mark.parametrize(
    "payload",
    [
        {},
        {"label_names": [], "scores": []},
        {"label_names": ["upside-down"], "scores": [0.99]},
        {"label_names": ["45"], "scores": [0.99]},
        {"label_names": [None], "scores": [0.99]},
    ],
    ids=["no-keys", "empty", "non-numeric-label", "angle-off-the-quadrants", "null-label"],
)
def test_an_unreadable_prediction_means_leave_the_page_alone(payload):
    """Never an exception: a classifier that returns something
    unrecognized must degrade to "upright", because the alternative is
    failing a document over a preprocessing hint."""
    result = _classifier_with(payload).classify(_upright_page())
    assert result.angle == 0
    assert result.confidence == 0.0


def test_a_label_with_no_score_is_still_usable_but_never_applied():
    """A confidence of 0 is below any sane floor, so the angle is reported
    and the page is left alone -- the honest outcome when the model said
    what but not how sure."""
    result = _classifier_with({"label_names": ["180"], "scores": []}).classify(_upright_page())
    assert (result.angle, result.confidence) == (180, 0.0)
