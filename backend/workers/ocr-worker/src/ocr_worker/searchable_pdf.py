"""Searchable PDF rendition (production prompt section 3.11).

A PDF that looks exactly like the source and selects like a text
document: each page is the **original page, copied unchanged**, with the
recognised text drawn over it invisibly (PDF render mode 3) where — and
only where — recognition actually happened.

Three properties, each of which took a decision:

**The page is copied, never re-rasterized.** `show_pdf_page` draws the
source page into the rendition with its own content intact: a 300 DPI
scan stays 300 DPI, vector text stays vector, and a user never gets back
a lower-resolution copy of what they uploaded. The earlier version drew
the `OCR_RENDER_MAX_MP` raster instead, which silently downsampled every
scan above 1.81 MP to the resolution OCR happened to need. It also means
no decoded page image is held here at all, so memory per page is bounded
by PyMuPDF's own streaming rather than by the page's pixel count.

A bare PNG/JPEG/TIFF/HEIC opens in PyMuPDF as a one-page document
wrapping that image at native size, so the same copy path serves every
format — there is no image branch.

**Invisible text goes only over regions that were recognised.** A
`native_text` page already carries its own, exact text layer; overlaying
a recognised approximation would give the page two text layers, so
Ctrl-F finds everything twice and copy-paste yields doubled text. Those
pages are copied with no overlay at all. A `hybrid` page gets an overlay
for its OCR'd regions only, for the same reason.

**The boxes are LINES, not words.** PaddleOCR's detector returns text
lines — measured: 9 boxes for a receipt, 6 of them containing spaces,
e.g. `'Receipt No: RCPT-33210'` as one box. So one invisible run is
written per line, and the font size is fitted to the line's box width so
that character positions inside it land approximately over the ink. A
viewer selecting a single word within a line will therefore highlight
approximately, not exactly; selecting a line is exact.
"""
from __future__ import annotations

import logging

logger = logging.getLogger(__name__)

# Any Base-14 font works: nothing is drawn, so no glyph is ever seen.
# Helvetica's widths are the closest of that set to the proportional text
# most documents use, which keeps a fitted run near the ink it covers.
_FONT = "helv"

# A box smaller than this in points is a detection artifact (a speck, a
# rule fragment), not a line. Writing text into it would put a selectable
# token where the page has no text.
_MIN_BOX_PT = 1.0

# Bounds on the fitted font size. The lower bound keeps a long line in a
# short box from collapsing to an unusable size; the upper stops a short
# string in a wide box (a heading, a lone figure in a table cell) from
# being inflated far beyond the ink it covers.
_MIN_FONT_PT = 1.0
_MAX_FONT_SCALE = 1.4


