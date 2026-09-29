from __future__ import annotations

from dataclasses import dataclass
from typing import Protocol

# The four whole-page rotations `PP-LCNet_x1_0_doc_ori` distinguishes, and
# the only ones this module will ever apply. Small-angle skew (a page fed
# crookedly into a scanner) is a different problem with a different fix --
# see Step 4's deskew, and `skewed_2deg` in the eval corpus, which this
# classifier correctly leaves at 0.
VALID_ANGLES = (0, 90, 180, 270)


@dataclass(frozen=True)
class OrientationResult:
    """`angle` is how far CLOCKWISE the page arrived rotated, so correcting
    it means rotating COUNTER-clockwise by the same amount. Verified
    against the eval corpus's own `rotated_*` conditions, which are
    generated as `upright.rotate(-angle, expand=True)` (PIL rotates
    counter-clockwise, so a negative angle is clockwise) and which this
    classifier labels with exactly that angle.
    """

    angle: int
    confidence: float


class OrientationClassifier(Protocol):
    def classify(self, image) -> OrientationResult: ...


@dataclass(frozen=True)
class PageOrientation:
    """What was decided for one page, and the inverse map that decision
    obliges every bbox to go through.

    OCR runs on the *corrected* (upright) raster, but every bbox in the
    output contract stays in the space of the page as it was actually
    stored -- the space a viewer renders and a review UI draws highlights
    over (production prompt section 3.13). A consumer must not have to
    know that this worker rotated anything to place a box correctly, so
    the rotation is undone on the coordinates before they leave here, and
    recorded in `pages[].preprocessing` so the decision is auditable.
    """

    angle: int
    confidence: float
    applied: bool
    original_width_px: int
    original_height_px: int

    def to_original(self, bbox):
        """A bbox detected in the corrected image -> the same region in
        the original page raster.

        Each branch is the exact inverse of `correct_image`'s rotation,
        derived empirically against PIL rather than from first principles
        and pinned by `test_orientation.py`'s round-trip tests.
        """
        if not self.applied or bbox is None:
            return bbox
        x0, y0, x1, y1 = bbox
        ow, oh = self.original_width_px, self.original_height_px
        if self.angle == 90:
            return (ow - y1, x0, ow - y0, x1)
        if self.angle == 180:
            return (ow - x1, oh - y1, ow - x0, oh - y0)
        if self.angle == 270:
            return (y0, oh - x1, y1, oh - x0)
        return bbox

    def upright_page_size(self, width, height):
        """`(width, height)` of the corrected raster OCR actually saw.

        A 90/270 correction swaps the page's dimensions, and anything
        reasoning in the corrected space needs the corrected size to
        reason against -- notably extraction's "value stacked below the
        label" radius, which is a fraction of page height (see
        `_spatial_value_on_page`). Handing it the original portrait height
        for a page that OCR read as landscape would make that radius ~40%
        too generous in one orientation and too tight in the other.
        """
        if self.applied and self.angle in (90, 270):
            return height, width
        return width, height

    def to_dict(self) -> dict:
        return {
            "angle": self.angle,
            "confidence": round(self.confidence, 4),
            "applied": self.applied,
        }


def correct_image(image, angle: int):
    """The upright raster OCR should see, or the image unchanged at 0.

    `expand=True` matters: a 90/270 correction swaps the page's pixel
    dimensions, and without it PIL would crop the page back to its
    pre-rotation box and throw away most of a portrait page's content.
    """
    if angle == 0:
        return image
    if angle not in VALID_ANGLES:
        raise ValueError(f"orientation angle must be one of {VALID_ANGLES}, got {angle!r}")
    # PIL rotates counter-clockwise for a positive angle, which is exactly
    # the correction for a page that arrived `angle` degrees clockwise.
    return image.rotate(angle, expand=True)


class NullOrientationClassifier:
    """What the pipeline uses when orientation correction is switched off
    (`OCR_ORIENTATION_ENABLED=false`) and what unit tests use by default.

    A no-op classifier rather than a `None` check at every call site: the
    pipeline then has exactly one code path, so the disabled configuration
    exercises the same arithmetic as the enabled one and cannot rot.
    """

    def classify(self, image) -> OrientationResult:
        return OrientationResult(angle=0, confidence=1.0)

    def warm_up(self) -> None:
        return None


