"""The degradation matrix -- one transform per real-world failure mode.

Each condition maps a clean native-text PDF to the bytes a real upload of
that kind would carry, and declares its own mime type and whether it
counts as degraded (which Step 5 scores the poor-image-quality flags
against, for free, with no extra annotation).

The conditions are chosen from what `docs/ocr/failure-analysis.md`
actually found, not from a generic list: `rotated_*` because a rotated
scan returned zero fields, `garbage_text_layer` because one stray
`(cid:NN)` glyph routes a whole page to the native path, `hybrid_page`
because a typed header over a scanned body is never OCR'd today, and
`low_dpi_scan_72` because the renderer's real output is only ~137 DPI.
"""

from __future__ import annotations

import io
from dataclasses import dataclass
from typing import Callable


@dataclass(frozen=True)
class Condition:
    name: str
    mime_type: str
    extension: str
    degraded: bool
    apply: Callable[[bytes, int], bytes]
    #  Conditions that only make sense for a multi-page source.
    page_count: int = 1


# ----------------------------------------------------------------------
# Helpers
# ----------------------------------------------------------------------
def _render_pages(pdf_bytes: bytes, dpi: int):
    """Rasterize every page of a PDF to PIL Images at a true DPI.

    Uses the requested DPI verbatim -- no cap. This is the *corpus*
    renderer, which models what a scanner produced; capping here would
    quietly make the low-DPI condition indistinguishable from the clean
    one and destroy the very comparison the harness exists to make.
    """
    import fitz
    from PIL import Image

    doc = fitz.open(stream=pdf_bytes, filetype="pdf")
    images = []
    zoom = dpi / 72.0
    try:
        for page in doc:
            pix = page.get_pixmap(matrix=fitz.Matrix(zoom, zoom))
            images.append(Image.frombytes("RGB", (pix.width, pix.height), pix.samples))
            pix = None
    finally:
        doc.close()
    return images


def _images_to_image_only_pdf(images, dpi: int) -> bytes:
    """Wrap rasters back into a PDF with no text layer at all.

    This is what a scanner or a "print to PDF from a scan" produces, and
    it is the input that forces the PaddleOCR path.

    `dpi` must be the DPI the images were rendered at, so the page keeps
    its true physical size. This was hardcoded to 200 at first, and it
    silently destroyed the corpus's whole point: a 300-DPI scan of an A4
    page was declared to be a 893x1263pt page rather than 595x842, so the
    renderer's 1600px cap scaled it back down to the same raster as every
    other condition, and a 100-DPI scan became a 298x421pt page that
    escaped the cap entirely and was rendered at its native resolution.
    The result was that `clean_scan_300`, the low-DPI scan and
    `fax_g4_tiff` all produced byte-identical text and byte-identical CER
    -- a corpus that could not detect the very resolution difference it
    exists to measure.

    With the real DPI, the page is A4 in all three cases and the images
    differ in resolution, which is what a real scanner produces and what
    Step 4's tiling change has to be judged against.
    """
    import fitz

    out = fitz.open()
    try:
        for image in images:
            buffer = io.BytesIO()
            image.save(buffer, format="PNG")
            width_pt = image.width * 72.0 / dpi
            height_pt = image.height * 72.0 / dpi
            page = out.new_page(width=width_pt, height=height_pt)
            page.insert_image(page.rect, stream=buffer.getvalue())
        return out.tobytes()
    finally:
        out.close()


def _scan_degrade(image, dpi: int, seed: int):
    """A scanner's optics and sensor, applied to a perfect render.

    Without this the corpus is unusable for its main purpose. Vector text
    rasterized straight from a PDF is cleaner than anything a scanner has
    ever produced -- every glyph edge is exact, the background is pure
    white, there is no grain. PP-OCRv6 reads that essentially perfectly at
    any resolution, so `clean_scan_300`, `low_dpi_scan_72`, `fax_g4_tiff`
    and `shadowed_photo` all returned byte-identical text and byte-identical
    CER. A corpus that scores a 72-DPI fax exactly like a 300-DPI scan
    cannot answer the question Step 4 exists to ask.

    Two effects, both standard document-degradation modelling:

    - **Optical blur (the scanner's MTF).** Sigma scales with DPI so the
      blur is a fixed *physical* size -- the same optical system at every
      resolution. Scaling it in pixels instead would blur the low-DPI scan
      less, which is backwards.
    - **Sensor noise.** Fixed in intensity, independent of resolution,
      which is what a real sensor does.

    What is left after both is a genuine resolution difference, which is
    exactly the variable under test.
    """
    import numpy as np
    from PIL import Image, ImageFilter

    blurred = image.filter(ImageFilter.GaussianBlur(radius=0.8 * dpi / 200.0))
    rng = np.random.default_rng(seed)
    array = np.asarray(blurred).astype(np.float32)
    noisy = np.clip(array + rng.normal(0.0, 6.0, array.shape), 0, 255).astype(np.uint8)
    return Image.fromarray(noisy)


