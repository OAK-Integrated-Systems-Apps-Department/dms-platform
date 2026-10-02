from __future__ import annotations

import logging
import math
import tempfile
from concurrent.futures import ThreadPoolExecutor
from concurrent.futures import TimeoutError as FuturesTimeoutError
from dataclasses import dataclass
from pathlib import Path
from types import SimpleNamespace
from typing import Protocol

from .errors import CorruptFileError, EncryptedPdfError

logger = logging.getLogger(__name__)

# The fallback rendered-area budget, in megapixels, used when a caller
# passes none. `config.Settings.ocr_render_max_mp` is the configured one
# and the pipeline always passes it; this exists so that forgetting to
# is survivable rather than fatal. Kept in step with that default.
DEFAULT_RENDER_MAX_MP = 1.81


@dataclass(frozen=True)
class PageGeometry:
    """The one authoritative mapping between a page's PDF-point geometry
    and the pixel space every bbox on that page is expressed in.

    This exists because there were previously *two* mappings, silently
    disagreeing. `render_page` capped its zoom at
    `_MAX_RENDER_DIMENSION_PX / longest_side_pt`, while
    `native_text_blocks_for_page`, `native_text_lines_for_page` and
    `tables_for_page` all scaled by the uncapped `dpi / 72`. At the
    default 200 DPI the cap is always active on an ordinary page, so a
    native-text bbox was in a 1700x2200 space while a PaddleOCR bbox on
    the same page was in a 1236x1600 one -- a factor of ~1.37, against a
    README that claimed "all bboxes, from every method, are in the same
    page-pixel coordinate space".

    Nothing noticed because routing is exclusive per page today, so the
    two never appeared together. Per-page hybrid routing puts them on the
    same page deliberately, and normalizing coordinates on top of the
    disagreement would have baked a 37% error into the output contract
    permanently.

    Normalization divides by `width_pt * render_scale` as a float rather
    than by the rounded `width_px`, so a native bbox and an OCR bbox
    normalize identically even when the rasterizer's own rounding lands a
    pixel either way.
    """

    page_no: int              # 0-indexed, PyMuPDF's convention
    width_pt: float
    height_pt: float
    render_scale: float       # pixels per point -- capped, and the only scale
    width_px: int
    height_px: int
    # The source raster's own pixel size, when this page carries one and
    # it is LARGER than what was rendered -- i.e. exactly when scanner
    # detail is being thrown away. None when nothing was discarded (a
    # vector page, or a raster we rendered at or above its own
    # resolution). See `native_pixel_size` and `to_dict`.
    downsampled_from: tuple[int, int] | None = None

    @property
    def exact_width_px(self) -> float:
        return self.width_pt * self.render_scale

    @property
    def exact_height_px(self) -> float:
        return self.height_pt * self.render_scale

    def normalize(self, bbox: tuple[float, float, float, float] | None):
        """Pixel bbox -> 0..1 fractions of page width/height.

        Returns None for a missing bbox, and for a page with no real
        geometry at all (a .docx, which is reflowable and never
        rasterized). Clamped to [0, 1]: a detection can legitimately
        overhang a page edge by a pixel, and a consumer drawing a
        highlight should not be handed a negative fraction.
        """
        if bbox is None or self.exact_width_px <= 0 or self.exact_height_px <= 0:
            return None
        x0, y0, x1, y1 = bbox
        return [
            min(max(x0 / self.exact_width_px, 0.0), 1.0),
            min(max(y0 / self.exact_height_px, 0.0), 1.0),
            min(max(x1 / self.exact_width_px, 0.0), 1.0),
            min(max(y1 / self.exact_height_px, 0.0), 1.0),
        ]

    def to_dict(self) -> dict:
        out = {
            "width_pt": round(self.width_pt, 2),
            "height_pt": round(self.height_pt, 2),
            "width_px": self.width_px,
            "height_px": self.height_px,
            "render_scale": round(self.render_scale, 6),
        }
        # Present only when detail was actually discarded, so its absence
        # means "nothing was lost" rather than "nobody checked". A
        # consumer deciding whether a poor read is worth re-scanning, or a
        # support engineer asked why a 600-DPI scan read badly, currently
        # has no way to know the worker downsampled it at all.
        if self.downsampled_from is not None:
            source_w, source_h = self.downsampled_from
            out["downsampled_from"] = {
                "width_px": source_w,
                "height_px": source_h,
                "megapixels": round(source_w * source_h / 1_000_000, 2),
                "scale": round(self.width_px / source_w, 4) if source_w else None,
            }
        return out


def normalize_bbox_for_page(geometry_by_page: dict | None, page: int | None, bbox):
    """Schema v2 normalized bbox for a value found on `page`.

    `geometry_by_page` is keyed by 1-indexed page number, matching
    `ExtractedField.page` / `RequestedFieldResult.page`. A regex match
    over the whole document's concatenated text has no single page and
    therefore no bbox at all, so this returns None for it -- as it does
    for a .docx, which has no page geometry to normalize against.

    It lives here, beside {@link PageGeometry.normalize}, and not next to
    one of its callers, because there are now three of them: the HTTP
    output builders in extraction.py and -- since the review screen's
    highlights were found landing ~830,000 px off the page -- the
    `document_ocr_field_results` / `document_ocr_line_items` writers in
    repository.py. The DB writers used to persist the raw PIXEL bbox into
    a column every consumer reads as a 0..1 fraction
    (`review-page-viewer.tsx` renders `left: ${x0 * 100}%`), so no
    highlight ever appeared and clicking a field scrolled the preview out
    of the document. Two definitions of "normalized for this page" is how
    that happened; one definition is the fix.
    """
    if not geometry_by_page or page is None or bbox is None:
        return None
    geometry = geometry_by_page.get(page)
    if geometry is None:
        return None
    return geometry.normalize(tuple(bbox))


def render_scale_for(dpi: int, width_pt: float, height_pt: float, max_megapixels: float | None = None) -> float:
    """The single zoom factor used for both rasterization and bbox scaling.

    Capped by total rendered AREA, not by the longest side. The thing that
    actually kills this worker is pixels per inference call -- measured at
    ~0.7 GiB of RSS per megapixel, with an OOM kill above ~6 MP at the
    container's 8.5 GiB limit (`scripts/dev/step4_resolution_probe.py`;
    docs/ocr/phase2-verification.md Step 4). Area is what that constraint
    is denominated in, so area is what the cap should be denominated in.

    A longest-side cap spends that budget badly on anything that is not
    roughly A4-shaped. A 2000x500pt landscape page hit the old 1600px
    limit at 1600x400 = 0.64 MP -- a quarter of the budget an A4 page was
    given, for no reason other than its aspect ratio. The same page under
    an area cap renders at 2828x707, four times the pixels, for the same
    memory.

    `max_megapixels=None` means `DEFAULT_RENDER_MAX_MP`, **not** "no cap".
    That distinction is deliberate and was chosen after getting it wrong:
    with None meaning uncapped, a caller that simply forgot to pass the
    budget rendered a 3000x4000pt page at 12500x16667 = 208 MP, which at
    the measured ~0.7 GiB/MP is an instant OOM kill taking every other
    in-flight document in that worker with it. The safe value is the one
    you get by forgetting.
    """
    zoom = dpi / 72.0
    area_pt = width_pt * height_pt
    budget = DEFAULT_RENDER_MAX_MP if max_megapixels is None else max_megapixels
    if budget and area_pt > 0:
        zoom = min(zoom, math.sqrt((budget * 1_000_000) / area_pt))
    return zoom


