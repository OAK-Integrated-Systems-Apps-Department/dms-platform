"""Per-page image quality assessment (production prompt section 3.3).

Measures four things on every rasterized page -- effective DPI, blur,
contrast and skew -- and turns the first three into a
`poor_image_quality` flag carrying the reasons that fired.

**The rule this module must never break: a page is never rejected for
quality.** Section 3.3 is explicit about it, and the alternate flow it
describes is "still attempt OCR, tell the user to re-scan". Everything
here is reporting. Nothing returns a value the caller is expected to
treat as a failure, and no function raises on a page it cannot measure --
an unmeasurable page comes back with `measured: False` and no reasons,
because "we looked and it was fine" and "we could not look" must not
collapse into the same output. That distinction is the same one
`preprocessing` draws for orientation, for the same reason.

Skew is measured and reported but deliberately does NOT raise the flag.
It is an input to deskewing (3.4 step 2), not a quality verdict: a
perfectly sharp, well-lit, high-resolution page that happens to sit at
1.5 degrees is not a page anyone should be asked to re-scan.
"""
from __future__ import annotations

import logging
import math

logger = logging.getLogger(__name__)

# Reason codes, exactly as production prompt 3.3 names them.
LOW_RESOLUTION = "LOW_RESOLUTION"
BLURRY = "BLURRY"
LOW_CONTRAST = "LOW_CONTRAST"

# Long-edge size skew detection downscales to. See `skew_angle`.
SKEW_MAX_EDGE_PX = 1000


def _downscaled_for_line_detection(gray, max_edge_px: int):
    """`gray` shrunk so its longest edge is at most `max_edge_px`.

    Returns the input unchanged when it is already small enough, so a
    phone photo and a thumbnail take the same code path.

    `INTER_AREA` rather than the default bilinear: downscaling with
    bilinear aliases thin strokes into dashes, which is precisely the
    structure the line detector is looking for. INTER_AREA averages the
    source pixels covering each destination pixel, so a 1px baseline
    becomes a fainter but continuous line instead of a dotted one.
    """
    import cv2

    height, width = gray.shape[:2]
    longest = max(height, width)
    if longest <= max_edge_px:
        return gray
    scale = max_edge_px / float(longest)
    return cv2.resize(
        gray, (max(1, int(width * scale)), max(1, int(height * scale))),
        interpolation=cv2.INTER_AREA,
    )


def effective_dpi(pixel_width: int, pixel_height: int, width_pt: float, height_pt: float) -> float | None:
    """The real resolution the page carries, in DPI.

    Derived from the source raster against the page's own point size
    rather than from any render setting: `OCR_RENDER_DPI` describes what
    we asked for, and on a page whose embedded scan is 150 DPI asking for
    300 does not create detail that was never captured. The number that
    matters for "should this be re-scanned" is the one in the file.

    Averages the two axes instead of taking one: a page scanned at
    different horizontal and vertical resolutions is rare but real (fax
    is the classic case -- 204x98 for standard mode), and reporting only
    the width would call a fax fine.
    """
    if not width_pt or not height_pt or pixel_width <= 0 or pixel_height <= 0:
        return None
    dpi_x = pixel_width / (width_pt / 72.0)
    dpi_y = pixel_height / (height_pt / 72.0)
    return (dpi_x + dpi_y) / 2.0


def blur_score(gray) -> float | None:
    """Variance of the Laplacian -- the standard sharpness proxy, and the
    one section 3.3 names.

    High variance means many strong intensity transitions, i.e. crisp
    edges; a blurred page smooths them away and the variance collapses.
    The absolute value is resolution-dependent, which is why the
    threshold is a configured number tuned against real pages rather than
    a constant with a citation.
    """
    import cv2

    try:
        return float(cv2.Laplacian(gray, cv2.CV_64F).var())
    except Exception:  # noqa: BLE001 -- measurement must never fail a page
        logger.exception("Could not compute blur score")
        return None


def contrast_score(gray) -> float | None:
    """RMS contrast: the standard deviation of pixel intensity, 0-255.

    Chosen over Michelson/peak-to-peak on purpose. Michelson contrast is
    driven entirely by the single brightest and single darkest pixel, so
    one speck of dust or one blown highlight pins it near 1.0 on a page
    that is otherwise uniformly grey -- exactly the washed-out scan this
    check exists to catch. The standard deviation uses every pixel.
    """
    import numpy as np

    try:
        return float(np.std(gray))
    except Exception:  # noqa: BLE001
        logger.exception("Could not compute contrast score")
        return None


