from __future__ import annotations

import tempfile
from dataclasses import dataclass
from pathlib import Path
from typing import Protocol

from .validation import validate_document_bytes


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
    # Docling's DocItemLabel value ("title", "section_header", "text",
    # "list_item", "page_header", "page_footer", "caption", ...) -- the
    # document-structure role structuring/ uses for headings and sections.
    # None for PyMuPDF line blocks, which have no such classification.
    label: str | None = None


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

        self._suffix = Path(original_filename).suffix or _suffix_for_mime(mime_type)
        self._tmp = tempfile.NamedTemporaryFile(suffix=self._suffix, delete=False)
        self._tmp.write(file_bytes)
        self._tmp.flush()

        # PyMuPDF opens PDF and single-page raster formats (PNG/JPEG/TIFF)
        # through the same call, so render_page can treat every input
        # uniformly as N pages regardless of source format.
        self._doc = fitz.open(self._tmp.name)

        result = docling_converter.convert(self._tmp.name)
        self._docling_document = result.document

    def page_count(self) -> int:
        return self._doc.page_count

    def render_page(self, page_no: int, dpi: int) -> PageImage:
        """0-indexed page_no, matching PyMuPDF's own convention."""
        from PIL import Image

        page = self._doc.load_page(page_no)
        zoom = dpi / 72.0
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
                label = getattr(getattr(item, "label", None), "value", None)
                blocks.append(NativeTextBlock(text=item.text, bbox=(x0, y0, x1, y1), label=label))
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


def _suffix_for_mime(mime_type: str) -> str:
    return {
        "application/pdf": ".pdf",
        "image/tiff": ".tiff",
        "image/jpeg": ".jpg",
        "image/png": ".png",
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

    def __init__(self, max_document_bytes: int = 50 * 1024 * 1024, max_document_pages: int = 500):
        self._docling_converter = None
        self._max_document_bytes = max_document_bytes
        self._max_document_pages = max_document_pages

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

    def load(self, file_bytes: bytes, mime_type: str, original_filename: str) -> DoclingLayoutDocument:
        # Raises validation.InvalidDocument (UNSUPPORTED_FORMAT/CORRUPTED_
        # DOCUMENT/...) before Docling or PyMuPDF's rasterizer ever parse the
        # file -- every OCR flow loads through here, so none can skip it.
        validate_document_bytes(
            file_bytes, mime_type, max_bytes=self._max_document_bytes, max_pages=self._max_document_pages
        )
        return DoclingLayoutDocument(file_bytes, mime_type, original_filename, docling_converter=self._converter())


SUPPORTED_MIME_TYPES = {"application/pdf", "image/tiff", "image/jpeg", "image/png"}