def native_pixel_size(page, *, raster_source: bool = False) -> tuple[int, int] | None:
    """The largest embedded raster on a PyMuPDF page, in its own pixels.

    This is how "the scanner's real resolution" is recovered. A PDF page
    is vector and has no native pixel size of its own, but a scanned page
    is a PDF wrapper around one big image, and a bare JPEG/TIFF opens as a
    one-page document containing exactly one image. In both cases the
    embedded raster's dimensions are the resolution the source actually
    carries -- which is the number to compare a rendered raster against
    before claiming detail was or was not discarded.

    Returns None for a genuinely vector page (nothing was scanned, so
    nothing can be lost) and on any PyMuPDF error, because this is
    reporting, never correctness.

    **Two different calls, because one of them is catastrophically slow on
    the shape it is not for.** Measured on the real fixtures:

    | source | `get_image_info()` | `get_images()` |
    |---|---|---|
    | 35-page scanned PDF | 2131 ms | **4.5 ms** |
    | 1-page scanned PDF | 1930 ms | **0.4 ms** |
    | bare PNG | **0.4 ms** | returns nothing |
    | bare JPEG | **1.0 ms** | returns nothing |

    `get_image_info()` parses the page's content stream and decodes enough
    of each image to describe it; on a page that *is* one big scan that is
    ~2 seconds. `get_images()` only reads the resource dictionary -- no
    stream parsing -- and on a PDF it returns the identical dimensions
    (verified: both say 1700x2200). But it returns **nothing** for a bare
    JPEG/TIFF, because MuPDF synthesises a page around such a file rather
    than embedding an XObject in one -- and that is every phone photo and
    every single-image scan, the population this function exists for.

    So: PDFs take the cheap resource-dictionary path, bare rasters take
    `get_image_info()`, which is already fast for them. Using
    `get_image_info()` for everything was a real, self-inflicted
    regression -- `page_geometry()` is called at least twice per page, so
    a 35-page scan paid ~4 seconds of pure bookkeeping and one-page
    documents blew their 85s budget under concurrent load.
    """
    if raster_source:
        try:
            infos = page.get_image_info()
        except Exception:  # noqa: BLE001 -- reporting must never fail a document
            return None
        return _largest(
            (info.get("width"), info.get("height"))
            for info in infos or []
            if hasattr(info, "get")
        )

    try:
        images = page.get_images(full=True)
    except Exception:  # noqa: BLE001
        return None
    # (xref, smask, width, height, bpc, colorspace, alt, name, filter, ...)
    return _largest((entry[2], entry[3]) for entry in images or [] if len(entry) >= 4)


def _largest(pairs) -> tuple[int, int] | None:
    best: tuple[int, int] | None = None
    for raw_width, raw_height in pairs:
        try:
            width, height = int(raw_width), int(raw_height)
        except (TypeError, ValueError):
            continue
        if width <= 0 or height <= 0:
            continue
        if best is None or width * height > best[0] * best[1]:
            best = (width, height)
    return best


@dataclass
class PageImage:
    page_no: int
    width_px: int
    height_px: int
    image: "Any"  # PIL.Image.Image, kept untyped to avoid importing PIL at module load
    geometry: PageGeometry | None = None


@dataclass
class NativeTextBlock:
    """One Docling-extracted text item on a page with a real native text
    layer, converted into the same pixel coordinate space (at the given
    render DPI) that PaddleOCR's WordResult blocks use, so pipeline.py can
    treat native_text and paddleocr blocks uniformly in the output JSON.
    """

    text: str
    bbox: tuple[int, int, int, int]


@dataclass
class TableCellBlock:
    """One cell of a Docling-recognized table, in the same pixel
    coordinate space as NativeTextBlock. `row`/`col` are 0-indexed
    offsets into the table's grid; `row_span`/`col_span` > 1 for a
    merged cell. `bbox` is None for a cell Docling reasoned about
    structurally but couldn't anchor to real page geometry (rare, but
    real: e.g. removed spanned cells in some layouts)."""

    text: str
    row: int
    col: int
    row_span: int
    col_span: int
    bbox: tuple[int, int, int, int] | None
    column_header: bool
    row_header: bool


@dataclass
class TableBlock:
    """One Docling-recognized table on a page: real row/column structure
    (requires do_table_structure=True), as opposed to native_text_blocks_
    for_page's flat, ungrouped text lines."""

    bbox: tuple[int, int, int, int]
    num_rows: int
    num_cols: int
    cells: list[TableCellBlock]


class LayoutDocument(Protocol):
    """What the pipeline needs from a loaded document, regardless of which
    library produced it. Docling supplies structure/native-text (page
    geometry, per-page text items with real provenance); PyMuPDF supplies
    the actual page rasterization PaddleOCR needs pixels from. Keeping
    this as a narrow protocol means ocr_engine/pipeline never import
    docling or pymupdf directly, so tests can substitute a fake
    implementation.
    """

    def page_count(self) -> int: ...

    def render_page(self, page_no: int, dpi: int) -> PageImage: ...

    def page_geometry(self, page_no: int, dpi: int) -> PageGeometry | None:
        """0-indexed. None when the document has no fixed page geometry at
        all (a reflowable .docx), in which case its bboxes are placeholders
        and cannot be normalized."""
        ...

    def native_text_blocks_for_page(self, page_no: int, dpi: int) -> list[NativeTextBlock] | None: ...

    def native_text_lines_for_page(self, page_no: int, dpi: int) -> list[NativeTextBlock] | None: ...

    def tables_for_page(self, page_no: int, dpi: int) -> list[TableBlock] | None: ...

    def image_regions_for_page(self, page_no: int, dpi: int) -> list[tuple[int, int, int, int]]:
        """0-indexed. Where the rasters are on this page, in the same
        pixel space as every other bbox. Empty for a document with no
        page geometry at all. Used by per-page routing to tell a typed
        page from a scan pasted under a typed header."""
        return []