def skew_angle(gray) -> float | None:
    """Dominant text skew in degrees, positive = counter-clockwise.

    Hough-based, per section 3.4 step 2's "Hough lines or projection
    profile". Hough is used rather than a projection profile because a
    projection profile has to be evaluated at every candidate angle,
    which on a 1500x1200 page is a real cost for a number we take on
    every page; the Hough transform finds the line angles directly.

    Restricted to near-horizontal lines (within +/-15 degrees). Without
    that restriction a page's vertical rules -- a table border, a form's
    column separator -- dominate the result and report a 90-degree skew
    on a perfectly upright page. Whole-page rotation is a separate,
    already-solved problem (orientation.py); this is only ever about the
    small residual angle a flatbed or a phone leaves behind.

    Returns None when there is nothing to measure, which is the honest
    answer for a blank or near-blank page and is NOT the same as 0.0.
    """
    import cv2
    import numpy as np

    try:
        # **Measured on a downscaled copy.** An angle is invariant under
        # uniform scaling, so the answer does not change -- but Canny plus
        # a probabilistic Hough transform over a full 1.8 MP page is by
        # far the most expensive thing in this module, and it is being run
        # for a number that never raises a flag.
        #
        # Measured on a 1530x1180 page:
        #     full resolution   704.1 ms
        #     downscaled ~1000px  ~90 ms
        #
        # 1000px on the long edge keeps text baselines several pixels
        # thick, which is what the line detector needs; going much below
        # that starts merging adjacent baselines into one blob.
        gray = _downscaled_for_line_detection(gray, SKEW_MAX_EDGE_PX)
        edges = cv2.Canny(gray, 50, 150, apertureSize=3)
        # minLineLength scaled to the page so the threshold means the same
        # thing on a 600px thumbnail and a 3000px scan.
        min_length = max(40, gray.shape[1] // 8)
        lines = cv2.HoughLinesP(
            edges, 1, math.pi / 180, threshold=100,
            minLineLength=min_length, maxLineGap=10,
        )
        if lines is None or len(lines) == 0:
            return None

        angles = []
        for line in lines:
            x1, y1, x2, y2 = line[0]
            if x2 == x1:
                continue
            angle = math.degrees(math.atan2(y2 - y1, x2 - x1))
            # Fold to (-90, 90], then keep only near-horizontal evidence.
            if angle > 90:
                angle -= 180
            elif angle <= -90:
                angle += 180
            if abs(angle) <= 15:
                angles.append(angle)

        if not angles:
            return None
        # Median, not mean: one long mis-detected diagonal (a fold, a
        # staple shadow, the edge of the scanner bed) would drag a mean
        # several degrees off on a page whose text is perfectly level.
        return float(np.median(angles))
    except Exception:  # noqa: BLE001
        logger.exception("Could not compute skew angle")
        return None


def assess_page(
    image,
    *,
    width_pt: float | None,
    height_pt: float | None,
    native_pixel_size: tuple[int, int] | None,
    min_dpi: float,
    min_blur: float,
    min_contrast: float,
) -> tuple[dict, list[str]]:
    """`(quality, reasons)` for one rendered page.

    `image` is the PIL image actually handed to OCR. `native_pixel_size`
    is the source raster's own dimensions when the page carries one --
    that, not the rendered size, is what `effective_dpi` is computed
    from, because the render is capped by `OCR_RENDER_MAX_MP` and a
    capped render would otherwise report a low DPI for a page whose scan
    is excellent.

    Never raises. A page this cannot measure comes back
    `{"measured": False}` with no reasons.
    """
    import numpy as np

    quality: dict = {"measured": False}
    reasons: list[str] = []

    try:
        import cv2

        rgb = np.array(image.convert("L") if image.mode != "L" else image)
        gray = rgb if rgb.ndim == 2 else cv2.cvtColor(rgb, cv2.COLOR_RGB2GRAY)
    except Exception:  # noqa: BLE001
        logger.exception("Could not read page image for quality assessment")
        return quality, reasons

    pixel_size = native_pixel_size or (gray.shape[1], gray.shape[0])
    dpi = effective_dpi(pixel_size[0], pixel_size[1], width_pt or 0.0, height_pt or 0.0)
    blur = blur_score(gray)
    contrast = contrast_score(gray)
    skew = skew_angle(gray)

    quality = {
        "measured": True,
        "effective_dpi": round(dpi, 1) if dpi is not None else None,
        "blur_score": round(blur, 2) if blur is not None else None,
        "contrast": round(contrast, 2) if contrast is not None else None,
        "skew_angle": round(skew, 2) if skew is not None else None,
        # Recorded so a reason can be read back against the bar it failed,
        # without needing the worker's config to interpret the output.
        "thresholds": {
            "min_dpi": min_dpi,
            "min_blur": min_blur,
            "min_contrast": min_contrast,
        },
    }

    # A threshold only fires on a measurement that exists. A None here
    # means the measurement failed, and "we could not measure the blur"
    # is not evidence that the page is blurry.
    if dpi is not None and dpi < min_dpi:
        reasons.append(LOW_RESOLUTION)
    if blur is not None and blur < min_blur:
        reasons.append(BLURRY)
    if contrast is not None and contrast < min_contrast:
        reasons.append(LOW_CONTRAST)

    return quality, reasons
