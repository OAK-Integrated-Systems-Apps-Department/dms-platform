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


# --- Phase 1 stability: encrypted/corrupt PDF handling, FILE_REPAIRED
# detection, new format support (docs/ocr/failure-analysis.md #4/#9) ---

from ocr_worker.errors import CorruptFileError, EncryptedPdfError  # noqa: E402


def _pdf_bytes_full(
    width_pt: float = 612,
    height_pt: float = 792,
    # Long enough that a 50% truncation still leaves recoverable structure
    # for MuPDF's repair to work with -- verified empirically: the same
    # truncation on a much shorter ("Hello") document fails to open at all
    # instead of repairing, since there's too little redundant structure
    # left. A real invoice-sized PDF (docs/ocr/failure-analysis.md #9's
    # live finding) behaves like this longer text, not the short one.
    text: str = "Hello World, this is a test document with enough content to survive truncation and still be repairable by MuPDF's own internal parser logic.",
) -> bytes:
    doc = fitz.open()
    page = doc.new_page(width=width_pt, height=height_pt)
    page.insert_text((50, 50), text)
    buf = io.BytesIO()
    doc.save(buf)
    doc.close()
    return buf.getvalue()


def test_password_protected_pdf_raises_encrypted_pdf_error():
    pypdf = pytest.importorskip("pypdf")
    reader = pypdf.PdfReader(io.BytesIO(_pdf_bytes_full()))
    writer = pypdf.PdfWriter()
    writer.append(reader)
    writer.encrypt(user_password="test123", algorithm="AES-256")
    out = io.BytesIO()
    writer.write(out)

    with pytest.raises(EncryptedPdfError):
        DoclingLayoutDocument(out.getvalue(), "application/pdf", "secret.pdf", _FakeDoclingConverter())


def test_severely_truncated_pdf_raises_corrupt_file_error():
    # Real finding, live (docs/ocr/failure-analysis.md #9): only a fragment
    # this small (no valid xref/trailer at all) actually fails to open --
    # 50% truncation and even magic-header replacement were both silently
    # repaired/tolerated by MuPDF's own lenient parser (see the
    # file_repaired tests below).
    tiny_fragment = _pdf_bytes_full()[:200]
    with pytest.raises(CorruptFileError):
        DoclingLayoutDocument(tiny_fragment, "application/pdf", "corrupt.pdf", _FakeDoclingConverter())


def test_truncated_but_repairable_pdf_sets_file_repaired_flag():
    # Real finding, live: a PDF truncated to 50% of its bytes still opens
    # (MuPDF repairs it) and, in this case, still has enough content for
    # Docling to convert successfully -- flags.file_repaired must be set
    # so the result is reviewed rather than silently trusted, even though
    # nothing actually raised.
    half = _pdf_bytes_full()
    half = half[: len(half) // 2]
    doc = DoclingLayoutDocument(half, "application/pdf", "repaired.pdf", _FakeDoclingConverter())
    assert doc.file_repaired is True


def test_clean_pdf_never_sets_file_repaired_flag():
    doc = DoclingLayoutDocument(_pdf_bytes_full(), "application/pdf", "clean.pdf", _FakeDoclingConverter())
    assert doc.file_repaired is False


def test_zero_page_pdf_raises_corrupt_file_error():
    # fitz's own .save() refuses to write a zero-page PDF at all ("cannot
    # save with zero pages") -- a minimal hand-crafted one (empty /Kids)
    # is the only way to construct a real file exercising this guard.
    raw = (
        b"%PDF-1.4\n"
        b"1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n"
        b"2 0 obj\n<< /Type /Pages /Kids [] /Count 0 >>\nendobj\n"
        b"trailer\n<< /Size 3 /Root 1 0 R >>\n"
        b"%%EOF"
    )

    with pytest.raises(CorruptFileError):
        DoclingLayoutDocument(raw, "application/pdf", "empty.pdf", _FakeDoclingConverter())


def test_bmp_opens_directly_no_pil_preconversion_needed():
    image = PIL_Image.new("RGB", (100, 100), color=(255, 255, 255))
    buf = io.BytesIO()
    image.save(buf, format="BMP")

    doc = DoclingLayoutDocument(buf.getvalue(), "image/bmp", "photo.bmp", _ExplodingDoclingConverter())
    assert doc.page_count() == 1


def test_gif_opens_directly_and_multiframe_collapses_to_one_page():
    frames = [PIL_Image.new("RGB", (50, 50), color=c) for c in ("red", "blue", "green")]
    buf = io.BytesIO()
    frames[0].save(buf, format="GIF", save_all=True, append_images=frames[1:])

    doc = DoclingLayoutDocument(buf.getvalue(), "image/gif", "anim.gif", _ExplodingDoclingConverter())
    assert doc.page_count() == 1


def test_webp_is_decoded_via_pil_preconversion():
    # Verified live: fitz/MuPDF (installed 1.28.2) raises a real
    # FileDataError trying to open a WEBP directly -- this must go through
    # _decode_via_pil_to_png first, not straight to fitz.open.
    image = PIL_Image.new("RGB", (100, 100), color=(255, 255, 255))
    buf = io.BytesIO()
    image.save(buf, format="WEBP")

    doc = DoclingLayoutDocument(buf.getvalue(), "image/webp", "photo.webp", _ExplodingDoclingConverter())
    assert doc.page_count() == 1


def test_heic_is_decoded_via_pil_preconversion():
    pillow_heif = pytest.importorskip("pillow_heif")
    image = PIL_Image.new("RGB", (100, 100), color=(255, 255, 255))
    heif_file = pillow_heif.from_pillow(image)
    buf = io.BytesIO()
    heif_file.save(buf, format="HEIF")

    doc = DoclingLayoutDocument(buf.getvalue(), "image/heic", "photo.heic", _ExplodingDoclingConverter())
    assert doc.page_count() == 1


# --- sniff_and_normalize_mime_type (docs/ocr/failure-analysis.md #4) ---

from ocr_worker.layout import sniff_and_normalize_mime_type  # noqa: E402


def test_sniff_recovers_real_type_when_declared_type_is_generic_octet_stream():
    # Real finding, live: a real HEIC upload through the actual My
    # Documents page showed Chrome itself sends application/octet-stream,
    # not image/heic (no registered browser MIME type for .heic).
    pillow_heif = pytest.importorskip("pillow_heif")
    image = PIL_Image.new("RGB", (100, 100), color=(255, 255, 255))
    heif_file = pillow_heif.from_pillow(image)
    buf = io.BytesIO()
    heif_file.save(buf, format="HEIF")

    result = sniff_and_normalize_mime_type(buf.getvalue(), "application/octet-stream")
    assert result == "image/heic"


def test_sniff_falls_back_to_alias_normalized_declared_type_when_unsniffable():
    # Garbage bytes filetype can't identify at all -- must fall back to
    # the (alias-normalized) declared type rather than raise or return None.
    result = sniff_and_normalize_mime_type(b"not a real file", "image/jpg")
    assert result == "image/jpeg"


def test_sniff_identifies_real_pdf_bytes_correctly():
    result = sniff_and_normalize_mime_type(_pdf_bytes_full(), "application/octet-stream")
    assert result == "application/pdf"