class DoclingLayoutDocument:
    """Loads a document via Docling for page geometry and per-page native
    text extraction, and rasterizes pages via PyMuPDF for the actual
    pixels handed to PaddleOCR when a page has no usable text layer.

    Docling's own OCR backends are disabled (do_ocr=False) -- PaddleOCR is
    the sole character-recognition engine in this system. Docling's job
    here is: (1) tell us, per page, whether a real native text layer
    already exists (native PDF text -- skip OCR entirely per the
    page-level decision logic), (2) page geometry for zone/rubber-band
    coordinate mapping, and (3) real table structure (rows/columns/cells)
    for any table on such a page, via tables_for_page. Verified against
    the real installed docling-core: `DoclingDocument.texts` is empty for
    a page with no text layer (an image-only PDF page or a raw scanned
    image) and contains real TextItem entries with page-anchored
    provenance (page_no, bbox in PDF-point BOTTOMLEFT-origin units) for a
    page that does have one.

    No broad try/except around Docling's convert() call here, deliberately:
    a Docling failure (bad model load, unsupported file, network failure
    fetching a model) must propagate to the caller as a real exception, so
    pipeline.py's existing failure handling records the real error in
    processing_jobs.last_error and sets ocr_status='failed' -- never a
    silent "treat this as image-only" fallback that would hide a broken
    Docling installation behind an apparently-successful OCR run.
    """

    def __init__(self, file_bytes: bytes, mime_type: str, original_filename: str, docling_converter, on_page_count=None, max_megapixels: float | None = None):
        """`on_page_count`, if given, is called with the real page count
        the instant PyMuPDF has opened the file and BEFORE Docling's
        conversion runs -- see `LayoutEngine.load`'s own docstring for why
        that ordering is load-bearing rather than cosmetic. It may raise
        (`TooManyPagesError`); this constructor cleans up either way.
        """
        import fitz  # PyMuPDF

        # The rendered-area budget (OCR_RENDER_MAX_MP), carried per
        # document rather than read from a module global so a test -- and
        # a future per-tenant override -- can set it without mutating
        # process state every other document shares.
        self._max_megapixels = max_megapixels
        # A bare JPEG/PNG/TIFF rather than a PDF. Decides which of the two
        # native-size calls is cheap for this document -- see
        # `native_pixel_size`, where picking the wrong one costs seconds
        # per page.
        self._raster_source = mime_type != "application/pdf"

        # fitz/MuPDF (verified against the installed 1.28.2 build) cannot
        # open WEBP or HEIC/HEIF directly -- a real FileDataError every
        # time, confirmed live. Decode via Pillow first and re-encode as
        # PNG, which both fitz and Docling read natively; everything below
        # then treats the input uniformly regardless of original format.
        if mime_type in _PIL_PRECONVERT_MIME_TYPES:
            file_bytes = _decode_via_pil_to_png(file_bytes, mime_type)
            self._suffix = ".png"
        else:
            self._suffix = Path(original_filename).suffix or _suffix_for_mime(mime_type)

        self._tmp = tempfile.NamedTemporaryFile(suffix=self._suffix, delete=False)
        self._tmp.write(file_bytes)
        self._tmp.flush()
        self._doc = None
        # Lazily-built PDF wrapper for a non-PDF source; see source_document.
        self._source_pdf = None

        try:
            self._open(file_bytes, mime_type, original_filename, docling_converter, on_page_count)
        except BaseException:
            # Nothing here owns a `with` block yet -- the caller only gets a
            # context manager once __init__ returns -- so a raise from the
            # page-count hook (or from Docling) would otherwise leak both
            # the temp file and the open MuPDF handle.
            self.close()
            raise

    def _open(self, file_bytes, mime_type, original_filename, docling_converter, on_page_count) -> None:
        import fitz  # PyMuPDF

        # PyMuPDF opens PDF and single-page raster formats (PNG/JPEG/TIFF/
        # BMP/GIF, verified live -- see _PIL_PRECONVERT_MIME_TYPES's own
        # comment for the three that need PIL first) through the same
        # call, so render_page can treat every input uniformly as N pages
        # regardless of source format.
        #
        # fitz.TOOLS.mupdf_warnings() is reset first so the check right
        # after open() reflects only THIS file's own open -- MuPDF's
        # warning log is process-global, not per-call.
        fitz.TOOLS.reset_mupdf_warnings()
        try:
            self._doc = fitz.open(self._tmp.name)
        except Exception as exc:
            # Real finding, live: three different corruption techniques
            # (50% truncation, trailer-region byte-flipping, magic-header
            # replacement) were all silently repaired/tolerated by
            # MuPDF's own lenient parser -- only a much more severe
            # truncation (no valid xref/trailer structure at all) ever
            # raised an exception here. This is genuinely the "can't be
            # opened at all" case CorruptFileError is reserved for; a
            # file that opens but got repaired is `file_repaired` below,
            # not this.
            raise CorruptFileError(f"{original_filename}: {exc}") from exc

        if self._doc.needs_pass:
            # Never attempt to guess a password -- a definite, permanent
            # failure for this pipeline, not a retryable one.
            raise EncryptedPdfError(f"{original_filename} is password-protected")
        if self._doc.page_count == 0:
            raise CorruptFileError(f"{original_filename} has zero pages")

        # True when MuPDF's own repair kicked in to open this file at all
        # (e.g. the 50%-truncation case above) -- surfaced as
        # flags.file_repaired/requires_review on an otherwise-successful
        # result (pipeline.py's _finalize_success), never silently trusted
        # like a genuinely intact file.
        self.file_repaired = "repair" in fitz.TOOLS.mupdf_warnings().lower()

        # The earliest point at which the real page count is known, and
        # deliberately BEFORE the Docling conversion below. Both of this
        # hook's callers depend on that ordering:
        #
        #  - the `ocr_max_pages` guard. errors.py has always described
        #    TooManyPagesError as "checked right after page_count(),
        #    before any rendering/Docling work, so an oversized document
        #    never even starts consuming a worker slot" -- which was not
        #    true while the check lived in pipeline.py, after `load()` had
        #    returned. Measured live during Phase 1 close-out: a 35-page
        #    scanned PDF spent ~104s inside `docling_converter.convert`
        #    before anything looked at its page count, every attempt.
        #  - the per-document deadline (deadline.py). Arming it after the
        #    conversion would leave the single most expensive step of the
        #    whole pipeline outside the budget it is supposed to bound.
        if on_page_count is not None:
            on_page_count(self._doc.page_count)

        # A plain raster image can never have a native text layer -- with
        # do_ocr=False (see LayoutEngine._converter's own comment),
        # Docling's own OCR is disabled, so an image-only input gives
        # TableFormer no text to structure either (its own tables_for_page
        # docstring already says as much). native_text_blocks_for_page/
        # tables_for_page both return None as soon as
        # self._docling_document.pages.get(page_no) is None -- always true
        # for an image, since Docling never gets a chance to populate a
        # page entry -- so skipping the conversion call entirely for a
        # bare image is a no-op for every caller. Verified this matters:
        # real crash found live, running Docling's layout (object-
        # detection transformer) + table-structure models AND PaddleOCR's
        # det/rec/textline models all in the same process for one photo
        # upload peaked at 6-7GB RSS (`docker stats` polled every second
        # through a real run) and OOM-killed the worker mid-request --
        # skipping two whole models' worth of load+inference for a result
        # nothing downstream ever reads removes that memory pressure
        # outright rather than just trimming it.
        if self._doc.is_pdf:
            result = docling_converter.convert(self._tmp.name)
            self._docling_document = result.document
        else:
            self._docling_document = SimpleNamespace(pages={})

    def page_count(self) -> int:
        return self._doc.page_count

    def render_page(self, page_no: int, dpi: int) -> PageImage:
        """0-indexed page_no, matching PyMuPDF's own convention.

        Caps the rendered pixel size regardless of dpi or source format.
        For a real PDF page (sized in 72-dpi points, e.g. 612x792 for
        Letter), dpi/72 upscaling is exactly what OCR needs and normal
        documents stay well under the cap. But PyMuPDF opens a raster
        image (JPEG/PNG/TIFF) as a one-page "document" whose point-size
        already equals its native pixel size -- so the same dpi/72 zoom
        upscales an already-full-resolution photo on top of itself.
        Verified against a real crash: a completely ordinary ~2300x3000px
        (6.8MP) phone photo got rendered out to 6350x8305 (~53MP) at the
        default 200 DPI, which OOM-killed the worker process mid-PaddleOCR
        (docker-compose.yml's ocr-worker restart-policy comment documents
        an earlier instance of this same failure mode).

        The cap is on rendered AREA (`OCR_RENDER_MAX_MP`), not on the
        longest side as it was until Phase 2 Step 4 -- see
        `render_scale_for` for why, and docs/ocr/phase2-verification.md
        Step 4 for the measurements the ceiling comes from (~0.7 GiB RSS
        per megapixel; an OOM kill above ~6 MP per inference call at the
        container's 8.5 GiB limit, with one process, where production runs
        two).

        When the cap bites on a page that carries a real raster, this logs
        it and records `downsampled_from` on the geometry. Discarding
        scanner pixels is a legitimate trade, but doing it silently is
        not: a 600-DPI scan that reads badly is indistinguishable, from
        the outside, from a bad scan -- unless the output says the worker
        threw most of it away.
        """
        from PIL import Image

        geometry = self.page_geometry(page_no, dpi)
        page = self._doc.load_page(page_no)
        pixmap = page.get_pixmap(matrix=_matrix(geometry.render_scale))
        image = Image.frombytes("RGB", (pixmap.width, pixmap.height), pixmap.samples)
        if geometry.downsampled_from is not None:
            source_w, source_h = geometry.downsampled_from
            logger.info(
                "page %d downsampled from its source raster: %dx%d (%.1f MP) -> %dx%d (%.1f MP), "
                "scale %.3f, budget OCR_RENDER_MAX_MP=%s",
                page_no + 1,
                source_w, source_h, source_w * source_h / 1e6,
                pixmap.width, pixmap.height, pixmap.width * pixmap.height / 1e6,
                pixmap.width / source_w if source_w else 0.0,
                self._max_megapixels,
            )
        return PageImage(
            page_no=page_no,
            width_px=pixmap.width,
            height_px=pixmap.height,
            image=image,
            geometry=geometry,
        )

    def page_geometry(self, page_no: int, dpi: int) -> PageGeometry:
        """0-indexed page_no. The single source of truth for this page's
        point size and its point->pixel scale (see `PageGeometry`)."""
        page = self._doc.load_page(page_no)
        width_pt = float(page.rect.width)
        height_pt = float(page.rect.height)
        scale = render_scale_for(dpi, width_pt, height_pt, self._max_megapixels)
        # The pixmap's OWN dimensions, asked of PyMuPDF rather than
        # recomputed. `round(width_pt * scale)` disagreed with the real
        # pixmap by a pixel on an ordinary A4 page (1131 against 1132,
        # 1600 against 1601) because PyMuPDF takes the integer rect of the
        # transformed page rather than rounding each side. Every
        # `bbox_norm` divides by these numbers while every bbox is
        # measured in the pixmap's space, so the two must be the same
        # space or a box on the far edge normalizes past 1.0. Costs
        # nothing: no rendering happens, only a rect transform.
        width_px, height_px = _pixmap_size(page, scale)

        # Only a page that carries a real raster can lose detail, and only
        # when we rendered it smaller than that raster actually is. A
        # vector page has nothing to lose; a small image rendered at or
        # above its own size has lost nothing either.
        #
        # The embedded raster's dimensions are in the IMAGE's own space,
        # which need not share the page's orientation. A real scan in the
        # corpus (SCAN0002) is a landscape 2338x1653 image on a page that
        # renders portrait, so comparing the two unswapped compared a
        # width against a height and reported `downsampled_from` as
        # (2338, 1653) for a 1132x1601 render -- reporting that actively
        # misleads, since the field exists to say what resolution was
        # discarded.
        #
        # Keyed on the two aspects disagreeing rather than on
        # `page.rotation`, which is NOT available here: `/Rotate` is
        # normalized away during load, so that page arrives reporting
        # rotation 0 with an already-portrait mediabox. A rotation-based
        # swap would silently never fire -- it was written that way first
        # and measured as a no-op.
        native = native_pixel_size(page, raster_source=self._raster_source)
        if native is not None:
            native_is_landscape = native[0] > native[1]
            page_is_landscape = width_px > height_px
            if native_is_landscape != page_is_landscape:
                native = (native[1], native[0])
        downsampled_from = None
        if native is not None and (native[0] > width_px or native[1] > height_px):
            downsampled_from = native

        return PageGeometry(
            page_no=page_no,
            width_pt=width_pt,
            height_pt=height_pt,
            render_scale=scale,
            width_px=width_px,
            height_px=height_px,
            downsampled_from=downsampled_from,
        )

    def image_regions_for_page(self, page_no: int, dpi: int) -> list[tuple[int, int, int, int]]:
        """0-indexed. Every raster drawn on this page, as pixel boxes in
        the page's own render space (see `PageGeometry`), so routing can
        compare them against native-text boxes without a second scale.

        PyMuPDF reports each placement's `bbox` in points; a single image
        drawn twice appears twice, which is correct here -- routing cares
        about area on the page, not about how many distinct XObjects
        produced it.
        """
        try:
            page = self._doc.load_page(page_no)
            infos = page.get_image_info()
        except Exception:  # noqa: BLE001 -- routing must never fail a document
            return []
        geometry = self.page_geometry(page_no, dpi)
        scale = geometry.render_scale
        regions: list[tuple[int, int, int, int]] = []
        for info in infos or []:
            bbox = (info or {}).get("bbox") if hasattr(info, "get") else None
            if not bbox or len(bbox) != 4:
                continue
            x0, y0, x1, y1 = (float(v) * scale for v in bbox)
            if x1 <= x0 or y1 <= y0:
                continue
            regions.append((round(x0), round(y0), round(x1), round(y1)))
        return regions

    def source_document(self):
        """A **PDF-backed** PyMuPDF document, for callers that need to
        COPY a page rather than rasterize one.

        Exists for the searchable PDF rendition (production prompt 3.11),
        which must preserve the source at its own resolution: a user who
        uploads a 300 DPI scan must not get back a copy downsampled to
        `OCR_RENDER_MAX_MP`. Copying the page also keeps vector content
        sharp and, on a native-text PDF, keeps the page's OWN text layer
        instead of overlaying a recognised approximation of it.

        A bare image (PNG/JPEG/TIFF/HEIC) opens as an *image* document,
        NOT a PDF -- and `show_pdf_page` rejects one with "is no PDF".
        Assuming otherwise cost every image upload its rendition
        silently, which is most real uploads. `convert_to_pdf()` wraps the
        image in a real PDF page at its own native size, so the copy path
        is uniform across formats without ever re-encoding the pixels.

        Converted once and cached: a multi-page TIFF must not be
        re-converted per page.

        **Read-only.** The caller must not mutate or close it; its
        lifetime belongs to this object.
        """
        if self._doc is None:
            return None
        if self._doc.is_pdf:
            return self._doc
        if self._source_pdf is None:
            import fitz  # PyMuPDF

            self._source_pdf = fitz.open("pdf", self._doc.convert_to_pdf())
        return self._source_pdf

    def native_pixel_size_for_page(self, page_no: int) -> tuple[int, int] | None:
        """The source raster's own dimensions for this page, or None for a
        genuinely vector page.

        Exists for quality assessment (production prompt 3.3), which needs
        the resolution the document actually *carries* rather than the one
        we chose to render at. Those differ whenever `OCR_RENDER_MAX_MP`
        caps the render -- and reporting the capped render's DPI would
        tell a user to re-scan a page whose scan is already excellent,
        which is the opposite of useful.
        """
        try:
            page = self._doc.load_page(page_no)
        except Exception:  # noqa: BLE001 -- reporting must never fail a document
            return None
        return native_pixel_size(page, raster_source=self._raster_source)

    def native_text_blocks_for_page(self, page_no: int, dpi: int) -> list[NativeTextBlock] | None:
        """1-indexed page_no, matching Docling's own numbering (the whole
        rest of this class is 0-indexed to match PyMuPDF -- callers add 1
        when crossing into this method). Returns None when Docling found
        no native text layer for this page, meaning callers must run
        PaddleOCR on the rendered raster instead.

        With do_table_structure=True (see _converter), text that Docling
        recognized as part of a table is NOT included here -- it lives in
        tables_for_page instead. Verified against the real installed
        docling-core: document.texts holds only non-table paragraph text
        once table-structure recognition is on.
        """
        page_info = self._docling_document.pages.get(page_no)
        if page_info is None:
            return None

        page_height_pt = page_info.size.height
        # The *capped* scale, matching render_page. This used to be a bare
        # `dpi / 72.0`, which put these bboxes in a different (larger)
        # pixel space than the rendered page they are supposed to describe
        # -- see PageGeometry's docstring.
        scale = render_scale_for(dpi, page_info.size.width, page_height_pt, self._max_megapixels)
        blocks: list[NativeTextBlock] = []
        for item in self._docling_document.texts:
            for prov in item.prov:
                if prov.page_no != page_no:
                    continue
                x0, y0, x1, y1 = _bbox_to_pixels(prov.bbox, page_height_pt, scale)
                blocks.append(NativeTextBlock(text=item.text, bbox=(x0, y0, x1, y1)))
        return blocks or None

    def native_text_lines_for_page(self, page_no: int, dpi: int) -> list[NativeTextBlock] | None:
        """0-indexed page_no, matching PyMuPDF's own convention (unlike
        native_text_blocks_for_page's 1-indexed Docling one) -- this reads
        PyMuPDF's own text dict directly rather than going through Docling,
        specifically for selectable-OCR-field spatial matching (see
        extraction.py's _spatial_label_value). Docling's own
        native_text_blocks_for_page groups multiple visual rows of a
        borderless "form fields" region into ONE merged paragraph TextItem
        -- verified live against a real invoice: a 4-row label/value header
        ("Invoice Number: Invoice Date: PO Number: Currency: ...") came
        back as a single TextItem spanning ~300px vertically, discarding
        exactly the per-row/per-column positions spatial matching needs.
        PyMuPDF's own "dict" extraction keeps each real visual line as its
        own entry with its own bbox, which is what this returns instead.

        Same coordinate space as render_page (PyMuPDF's own top-left-origin
        page-point space, scaled by dpi/72) -- no BOTTOMLEFT/TOPLEFT
        ambiguity to resolve here, unlike Docling's bboxes (_bbox_to_pixels).
        """
        page = self._doc.load_page(page_no)
        zoom = render_scale_for(dpi, page.rect.width, page.rect.height, self._max_megapixels)
        lines: list[NativeTextBlock] = []
        for block in page.get_text("dict")["blocks"]:
            for line in block.get("lines", []):
                text = "".join(span["text"] for span in line["spans"]).strip()
                if not text:
                    continue
                x0, y0, x1, y1 = line["bbox"]
                lines.append(NativeTextBlock(
                    text=text,
                    bbox=(round(x0 * zoom), round(y0 * zoom), round(x1 * zoom), round(y1 * zoom)),
                ))
        return lines or None

    def tables_for_page(self, page_no: int, dpi: int) -> list[TableBlock] | None:
        """1-indexed page_no. Returns None when Docling found no tables on
        this page (either there genuinely isn't one, or the page has no
        native text layer at all -- do_ocr=False means an image-only page
        never has text for TableFormer to structure, so this is only ever
        useful alongside native_text_blocks_for_page).

        Verified against the real installed docling-core: unlike
        TextItem.prov.bbox (always PDF-point BOTTOMLEFT), a TableCell's
        own .bbox comes back in TOPLEFT origin -- _bbox_to_pixels handles
        both by checking each bbox's own coord_origin rather than
        assuming one.
        """
        page_info = self._docling_document.pages.get(page_no)
        if page_info is None:
            return None

        page_height_pt = page_info.size.height
        # Capped scale, matching render_page -- see PageGeometry.
        scale = render_scale_for(dpi, page_info.size.width, page_height_pt, self._max_megapixels)
        tables: list[TableBlock] = []
        for table in self._docling_document.tables:
            table_provs = [p for p in table.prov if p.page_no == page_no]
            if not table_provs:
                continue
            table_bbox = _bbox_to_pixels(table_provs[0].bbox, page_height_pt, scale)
            cells = [
                TableCellBlock(
                    text=cell.text,
                    row=cell.start_row_offset_idx,
                    col=cell.start_col_offset_idx,
                    row_span=cell.row_span,
                    col_span=cell.col_span,
                    bbox=_bbox_to_pixels(cell.bbox, page_height_pt, scale) if cell.bbox else None,
                    column_header=cell.column_header,
                    row_header=cell.row_header,
                )
                for cell in table.data.table_cells
            ]
            tables.append(TableBlock(bbox=table_bbox, num_rows=table.data.num_rows, num_cols=table.data.num_cols, cells=cells))
        return tables or None

    def close(self) -> None:
        if self._source_pdf is not None:
            # A converted wrapper around a non-PDF source (see
            # source_document); closed before the document it came from.
            self._source_pdf.close()
            self._source_pdf = None
        if self._doc is not None:
            self._doc.close()
            self._doc = None
        Path(self._tmp.name).unlink(missing_ok=True)

    def __enter__(self) -> "DoclingLayoutDocument":
        return self

    def __exit__(self, *exc: object) -> None:
        self.close()