def _to_png(image) -> bytes:
    buffer = io.BytesIO()
    image.save(buffer, format="PNG")
    return buffer.getvalue()


def _to_jpeg(image, quality: int) -> bytes:
    buffer = io.BytesIO()
    image.convert("RGB").save(buffer, format="JPEG", quality=quality)
    return buffer.getvalue()


# ----------------------------------------------------------------------
# Transforms
# ----------------------------------------------------------------------
def _native(pdf_bytes: bytes, _seed: int) -> bytes:
    return pdf_bytes


def _scan_at(dpi: int):
    def transform(pdf_bytes: bytes, seed: int) -> bytes:
        pages = [
            _scan_degrade(page, dpi, seed + n)
            for n, page in enumerate(_render_pages(pdf_bytes, dpi))
        ]
        return _images_to_image_only_pdf(pages, dpi)

    return transform


def _skew(degrees: float):
    def transform(pdf_bytes: bytes, _seed: int) -> bytes:
        from PIL import Image

        rotated = [
            page.rotate(degrees, resample=Image.BICUBIC, expand=True, fillcolor=(255, 255, 255))
            for page in _render_pages(pdf_bytes, 200)
        ]
        return _images_to_image_only_pdf(rotated, 200)

    return transform


def _rotate_raster(degrees: int):
    """Whole-page rotation, delivered as a raster.

    Deliberately a PNG, not a PDF with a /Rotate flag: the failure
    analysis showed those are two different code paths and only the
    raster one loses every field (the native path reads the content
    stream and is indifferent to /Rotate).
    """

    def transform(pdf_bytes: bytes, _seed: int) -> bytes:
        page = _render_pages(pdf_bytes, 200)[0]
        return _to_png(page.rotate(-degrees, expand=True))

    return transform


def _perspective(pdf_bytes: bytes, seed: int) -> bytes:
    """A phone photo held at an angle: keystone warp plus JPEG artifacts."""
    import cv2
    import numpy as np
    from PIL import Image

    page = _scan_degrade(_render_pages(pdf_bytes, 200)[0], 200, seed)
    array = np.array(page)
    h, w = array.shape[:2]

    rng = np.random.default_rng(seed)
    dx = w * (0.04 + 0.02 * rng.random())
    dy = h * (0.02 + 0.015 * rng.random())

    source = np.float32([[0, 0], [w, 0], [w, h], [0, h]])
    target = np.float32([[dx, dy], [w - dx * 0.4, 0], [w, h - dy], [dx * 0.6, h]])
    matrix = cv2.getPerspectiveTransform(source, target)
    warped = cv2.warpPerspective(
        array, matrix, (w, h), borderMode=cv2.BORDER_CONSTANT, borderValue=(245, 245, 245)
    )
    return _to_jpeg(Image.fromarray(warped), quality=85)


def _shadowed(pdf_bytes: bytes, seed: int) -> bytes:
    """A photo with a soft shadow gradient across the page.

    Multiplicative, not additive, so it darkens the paper the way a real
    shadow does rather than flattening the whole image toward grey --
    which is what makes it a contrast problem CLAHE can plausibly fix,
    and therefore a fair test of Step 6.
    """
    import numpy as np
    from PIL import Image

    page = _scan_degrade(_render_pages(pdf_bytes, 200)[0], 200, seed)
    array = np.array(page).astype(np.float32)
    h, w = array.shape[:2]

    rng = np.random.default_rng(seed)
    axis = np.linspace(0, 1, w, dtype=np.float32) ** (1.4 + 0.4 * rng.random())
    gradient = (0.42 + 0.58 * axis)[None, :, None]
    shaded = np.clip(array * gradient, 0, 255).astype(np.uint8)
    return _to_jpeg(Image.fromarray(shaded), quality=80)


