from __future__ import annotations

import tempfile
from dataclasses import dataclass
from pathlib import Path
from types import SimpleNamespace
from typing import Protocol

from .errors import CorruptFileError, EncryptedPdfError


@dataclass
class PageImage:
    page_no: int
    width_px: int
    height_px: int
    image: "Any"  # PIL.Image.Image, kept untyped to avoid importing PIL at module load


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

    def native_text_blocks_for_page(self, page_no: int, dpi: int) -> list[NativeTextBlock] | None: ...

    def native_text_lines_for_page(self, page_no: int, dpi: int) -> list[NativeTextBlock] | None: ...

    def tables_for_page(self, page_no: int, dpi: int) -> list[TableBlock] | None: ...


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

    def __init__(self, file_bytes: bytes, mime_type: str, original_filename: str, docling_converter):
        import fitz  # PyMuPDF

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

        1600px was chosen empirically, not just to stay under PaddleX's own
        4000px max_side_limit: even a correctly-capped ~3000x4000px input
        (already well inside that limit) still OOM-killed the worker during
        PaddleOCR's own CPU det/rec inference (verified live, with Docling
        entirely out of the process by then -- see this class's __init__
        for why a bare image skips Docling altogether). 1600px on the
        longer side is still ~180 DPI-equivalent for a normal Letter/A4
        page, comfortably legible for OCR, while cutting detection-stage
        pixel count (and therefore peak memory) to roughly a quarter of
        what 4000px would need.
        """
        from PIL import Image

        page = self._doc.load_page(page_no)
        zoom = dpi / 72.0
        longest_side_pt = max(page.rect.width, page.rect.height)
        if longest_side_pt > 0:
            zoom = min(zoom, _MAX_RENDER_DIMENSION_PX / longest_side_pt)
        pixmap = page.get_pixmap(matrix=_matrix(zoom))
        image = Image.frombytes("RGB", (pixmap.width, pixmap.height), pixmap.samples)
        return PageImage(page_no=page_no, width_px=pixmap.width, height_px=pixmap.height, image=image)

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
        scale = dpi / 72.0
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
        zoom = dpi / 72.0
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
        scale = dpi / 72.0
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
        self._doc.close()
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

    def __init__(self, file_bytes: bytes, mime_type: str, original_filename: str, docling_converter):
        self._suffix = Path(original_filename).suffix or _suffix_for_mime(mime_type)
        self._tmp = tempfile.NamedTemporaryFile(suffix=self._suffix, delete=False)
        self._tmp.write(file_bytes)
        self._tmp.flush()

        result = docling_converter.convert(self._tmp.name)
        self._text = result.document.export_to_text()

    def page_count(self) -> int:
        return 1

    def render_page(self, page_no: int, dpi: int) -> PageImage:
        raise NotImplementedError(
            "DoclingTextDocument has no page rasterization -- native_text_blocks_for_page "
            "always returns real text for a .docx, so the pipeline should never reach this."
        )

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
    """
    import io

    from PIL import Image

    if mime_type in (_HEIC_MIME_TYPE, _HEIF_MIME_TYPE):
        import pillow_heif

        pillow_heif.register_heif_opener()

    image = Image.open(io.BytesIO(file_bytes))
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

    def __init__(self):
        self._docling_converter = None

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
            self._docling_converter = DocumentConverter(
                format_options={
                    InputFormat.PDF: PdfFormatOption(pipeline_options=pipeline_options),
                    InputFormat.IMAGE: ImageFormatOption(pipeline_options=pipeline_options),
                }
            )
        return self._docling_converter

    def load(self, file_bytes: bytes, mime_type: str, original_filename: str) -> DoclingLayoutDocument | DoclingTextDocument:
        if mime_type == _DOCX_MIME_TYPE:
            return DoclingTextDocument(file_bytes, mime_type, original_filename, docling_converter=self._converter())
        return DoclingLayoutDocument(file_bytes, mime_type, original_filename, docling_converter=self._converter())


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