class DoclingTextDocument:
    """Loads a document that already has real, structured text and needs
    no OCR at all -- e.g. a native .docx -- via Docling's own docx reader.

    Unlike DoclingLayoutDocument, there's no PyMuPDF page rasterization at
    all (nothing to OCR -- fitz can't even open a .docx) and no fixed
    page/pixel geometry (a Word document reflows; it doesn't paginate the
    way a rendered PDF page does). So this always reports itself as
    exactly one page, and never supports render_page or
    native_text_lines_for_page/tables_for_page (no per-line bbox data
    exists to give them) -- the pipeline only ever reaches those when
    native_text_blocks_for_page returns None, which this class never
    does, so a real .docx always takes the "native_text" path, never
    PaddleOCR.

    export_to_text() (rather than joining .texts item-by-item) is used
    deliberately: verified against a real policy document that it renders
    Docling's own table-structure recognition as readable pipe-delimited
    rows (e.g. "DOCUMENT REF | ISMS-DOC-A08-28-1"), which the existing
    flattened-text label-proximity matcher can match against directly --
    exactly the fallback tier this was designed for (see
    _label_proximity_value), with no bbox/spatial data needed.
    """

    def __init__(self, file_bytes: bytes, mime_type: str, original_filename: str, docling_converter, on_page_count=None, max_megapixels: float | None = None):
        # Unused here -- a .docx is reflowable and never rasterized (see
        # render_page below) -- but accepted so both document classes
        # satisfy the same construction contract from LayoutEngine.load.
        self._max_megapixels = max_megapixels
        # A bare JPEG/PNG/TIFF rather than a PDF. Decides which of the two
        # native-size calls is cheap for this document -- see
        # `native_pixel_size`, where picking the wrong one costs seconds
        # per page.
        self._raster_source = mime_type != "application/pdf"
        self._suffix = Path(original_filename).suffix or _suffix_for_mime(mime_type)
        self._tmp = tempfile.NamedTemporaryFile(suffix=self._suffix, delete=False)
        self._tmp.write(file_bytes)
        self._tmp.flush()

        # Always 1 (see page_count below -- a reflowable .docx has no fixed
        # pagination), so unlike DoclingLayoutDocument this needs nothing
        # from the file to answer it, and the hook can run before the
        # conversion rather than merely alongside it.
        if on_page_count is not None:
            try:
                on_page_count(self.page_count())
            except BaseException:
                self.close()
                raise

        result = docling_converter.convert(self._tmp.name)
        self._text = result.document.export_to_text()

    def page_count(self) -> int:
        return 1

    def render_page(self, page_no: int, dpi: int) -> PageImage:
        raise NotImplementedError(
            "DoclingTextDocument has no page rasterization -- native_text_blocks_for_page "
            "always returns real text for a .docx, so the pipeline should never reach this."
        )

    def page_geometry(self, page_no: int, dpi: int) -> PageGeometry | None:
        """Always None: a .docx reflows and has no page geometry.

        Its one block carries a (0,0,0,0) placeholder bbox, so there is
        nothing meaningful to normalize. Returning None makes the output
        say so -- `bbox_norm: null` and null page dimensions -- rather
        than emitting a normalized zero box that a consumer would draw as
        a real highlight in the page's top-left corner.
        """
        return None

    def native_text_blocks_for_page(self, page_no: int, dpi: int) -> list[NativeTextBlock] | None:
        if page_no != 1 or not self._text.strip():
            return None
        # One block for the whole document rather than one per paragraph --
        # bbox is a placeholder (0,0,0,0), never a real pixel position,
        # since nothing here was ever rasterized.
        return [NativeTextBlock(text=self._text, bbox=(0, 0, 0, 0))]

    def native_text_lines_for_page(self, page_no: int, dpi: int) -> list[NativeTextBlock] | None:
        return None

    def tables_for_page(self, page_no: int, dpi: int) -> list[TableBlock] | None:
        return None

    def close(self) -> None:
        Path(self._tmp.name).unlink(missing_ok=True)

    def __enter__(self) -> "DoclingTextDocument":
        return self

    def __exit__(self, *exc: object) -> None:
        self.close()