class PaddleDocOrientationClassifier:
    """PaddleOCR 3.x's own whole-page orientation model
    (`PP-LCNet_x1_0_doc_ori`), driven directly rather than through
    `PaddleOCR(use_doc_orientation_classify=True)`.

    Driving it ourselves is the whole point. Inside the PaddleOCR pipeline
    the doc-preprocessor rotates the page and every box comes back in the
    *rotated* image's coordinate space -- which would silently invalidate
    `bbox`/`bbox_norm` against the page a viewer actually renders, the one
    contract Step 2 just established. Here the rotation and its inverse
    both live in this module, so the coordinates that leave the worker
    keep meaning what they say.

    Constructed lazily and cached, mirroring `PaddleOcrEngine`, so
    importing this module -- or constructing this class in a test with a
    fake in its place -- never requires paddleocr to be installed.

    Measured in the running container: 1 MiB marginal RSS in a process
    that already holds PaddleOCR (the weights are a ~7MB PP-LCNet; the
    600+ MiB a standalone probe attributes to this model is paddle's own
    framework import, already paid for by the OCR engine), and 0.02-0.07s
    per page against ~20s for that page's actual OCR.
    """

    def __init__(self):
        self._model = None

    def warm_up(self) -> None:
        self._get_model()

    def _get_model(self):
        if self._model is None:
            import os

            # Same reason as ocr_engine.py's: read once at paddlex import
            # time, so it has to be set before the first import anywhere
            # in the process.
            os.environ.setdefault("PADDLE_PDX_ENABLE_MKLDNN_BYDEFAULT", "False")

            from paddleocr import DocImgOrientationClassification

            self._model = DocImgOrientationClassification()
        return self._model

    def classify(self, image) -> OrientationResult:
        import numpy as np

        for result in self._get_model().predict(np.array(image)):
            # paddlex returns parallel `label_names`/`scores` sequences,
            # best first; the label is the angle as a string ("0", "90",
            # ...). Tested with explicit `is None` / `len()` rather than
            # truthiness because either can come back as a numpy array,
            # and `array or []` raises "truth value of an array with more
            # than one element is ambiguous" -- a crash that would only
            # appear once a real model ran, i.e. never in a unit test.
            labels = result.get("label_names")
            scores = result.get("scores")
            if labels is None or len(labels) == 0:
                continue
            try:
                angle = int(str(labels[0]).strip())
            except (TypeError, ValueError):
                continue
            if angle not in VALID_ANGLES:
                continue
            confidence = float(scores[0]) if scores is not None and len(scores) else 0.0
            return OrientationResult(angle=angle, confidence=confidence)
        # No usable prediction is not an error -- it means "leave the page
        # alone", which is always a safe answer.
        return OrientationResult(angle=0, confidence=0.0)


def decide(classifier: OrientationClassifier, image, *, original_size, min_confidence: float):
    """Classify one rendered page and return `(image_for_ocr, decision)`.

    `original_size` is passed in rather than read off the image, because
    the authoritative raster size is the rasterizer's own pixmap
    dimensions, which `layout.PageImage` already carries -- the same
    source `PageGeometry` normalizes against. Taking it from the image
    object instead would be a second, silently-drifting source for the
    one number the inverse map depends on.

    Rotating an already-upright page is the expensive mistake here -- it
    turns a page that reads at 95% into one that reads at 12% -- so a
    prediction below `min_confidence` is recorded and *not* applied. The
    page is still OCR'd either way, never rejected.
    """
    original_width_px, original_height_px = original_size
    result = classifier.classify(image)
    angle = result.angle if result.angle in VALID_ANGLES else 0
    applied = angle != 0 and result.confidence >= min_confidence
    decision = PageOrientation(
        angle=angle,
        confidence=result.confidence,
        applied=applied,
        original_width_px=original_width_px,
        original_height_px=original_height_px,
    )
    return (correct_image(image, angle) if applied else image), decision