class SearchablePdfBuilder:
    """Accumulates pages into one searchable PDF.

    Incremental by design — `add_source_page` per page, then `finish()` —
    so nothing accumulates per page beyond the output document itself.

    Every failure is *recorded*, never swallowed silently: `failures`
    holds one human-readable reason per page that could not be added, and
    the caller writes them into the OCR output so a missing or partial
    rendition is visible rather than merely absent.
    """

    def __init__(self):
        import fitz  # PyMuPDF

        self._fitz = fitz
        self._doc = fitz.open()
        self._pages = 0
        self.failures: list[str] = []

    @property
    def page_count(self) -> int:
        return self._pages

    def add_source_page(self, source_doc, page_no: int, text_boxes, *, pixels_per_point: float,
                        text_rotation: int = 0) -> None:
        """Copy page `page_no` of `source_doc` and overlay `text_boxes`.

        `text_boxes` is `[(text, (x0, y0, x1, y1))]` in the OCR RENDER's
        pixel space; `pixels_per_point` is that render's scale, so points
        = pixels / scale. Pass an empty list for a page whose text layer
        is already exact (`native_text`), and only the OCR'd regions for a
        `hybrid` page.

        `text_rotation` (0/90/180/270) is the direction the ink runs on
        the ORIGINAL page. On a page that arrived sideways, OCR read a
        corrected raster and `orientation.to_original` mapped each box
        back -- which leaves the box tall and narrow, its *height* being
        the line's length. Writing horizontal text into that box puts the
        baseline tens of points above the ink (measured: up to 43.5pt
        off), so the run has to be rotated to follow the line.

        Never raises. A rendition is additive: losing it must not fail a
        document whose text and fields extracted perfectly. The reason is
        appended to `failures`.
        """
        try:
            self._add_source_page(source_doc, page_no, text_boxes, pixels_per_point, text_rotation)
        except Exception as exc:  # noqa: BLE001 -- see docstring
            reason = f"page {page_no + 1}: {type(exc).__name__}: {exc}"
            self.failures.append(reason)
            logger.exception("Could not add page %d to the searchable PDF", page_no + 1)

    def _add_source_page(self, source_doc, page_no: int, text_boxes, pixels_per_point: float,
                         text_rotation: int = 0) -> None:
        if source_doc is None:
            raise ValueError("no source document to copy the page from")

        source_page = source_doc.load_page(page_no)
        rect = source_page.rect
        if rect.width <= 0 or rect.height <= 0:
            raise ValueError(f"source page has no area ({rect.width}x{rect.height})")

        new_page = self._doc.new_page(width=rect.width, height=rect.height)
        # The copy. Preserves the page's own resolution and vector
        # content -- and, on a native-text page, its own text layer.
        new_page.show_pdf_page(new_page.rect, source_doc, page_no)

        if text_boxes and pixels_per_point > 0:
            self._overlay(new_page, text_boxes, pixels_per_point, text_rotation)

        self._pages += 1

    def _overlay(self, page, text_boxes, pixels_per_point: float, text_rotation: int = 0) -> None:
        fitz = self._fitz
        rotation = int(text_rotation) % 360
        if rotation not in (0, 90, 180, 270):
            rotation = 0
        # Which way the line RUNS, and therefore which dimension of the
        # box the run must be fitted to. For an upright line that is the
        # width; for a sideways one the box is tall and narrow and it is
        # the height.
        along_height = rotation in (90, 270)

        for text, bbox in text_boxes:
            if not text or not str(text).strip() or not bbox or len(bbox) != 4:
                continue
            x0, y0, x1, y1 = (float(v) / pixels_per_point for v in bbox)
            box_w, box_h = x1 - x0, y1 - y0
            if box_w < _MIN_BOX_PT or box_h < _MIN_BOX_PT:
                continue

            value = str(text)
            run_length = box_h if along_height else box_w
            thickness = box_w if along_height else box_h

            # Fit the run to the box's extent ALONG the text direction.
            # These boxes are lines, so sizing by thickness alone leaves a
            # long line's characters bunched at one end and a short one's
            # stretched -- and a viewer's highlight then drifts away from
            # the ink along the line. Capped against the thickness so a
            # two-character cell in a wide column is not inflated to fill
            # it.
            try:
                natural = fitz.get_text_length(value, fontname=_FONT, fontsize=10.0)
            except Exception:  # noqa: BLE001
                natural = 0.0
            if natural > 0:
                font_size = min(10.0 * run_length / natural, thickness * _MAX_FONT_SCALE)
            else:
                font_size = thickness * 0.8
            font_size = max(_MIN_FONT_PT, font_size)

            # Where the run starts. `insert_text(rotate=...)` writes from
            # this point in the rotated direction, so each case begins at
            # a different corner.
            #
            # **The corners were measured, not derived.** Sweeping all
            # four rotations against all eight candidate origins on the
            # corpus's rotated fixtures (`invoice-rotated_90-00` and
            # `invoice-rotated_270-00`) gave, as median offset from the
            # ink:
            #
            #     rot=90  origin=bottom-right      1.2pt   <- best, both
            #     rot=270 origin=top-left+inset    2.8pt
            #     rot=90  origin=bottom-right-inset 3.9pt
            #     rot=0   (unrotated)             117.0pt
            #
            # 1.2pt is on par with the 1.8-2.8pt an upright page achieves,
            # so a sideways line lands on its own ink. Deriving this from
            # first principles went wrong twice -- PyMuPDF's rotation is
            # about the insertion point, and which corner that makes the
            # start of the run is not obvious for any of the three
            # non-zero cases.
            inset = thickness * 0.15
            if rotation == 90:
                origin = fitz.Point(x1, y1)          # bottom-right, no inset
            elif rotation == 180:
                origin = fitz.Point(x1, y0 + inset)  # runs leftward from the top
            elif rotation == 270:
                origin = fitz.Point(x0 + inset, y0)
            else:
                origin = fitz.Point(x0, y1 - inset)  # upright baseline

            page.insert_text(
                origin,
                value,
                fontname=_FONT,
                fontsize=font_size,
                rotate=rotation,
                render_mode=3,  # invisible: no marks, still selectable
            )

    def finish(self) -> bytes | None:
        """The PDF bytes, or None if no page was added."""
        try:
            if self._pages == 0:
                return None
            return self._doc.tobytes(deflate=True, garbage=3)
        except Exception as exc:  # noqa: BLE001
            self.failures.append(f"serialise: {type(exc).__name__}: {exc}")
            logger.exception("Could not serialise the searchable PDF")
            return None
        finally:
            self.close()

    def close(self) -> None:
        try:
            self._doc.close()
        except Exception:  # noqa: BLE001
            pass

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        self.close()
        return False