def _bbox_to_pixels(b, page_height_pt: float, scale: float) -> tuple[int, int, int, int]:
    """Docling bboxes come back in PDF-point units but NOT a single
    consistent origin -- verified against the real installed docling-core:
    TextItem.prov.bbox is BOTTOMLEFT (y increases upward) while a
    TableCell's own .bbox is TOPLEFT, both on the same page. Checking each
    bbox's own coord_origin (rather than assuming one based on which
    Docling collection it came from) avoids a silent, hard-to-notice
    vertical flip if a future docling-core version changes either one.
    """
    x0 = round(b.l * scale)
    x1 = round(b.r * scale)
    if b.coord_origin.name == "BOTTOMLEFT":
        y0 = round((page_height_pt - b.t) * scale)
        y1 = round((page_height_pt - b.b) * scale)
    else:
        y0 = round(b.t * scale)
        y1 = round(b.b * scale)
    return (x0, y0, x1, y1)


def _matrix(zoom: float):
    import fitz

    return fitz.Matrix(zoom, zoom)


def _pixmap_size(page, zoom: float) -> tuple[int, int]:
    """The size `page.get_pixmap(matrix=_matrix(zoom))` will produce.

    Derived from the same transform the rasterizer uses, so `PageGeometry`
    and the pixmap can never disagree about the page's pixel size. Falls
    back to the old arithmetic if PyMuPDF cannot be asked, because page
    geometry is needed even on a page nothing will rasterize.
    """
    try:
        rect = page.rect * _matrix(zoom)
        irect = rect.irect
        return int(irect.width), int(irect.height)
    except Exception:  # noqa: BLE001 -- geometry must never fail a document
        return round(page.rect.width * zoom), round(page.rect.height * zoom)


