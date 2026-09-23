"""Real-PyMuPDF regression coverage for DoclingLayoutDocument.render_page's
render-size cap (see layout.py's own docstring on render_page for the real
crash this guards against: an ordinary ~6.8MP phone photo rendered out to
~53MP at the default OCR DPI and OOM-killed the worker mid-PaddleOCR).

Uses a fake docling_converter (render_page never touches
self._docling_document) so this stays a fast, real-fitz unit test with no
model loading.
"""

import io
from types import SimpleNamespace

import pytest

fitz = pytest.importorskip("fitz")
PIL_Image = pytest.importorskip("PIL.Image")

from ocr_worker.layout import DoclingLayoutDocument, DoclingTextDocument, _MAX_RENDER_DIMENSION_PX


class _FakeDoclingConverter:
    def convert(self, path):
        return SimpleNamespace(document=SimpleNamespace(pages={}, texts=[]))


class _ExplodingDoclingConverter:
    """Asserts Docling's convert() is never called for a bare raster image."""

    def convert(self, path):
        raise AssertionError("docling_converter.convert() must not be called for a raster image")


def _jpeg_bytes(width: int, height: int) -> bytes:
    image = PIL_Image.new("RGB", (width, height), color=(255, 255, 255))
    buf = io.BytesIO()
    image.save(buf, format="JPEG")
    return buf.getvalue()


def _pdf_bytes(width_pt: float, height_pt: float) -> bytes:
    pdf_doc = fitz.open()
    pdf_doc.new_page(width=width_pt, height=height_pt)
    buf = io.BytesIO()
    pdf_doc.save(buf)
    pdf_doc.close()
    return buf.getvalue()


def test_render_page_caps_pixel_size_for_an_ordinary_high_resolution_photo():
    # The real dimensions from the crash: a completely ordinary ~6.8MP
    # phone photo, opened by PyMuPDF as a one-page "document" whose point
    # size already equals its pixel size, previously rendered out to
    # ~6350x8305 (~53MP) at the default 200 DPI and OOM-killed the worker.
    source = _jpeg_bytes(2286, 2990)
    doc = DoclingLayoutDocument(source, "image/jpeg", "photo.jpg", _FakeDoclingConverter())

    page_image = doc.render_page(0, dpi=200)

    assert max(page_image.width_px, page_image.height_px) <= _MAX_RENDER_DIMENSION_PX
    # Aspect ratio preserved, not squashed to a square.
    assert page_image.width_px < page_image.height_px


def test_render_page_still_upscales_a_normal_pdf_page_for_ocr():
    # A real PDF page (Letter, 612x792 pt) at a DPI low enough to stay
    # under the cap is the intended upscale case (a page defined in
    # 72-dpi points genuinely needs more pixels for OCR) and must stay
    # unaffected by the cap. 100 DPI keeps 792pt's render (1100px) under
    # the 1600px cap -- see test_render_page_caps_an_oversized_pdf_page_too
    # for the case where a page's own render legitimately exceeds it.
    source = _pdf_bytes(612, 792)
    doc = DoclingLayoutDocument(source, "application/pdf", "letter.pdf", _FakeDoclingConverter())

    page_image = doc.render_page(0, dpi=100)

    assert page_image.width_px == pytest.approx(612 * 100 / 72, abs=1)
    assert page_image.height_px == pytest.approx(792 * 100 / 72, abs=1)


def test_render_page_caps_an_oversized_pdf_page_too():
    # Defense in depth: even a genuine PDF page is capped if a high enough
    # DPI would otherwise blow past the limit (e.g. a large-format
    # drawing rendered at a high configured DPI).
    source = _pdf_bytes(3000, 4000)
    doc = DoclingLayoutDocument(source, "application/pdf", "drawing.pdf", _FakeDoclingConverter())

    page_image = doc.render_page(0, dpi=300)

    assert max(page_image.width_px, page_image.height_px) <= _MAX_RENDER_DIMENSION_PX