def boxes_for_overlay(page_result) -> list[tuple[str, tuple]]:
    """Which of a page's blocks need an invisible overlay.

    * `native_text` -> nothing. The page's own text layer is exact and
      already copied with it; a second layer means Ctrl-F finds every
      word twice and copy-paste yields doubled text.
    * `hybrid` -> only the blocks OCR read. Its native blocks carry
      `source == "native_text"` and are already in the copied page.
    * `paddleocr` -> every block; the page had no text layer to copy.
    """
    method = getattr(page_result, "method", None)
    if method == "native_text":
        return []
    boxes = []
    for block in getattr(page_result, "blocks", None) or []:
        if method == "hybrid" and block.get("source") != "paddleocr":
            continue
        text, bbox = block.get("text"), block.get("bbox")
        if text and bbox:
            boxes.append((text, tuple(bbox)))
    return boxes


def searchable_pdf_key(tenant_id, version_id, rendition_id) -> str:
    """Object key for the rendition, mirroring `storage.ocr_output_key`'s
    tenant-first layout so a per-tenant lifecycle policy or a tenant
    delete stays expressible as a prefix operation."""
    return f"tenants/{tenant_id}/versions/{version_id}/renditions/{rendition_id}.pdf"


def result_of(builder) -> tuple[bytes | None, list[str]]:
    """`(pdf bytes or None, reasons)` from a finished builder.

    Bundled so the pipeline threads ONE value through `_run_inference`'s
    return rather than two, and so the reasons cannot be dropped on the
    way -- production prompt's "no silent fallback": a rendition that did
    not happen must say why, in the output, not only in a log line.
    """
    failures = list(builder.failures)
    data = builder.finish()
    failures.extend(f for f in builder.failures if f not in failures)
    return data, failures


def text_rotation_for(orientation) -> int:
    """Which way the ink runs on the ORIGINAL page, from the orientation
    decision that was applied to correct it.

    `orientation.angle` is the rotation applied to the raster to make it
    upright, so the ink on the stored page runs at the inverse. Both the
    90 and 270 cases are written with `rotate=90`: measured on the
    corpus's `invoice-rotated_90-00` and `invoice-rotated_270-00`
    fixtures, that lands every line within 1.2pt of its ink for both,
    while 270 lands at 2.8pt. Search matches on the string, so the run's
    reading direction does not change what is found -- only where the
    highlight falls, and 90 puts it in the right place for both.

    Returns 0 when no correction was applied, which covers every upright
    page and every native-text page.
    """
    if orientation is None or not getattr(orientation, "applied", False):
        return 0
    angle = int(getattr(orientation, "angle", 0) or 0) % 360
    if angle == 180:
        return 180
    if angle in (90, 270):
        return 90
    return 0