# See render_page's own docstring: matches PaddleX's internal max_side_limit.
_MAX_RENDER_DIMENSION_PX = 1600


_DOCX_MIME_TYPE = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

# New formats added this phase (docs/ocr/ocr-production-prompt.md section
# 3.2, minus LibreOffice-dependent legacy formats -- see the Phase 1 plan's
# Decision 3). BMP and GIF (first frame only -- verified live: fitz already
# renders a multi-frame animated GIF as a single page) open directly via
# fitz/MuPDF, same as the original PDF/TIFF/JPEG/PNG set.
_BMP_MIME_TYPE = "image/bmp"
_GIF_MIME_TYPE = "image/gif"
_WEBP_MIME_TYPE = "image/webp"
_HEIC_MIME_TYPE = "image/heic"
_HEIF_MIME_TYPE = "image/heif"

# Verified live against the installed fitz 1.28.2: WEBP and HEIC/HEIF both
# raise a real FileDataError from fitz.open -- MuPDF doesn't decode either
# format in this build. Both need the PIL-decode-then-PNG-re-encode
# fallback (_decode_via_pil_to_png) instead of a direct fitz.open.
_PIL_PRECONVERT_MIME_TYPES = {_WEBP_MIME_TYPE, _HEIC_MIME_TYPE, _HEIF_MIME_TYPE}


def _decode_via_pil_to_png(file_bytes: bytes, mime_type: str) -> bytes:
    """Decodes a format fitz/MuPDF can't open directly (see
    _PIL_PRECONVERT_MIME_TYPES) via Pillow and re-encodes as PNG, which
    both fitz and Docling read natively. `pillow-heif` registers itself as
    a real PIL plugin for HEIC/HEIF -- once registered, a plain
    `PIL.Image.open` handles all three formats in this set uniformly, no
    format-specific decode call needed.

    EXIF orientation (production prompt 3.2, "apply EXIF orientation for
    camera images before anything else") is the one thing this path has to
    do for itself. Everywhere else fitz/MuPDF applies it during
    rasterization -- verified live for all four of Orientation 1/3/6/8, by
    where a marked corner lands, not merely by whether the page
    dimensions swapped. But this path re-encodes to PNG first, and PNG
    carries no orientation tag, so whatever is not applied here is lost
    for good. Verified live: a WEBP written with Orientation=6 opens as
    400x200 with tag 274 still set to 6 -- PIL leaves it un-applied, and
    the page would have reached OCR sideways. (HEIC does not need this --
    pillow-heif applies the rotation on open and resets the tag -- but
    `exif_transpose` is a no-op on an already-upright image, so this
    stays uniform across the set rather than carrying a per-format
    exception.)
    """
    import io

    from PIL import Image, ImageOps

    if mime_type in (_HEIC_MIME_TYPE, _HEIF_MIME_TYPE):
        import pillow_heif

        pillow_heif.register_heif_opener()

    image = ImageOps.exif_transpose(Image.open(io.BytesIO(file_bytes)))
    out = io.BytesIO()
    image.convert("RGB").save(out, format="PNG")
    return out.getvalue()


def _suffix_for_mime(mime_type: str) -> str:
    return {
        "application/pdf": ".pdf",
        "image/tiff": ".tiff",
        "image/jpeg": ".jpg",
        "image/png": ".png",
        _BMP_MIME_TYPE: ".bmp",
        _GIF_MIME_TYPE: ".gif",
        _DOCX_MIME_TYPE: ".docx",
    }.get(mime_type, ".bin")


# Pages (0-based) whose table-structure pass was abandoned on the deadline,
# for the document currently being converted. Reset at the start of every
# `LayoutEngine.load`.
#
# Module-level rather than passed through, because the only hook Docling
# offers is a method on its own model class -- there is no per-conversion
# context to thread a collector through. Safe here for one specific
# reason: a pool worker converts exactly one document at a time (see
# worker_pool.py), and pool workers are separate *processes*, so no two
# documents ever share this module.
_TABLE_TIMEOUT_PAGES: set[int] = set()
_TABLE_BOUND_INSTALLED = False