def test_a_bare_raster_image_never_invokes_docling_conversion():
    # A plain photo/scan can never have a native text layer -- do_ocr=False
    # means Docling's own OCR is disabled, so it would produce nothing
    # native_text_blocks_for_page/tables_for_page could ever use anyway.
    # Running Docling's layout + table-structure models for a result
    # nothing reads was real, verified-live memory pressure (see this
    # module's own docstring) contributing to a worker OOM-kill.
    source = _jpeg_bytes(400, 300)
    doc = DoclingLayoutDocument(source, "image/jpeg", "photo.jpg", _ExplodingDoclingConverter())

    assert doc.native_text_blocks_for_page(1, dpi=200) is None
    assert doc.tables_for_page(1, dpi=200) is None


def test_a_real_pdf_still_invokes_docling_conversion():
    calls = []

    class _RecordingDoclingConverter:
        def convert(self, path):
            calls.append(path)
            return SimpleNamespace(document=SimpleNamespace(pages={}, texts=[]))

    source = _pdf_bytes(612, 792)
    DoclingLayoutDocument(source, "application/pdf", "letter.pdf", _RecordingDoclingConverter())

    assert len(calls) == 1


# --- DoclingTextDocument: native .docx text extraction, no OCR at all ---
# Real bug found live: a .docx upload ("OCR could not process this
# document") was rejected outright (UnsupportedMimeType) -- fitz can't
# open a .docx, and the pipeline had no non-PyMuPDF path for a format
# that already has real, structured text and needs no OCR whatsoever.


class _FakeDoclingTextConverter:
    def __init__(self, text: str):
        self._text = text

    def convert(self, path):
        document = SimpleNamespace(export_to_text=lambda: self._text)
        return SimpleNamespace(document=document)


def test_docling_text_document_reports_exactly_one_page():
    doc = DoclingTextDocument(b"", "application/vnd...docx", "policy.docx", _FakeDoclingTextConverter("Hello world"))
    assert doc.page_count() == 1


def test_docling_text_document_returns_the_full_flattened_text_as_one_block():
    # Real policy document found live: export_to_text() renders Docling's
    # table-structure recognition as readable pipe-delimited rows (e.g.
    # "DOCUMENT REF | ISMS-DOC-A08-28-1"), which the existing flattened-text
    # label-proximity matcher can already match against directly.
    text = "Secure Coding Policy\n\n| DOCUMENT REF | ISMS-DOC-A08-28-1 |"
    doc = DoclingTextDocument(b"", "application/vnd...docx", "policy.docx", _FakeDoclingTextConverter(text))

    blocks = doc.native_text_blocks_for_page(1, dpi=200)

    assert blocks is not None
    assert len(blocks) == 1
    assert blocks[0].text == text


def test_docling_text_document_returns_none_for_any_page_other_than_one():
    doc = DoclingTextDocument(b"", "application/vnd...docx", "policy.docx", _FakeDoclingTextConverter("some text"))
    assert doc.native_text_blocks_for_page(2, dpi=200) is None


def test_docling_text_document_returns_none_for_empty_text():
    doc = DoclingTextDocument(b"", "application/vnd...docx", "policy.docx", _FakeDoclingTextConverter("   "))
    assert doc.native_text_blocks_for_page(1, dpi=200) is None


def test_docling_text_document_has_no_lines_or_tables_or_rasterization():
    # No fixed pixel geometry exists for a reflowable document -- these
    # must all safely report "nothing here" rather than guess at bboxes,
    # and render_page must fail loudly (never silently) if ever reached,
    # since native_text_blocks_for_page always returns real text for a
    # .docx and the pipeline should never fall through to it.
    doc = DoclingTextDocument(b"", "application/vnd...docx", "policy.docx", _FakeDoclingTextConverter("some text"))
    assert doc.native_text_lines_for_page(1, dpi=200) is None
    assert doc.tables_for_page(1, dpi=200) is None
    with pytest.raises(NotImplementedError):
        doc.render_page(0, dpi=200)