def _fax_g4(pdf_bytes: bytes, seed: int) -> bytes:
    """1-bit CCITT Group 4 TIFF -- what a fax or a cheap MFP produces.

    Degraded before thresholding, not after: a real fax thresholds a noisy
    analogue scan, which is what makes its 1-bit output ragged. Thresholding
    a perfect render just gives perfect 1-bit text.
    """
    page = _scan_degrade(_render_pages(pdf_bytes, 200)[0], 200, seed)
    buffer = io.BytesIO()
    page.convert("L").convert("1").save(buffer, format="TIFF", compression="group4")
    return buffer.getvalue()


def _hybrid_page(pdf_bytes: bytes, _seed: int) -> bytes:
    """A real text layer for the header, a pasted raster for the body.

    This is the shape section 3.6 calls `hybrid` and the one today's
    routing gets wrong: the header's native text makes `native_blocks`
    truthy, so PaddleOCR never runs and the entire body is lost.
    """
    import fitz

    source = fitz.open(stream=pdf_bytes, filetype="pdf")
    try:
        page = source[0]
        rect = page.rect
        body_rect = fitz.Rect(rect.x0, rect.y0 + 120, rect.x1, rect.y1)
        body_pixmap = page.get_pixmap(matrix=fitz.Matrix(200 / 72.0, 200 / 72.0), clip=body_rect)
        body_png = body_pixmap.tobytes("png")

        out = fitz.open()
        new_page = out.new_page(width=rect.width, height=rect.height)
        # Header: re-drawn as real text so the page genuinely has a text
        # layer, rather than copied (which would bring the body's text
        # along with it and defeat the point).
        header_words = [w for w in page.get_text("words") if w[3] <= rect.y0 + 120]
        for word in header_words:
            new_page.insert_text((word[0], word[3]), word[4], fontname="helv", fontsize=9)
        new_page.insert_image(body_rect, stream=body_png)
        result = out.tobytes()
        out.close()
        return result
    finally:
        source.close()


def _garbage_text_layer(pdf_bytes: bytes, _seed: int) -> bytes:
    """An image page carrying a junk text layer, invisible to a reader.

    Render mode 3 is PDF's "invisible" text -- exactly what a bad
    scanner-OCR layer looks like. The glyphs spell `(cid:NN)`, the
    signature the failure analysis names. A reader sees a clean scan; the
    router sees a text layer and takes the native path, returning
    confident garbage.
    """
    import fitz

    page_image = _render_pages(pdf_bytes, 200)[0]
    buffer = io.BytesIO()
    page_image.save(buffer, format="PNG")

    out = fitz.open()
    try:
        width_pt = page_image.width * 72.0 / 200.0
        height_pt = page_image.height * 72.0 / 200.0
        page = out.new_page(width=width_pt, height=height_pt)
        page.insert_image(page.rect, stream=buffer.getvalue())
        junk = " ".join(f"(cid:{n})" for n in range(20, 60))
        y = 120.0
        for _ in range(12):
            page.insert_text((56, y), junk, fontname="helv", fontsize=8, render_mode=3)
            y += 22
        return out.tobytes()
    finally:
        out.close()


# ----------------------------------------------------------------------
# The matrix
# ----------------------------------------------------------------------
CONDITIONS: list[Condition] = [
    Condition("native_pdf", "application/pdf", "pdf", False, _native),
    Condition("clean_scan_300", "application/pdf", "pdf", False, _scan_at(300)),
    # 72 DPI, not 100: at 100 the fine print was still read perfectly.
    # 72 DPI is a real setting on cheap MFPs and fax-grade scans.
    Condition("low_dpi_scan_72", "application/pdf", "pdf", True, _scan_at(72)),
    Condition("skewed_2deg", "application/pdf", "pdf", True, _skew(2.0)),
    Condition("rotated_90", "image/png", "png", False, _rotate_raster(90)),
    Condition("rotated_180", "image/png", "png", False, _rotate_raster(180)),
    Condition("rotated_270", "image/png", "png", False, _rotate_raster(270)),
    Condition("phone_photo_perspective", "image/jpeg", "jpg", True, _perspective),
    Condition("shadowed_photo", "image/jpeg", "jpg", True, _shadowed),
    Condition("fax_g4_tiff", "image/tiff", "tiff", True, _fax_g4),
    Condition("multipage", "application/pdf", "pdf", False, _native, page_count=3),
    Condition("hybrid_page", "application/pdf", "pdf", False, _hybrid_page),
    Condition("garbage_text_layer", "application/pdf", "pdf", False, _garbage_text_layer),
]

BY_NAME = {condition.name: condition for condition in CONDITIONS}