def _install_table_structure_bound(timeout_s: float) -> None:
    """Bound Docling's table-structure pass to `timeout_s` per page.

    Why this exists: TableFormer's cost is driven by the grid it predicts,
    and that prediction is unbounded and occasionally absurd. Measured, on
    a one-page PDF carrying a `/Rotate 90`: a **378x2** grid on a page with
    24 cells, 87 seconds, and every one of those 24 cells then dropped for
    matching no row or column band. The same page upright takes 1.2s. See
    docs/ocr/phase2-verification.md Step 7.

    That specific case is worth fixing at source (`/Rotate` normalisation,
    below), but the general shape is not: nothing in Docling's API bounds
    what TableFormer will predict, and a landscape page with a genuinely
    complex table could hit the same wall without any rotation involved.
    This is the safety net for the class of problem, not the instance.

    **What the bound can and cannot do.** Per page, the prediction runs on
    a worker thread and is abandoned if it overruns. Abandoned is the
    honest word: a thread running native torch code cannot be killed, so
    it keeps burning CPU until it finishes on its own. What this buys is
    that the *document* stops waiting for it -- the page keeps all its
    text and loses only table structure, and the remaining pages proceed.
    Bounding the process, rather than the document, is the pool worker's
    hard-kill ceiling, which is a separate mechanism and still applies.

    Patched onto `BaseTableStructureModel`, which is where `__call__` is
    actually defined -- `TableStructureModel`, `TableStructureModelV2` and
    the granite variant all inherit it, so one patch covers whichever the
    factory picks.
    """
    global _TABLE_BOUND_INSTALLED
    if _TABLE_BOUND_INSTALLED or timeout_s <= 0:
        return

    from docling.models.stages.table_structure.table_structure_model import (
        BaseTableStructureModel,
    )

    original_call = BaseTableStructureModel.__call__

    def bounded_call(self, conv_res, page_batch):
        if not getattr(self, "enabled", True):
            yield from page_batch
            return

        for page in page_batch:
            page_no = getattr(page, "page_no", -1)
            # A FRESH executor per page, deliberately. Sharing one with
            # max_workers=1 across the batch means an abandoned prediction
            # still owns the only worker thread, so the next page queues
            # behind the very work we just refused to wait for and times
            # out too -- one bad page silently costing every page after
            # it. Caught by
            # `test_one_slow_page_does_not_cost_the_others`. A thread per
            # page is nothing against a table prediction.
            executor = ThreadPoolExecutor(max_workers=1, thread_name_prefix="tableformer")
            try:
                future = executor.submit(self.predict_tables, conv_res, [page])
                try:
                    predictions = future.result(timeout=timeout_s)
                except FuturesTimeoutError:
                    _TABLE_TIMEOUT_PAGES.add(page_no)
                    logger.warning(
                        "Table-structure prediction for page %d exceeded %.1fs and was "
                        "abandoned; the page keeps its text and loses only table structure. "
                        "The abandoned prediction continues on its own thread until it ends.",
                        page_no + 1, timeout_s,
                    )
                    yield page
                    continue
                except Exception:  # noqa: BLE001 -- a table is additive, never worth failing a page
                    _TABLE_TIMEOUT_PAGES.add(page_no)
                    logger.exception(
                        "Table-structure prediction failed for page %d; continuing without tables",
                        page_no + 1,
                    )
                    yield page
                    continue
                for prediction in predictions:
                    page.predictions.tablestructure = prediction
                yield page
            finally:
                # Not `shutdown(wait=True)`: an abandoned prediction is
                # exactly the thread we are refusing to wait for, and
                # blocking here would hand back the timeout we just
                # bought.
                executor.shutdown(wait=False)

    bounded_call.__wrapped__ = original_call
    BaseTableStructureModel.__call__ = bounded_call
    _TABLE_BOUND_INSTALLED = True


def normalize_page_rotation(pdf_bytes: bytes) -> tuple[bytes, int]:
    """Bake every page's `/Rotate` into its content. Returns the rewritten
    PDF and how many pages changed; the input is returned unchanged when
    no page carries a rotation, which is the overwhelmingly common case.

    **`set_rotation(0)` is not this.** That clears the attribute without
    touching the content stream, which leaves the text physically sideways
    and simply stops the viewer compensating -- the opposite of what is
    wanted. The rotation has to be applied to the content, which means
    drawing each page onto a new page whose dimensions are the *displayed*
    ones. `show_pdf_page` honours the source page's `/Rotate` when it
    draws, so the result is an upright page with `/Rotate 0`.

    Why bother: PyMuPDF already applies `/Rotate` for our own rasterizing
    and text extraction, so this changes nothing we read. It changes what
    *Docling* is handed, and Docling's layout model behaves very
    differently on a page stored sideways -- 87s and a hallucinated 378x2
    grid, against 1.2s upright (docs/ocr/phase2-verification.md Step 7).

    The coordinate invariant `test_coordinates.py` guards is preserved
    because normalisation happens once, before anything reads the
    document: native-text boxes, the render, and OCR boxes are all then
    derived from the same upright page. Normalising *after* any of them
    had been taken would be the thing that breaks it.
    """
    import fitz  # PyMuPDF

    try:
        doc = fitz.open(stream=pdf_bytes, filetype="pdf")
    except Exception:  # noqa: BLE001 -- see below; never fail a document here
        # **This must not raise, and getting that wrong was a real
        # regression.** This runs BEFORE `DoclingLayoutDocument.__init__`,
        # which is where a PDF that cannot be opened becomes a classified
        # `CORRUPT_FILE` or `ENCRYPTED_PDF`. When this function raised
        # PyMuPDF's own error instead, the taxonomy was bypassed and both
        # fixtures came back as a generic `ENGINE_ERROR` -- which also
        # silently changed their retry budget from 0 to 3, so a
        # password-protected file would be retried four times.
        #
        # Normalisation is an optimisation for Docling's benefit. If the
        # file cannot be read, hand the original bytes on unchanged and
        # let the real classifier downstream say what is wrong with it.
        logger.debug("Could not open PDF for rotation normalisation; passing bytes through")
        return pdf_bytes, 0

    try:
        try:
            rotated = any(page.rotation for page in doc)
        except Exception:  # noqa: BLE001 -- an encrypted doc raises on page access
            logger.debug("Could not read page rotation; passing bytes through")
            return pdf_bytes, 0
        if not rotated:
            return pdf_bytes, 0

        out = fitz.open()
        try:
            changed = 0
            for page in doc:
                # page.rect is the DISPLAYED box: for /Rotate 90 on a
                # 612x792 page it is 792x612, which is the size the new
                # page must be for the drawn content to fit.
                rect = page.rect
                new_page = out.new_page(width=rect.width, height=rect.height)
                new_page.show_pdf_page(new_page.rect, doc, page.number)
                if page.rotation:
                    changed += 1
            return out.tobytes(), changed
        except Exception:  # noqa: BLE001 -- same rule as the open above
            # A PDF that opens but will not re-render (damaged content
            # stream, an unsupported filter) must not become a failure
            # HERE, where the error has no classification. The original
            # bytes still go through the ordinary path, which either reads
            # them or reports a proper CORRUPT_FILE.
            logger.warning(
                "Could not normalise page rotation; continuing with the original bytes",
                exc_info=True,
            )
            return pdf_bytes, 0
        finally:
            out.close()
    finally:
        doc.close()


class LayoutEngine:
    """Factory boundary the pipeline depends on, so tests can inject a fake
    that never touches Docling/PyMuPDF/Pillow.

    Builds its Docling `DocumentConverter` once, lazily, and reuses it for
    every document -- constructing a fresh one per request reloads its
    transformers-based layout model into memory each time, which OOM-killed
    the container during testing after only a handful of requests
    (verified via `docker inspect`'s OOMKilled flag). Construction failures
    are NOT caught here: they propagate to the caller so a broken Docling
    install surfaces as a real, loud failure rather than silently disabling
    native-text detection for every document that follows.
    """

    def __init__(self, table_timeout_per_page_s: float = 0.0, normalize_rotation: bool = True):
        """`table_timeout_per_page_s=0` disables the table-structure bound
        entirely, which is the default here so that a caller constructing
        a bare `LayoutEngine()` gets Docling's own unmodified behaviour.
        Production passes the configured value (see `config.Settings`);
        `worker_pool` is the one place that matters.
        """
        self._docling_converter = None
        self._table_timeout_per_page_s = table_timeout_per_page_s
        self._normalize_rotation = normalize_rotation
        self._rotation_normalized_pages = 0

    def warm_up(self) -> None:
        """Forces the lazy Docling `DocumentConverter` construction AND a
        real conversion to happen now, not on the first real document.

        Verified live this matters: constructing `DocumentConverter()`
        alone does NOT load Docling's own internal sub-models (the
        object-detection transformer for layout, the table-structure
        engine) -- those are lazily initialized inside `.convert()`
        itself, on its first call. A pool worker that only called
        `_converter()` still paid a real ~3-5s one-time cost (HuggingFace
        cache lookups, "Loading weights", engine construction -- see the
        real log lines under docling.models.inference_engines.*) on its
        first actual request, which is exactly the cold-start cost this
        warm-up step exists to eliminate. Converting a trivial in-memory
        single-page PDF here forces that same lazy init to happen during
        startup instead.
        """
        import fitz  # PyMuPDF

        converter = self._converter()
        placeholder = fitz.open()
        placeholder.new_page()
        with tempfile.NamedTemporaryFile(suffix=".pdf", delete=False) as tmp:
            tmp.write(placeholder.tobytes())
            tmp.flush()
            tmp_path = tmp.name
        placeholder.close()
        try:
            converter.convert(tmp_path)
        finally:
            Path(tmp_path).unlink(missing_ok=True)

    def _converter(self):
        if self._docling_converter is None:
            from docling.datamodel.base_models import InputFormat
            from docling.datamodel.pipeline_options import PdfPipelineOptions
            from docling.document_converter import DocumentConverter, ImageFormatOption, PdfFormatOption

            pipeline_options = PdfPipelineOptions()
            # Docling's built-in OCR backends (RapidOCR by default) must
            # stay disabled -- PaddleOCR is the sole character-recognition
            # engine per the tech choice. Verified: enabled by default,
            # it downloads and runs its own OCR pass on every image-only
            # page, duplicating work this pipeline does deliberately with
            # PaddleOCR instead.
            pipeline_options.do_ocr = False
            # Table-structure recognition (TableFormer) is a second,
            # separate model download+inference pass on top of layout
            # analysis. Enabled so tables_for_page can return real
            # rows/columns/cells instead of native_text_blocks_for_page's
            # flat, ungrouped text lines -- verified against the real
            # installed docling-core that turning this on moves a table's
            # cell text out of document.texts and into document.tables
            # with real structure, rather than duplicating it in both.
            pipeline_options.do_table_structure = True
            # Installed before the converter is built, so the very first
            # conversion is already bounded -- including `warm_up`'s.
            _install_table_structure_bound(self._table_timeout_per_page_s)
            self._docling_converter = DocumentConverter(
                format_options={
                    InputFormat.PDF: PdfFormatOption(pipeline_options=pipeline_options),
                    InputFormat.IMAGE: ImageFormatOption(pipeline_options=pipeline_options),
                }
            )
        return self._docling_converter

    def load(
        self, file_bytes: bytes, mime_type: str, original_filename: str, on_page_count=None,
        max_megapixels: float | None = None,
    ) -> DoclingLayoutDocument | DoclingTextDocument:
        """`on_page_count(page_count)` is invoked as early as the format
        allows -- for a PDF/image, right after PyMuPDF has opened the file
        and before Docling converts it; for a .docx, before the conversion
        entirely (its page count is a constant 1).

        That "before the conversion" part is the whole point of the hook
        existing, rather than the caller simply reading `page_count()` off
        the returned document: Docling's conversion is by far the most
        expensive step in `load()` (~104s for a 35-page scanned PDF,
        measured live during Phase 1 close-out), and both the
        `ocr_max_pages` guard and the per-document deadline mean very
        little if they only take effect after it has already run.
        """
        # One document per pool worker at a time, so clearing the shared
        # collector here (rather than per-page) is what makes it belong to
        # THIS document and no other.
        _TABLE_TIMEOUT_PAGES.clear()
        self._rotation_normalized_pages = 0

        if mime_type == "application/pdf" and self._normalize_rotation:
            file_bytes, self._rotation_normalized_pages = normalize_page_rotation(file_bytes)
            if self._rotation_normalized_pages:
                logger.info(
                    "Normalized /Rotate to 0 on %d page(s) before layout analysis",
                    self._rotation_normalized_pages,
                )

        document_class = DoclingTextDocument if mime_type == _DOCX_MIME_TYPE else DoclingLayoutDocument
        return document_class(
            file_bytes, mime_type, original_filename,
            docling_converter=self._converter(), on_page_count=on_page_count,
            max_megapixels=max_megapixels,
        )

    @staticmethod
    def table_structure_skipped_pages() -> set[int]:
        """0-based pages whose table pass was abandoned on the deadline,
        for the document most recently loaded."""
        return set(_TABLE_TIMEOUT_PAGES)


# Real bug found live (docs/ocr/failure-analysis.md #4): the caller-
# supplied Content-Type is trusted nowhere upstream either -- dms-core
# stores the browser's own header verbatim, and a real HEIC upload through
# the actual My Documents page showed Chrome itself sends
# application/octet-stream, not image/heic (no registered browser MIME
# type for .heic). Common aliases some other client (a mobile app, an
# older camera) might send instead of the canonical type.
_MIME_ALIASES = {
    "image/jpg": "image/jpeg",
    "image/pjpeg": "image/jpeg",
    "image/x-png": "image/png",
    "image/tif": "image/tiff",
    "image/x-tiff": "image/tiff",
}


def sniff_and_normalize_mime_type(file_bytes: bytes, declared_mime_type: str) -> str:
    """Real magic-byte sniffing, verified live against every format this
    module supports (PDF/PNG/JPEG/TIFF/BMP/GIF/HEIC and a real .docx --
    `filetype` correctly identifies the latter as the specific Office Open
    XML type, not a generic zip). Falls back to the alias-normalized
    declared type only when sniffing can't identify the content at all.
    """
    import filetype

    kind = filetype.guess(file_bytes)
    if kind is not None:
        return kind.mime
    return _MIME_ALIASES.get(declared_mime_type, declared_mime_type)


SUPPORTED_MIME_TYPES = {
    "application/pdf",
    "image/tiff",
    "image/jpeg",
    "image/png",
    _BMP_MIME_TYPE,
    _GIF_MIME_TYPE,
    _WEBP_MIME_TYPE,
    _HEIC_MIME_TYPE,
    _HEIF_MIME_TYPE,
    _DOCX_MIME_TYPE,
}
