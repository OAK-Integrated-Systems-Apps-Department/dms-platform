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

from ocr_worker.layout import DEFAULT_RENDER_MAX_MP, DoclingLayoutDocument, DoclingTextDocument


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

    # The rasterizer rounds each side up independently, so the realised
    # area can exceed the budget by at most one extra row plus one extra
    # column. Asserted with exactly that slack rather than a round fudge
    # factor, so a real regression in the scale cannot hide inside it.
    rounding_slack = page_image.width_px + page_image.height_px + 1
    assert page_image.width_px * page_image.height_px <= DEFAULT_RENDER_MAX_MP * 1_000_000 + rounding_slack
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

    # The rasterizer rounds each side up independently, so the realised
    # area can exceed the budget by at most one extra row plus one extra
    # column. Asserted with exactly that slack rather than a round fudge
    # factor, so a real regression in the scale cannot hide inside it.
    rounding_slack = page_image.width_px + page_image.height_px + 1
    assert page_image.width_px * page_image.height_px <= DEFAULT_RENDER_MAX_MP * 1_000_000 + rounding_slack


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


# --- on_page_count: the early hook the ocr_max_pages guard and the
# per-document deadline both hang off (see LayoutEngine.load). Its whole
# value is the ORDERING -- before Docling's conversion, which is ~104s for
# a 35-page scanned PDF (measured live). A test that only checked "the
# hook was called" would pass just as happily with it fired afterwards.


class _OrderRecordingDoclingConverter:
    def __init__(self, order: list[str]):
        self._order = order

    def convert(self, path):
        self._order.append("docling.convert")
        return SimpleNamespace(document=SimpleNamespace(pages={}, texts=[]))


def test_on_page_count_fires_before_docling_conversion():
    order: list[str] = []
    seen: list[int] = []

    DoclingLayoutDocument(
        _pdf_bytes(612, 792), "application/pdf", "letter.pdf",
        _OrderRecordingDoclingConverter(order),
        on_page_count=lambda pages: (order.append("on_page_count"), seen.append(pages)),
    )

    assert order == ["on_page_count", "docling.convert"]
    assert seen == [1]


def test_raising_from_on_page_count_skips_the_conversion_entirely():
    """This is how TOO_MANY_PAGES short-circuits. Before the hook existed
    the guard lived in pipeline.py, after `load()` had returned -- so an
    oversized document paid Docling's full conversion cost and only then
    got rejected, on every single attempt.
    """
    order: list[str] = []

    class _TooBig(Exception):
        pass

    with pytest.raises(_TooBig):
        DoclingLayoutDocument(
            _pdf_bytes(612, 792), "application/pdf", "letter.pdf",
            _OrderRecordingDoclingConverter(order),
            on_page_count=_raise(_TooBig),
        )

    assert order == []


def test_raising_from_on_page_count_still_releases_the_open_document():
    """No `with` block protects the caller yet -- __init__ hasn't
    returned -- so the constructor has to clean up after itself or every
    oversized document leaks a temp file and an open MuPDF handle.
    """
    class _TooBig(Exception):
        pass

    captured: dict = {}
    original_init = DoclingLayoutDocument.close

    def spy(self):
        captured["tmp_path"] = self._tmp.name
        original_init(self)

    DoclingLayoutDocument.close = spy
    try:
        with pytest.raises(_TooBig):
            DoclingLayoutDocument(
                _pdf_bytes(612, 792), "application/pdf", "letter.pdf",
                _OrderRecordingDoclingConverter([]), on_page_count=_raise(_TooBig),
            )
    finally:
        DoclingLayoutDocument.close = original_init

    import os

    assert not os.path.exists(captured["tmp_path"])


def _raise(exception_class):
    def raiser(_pages):
        raise exception_class()

    return raiser


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


# ----------------------------------------------------------------------
# EXIF orientation (production prompt 3.2, "apply EXIF orientation for
# camera images before anything else").
#
# Nothing in the worker did this explicitly, which looked like a plain
# gap. It is not: fitz/MuPDF applies EXIF orientation itself during
# rasterization, so the ordinary JPEG/TIFF path is already correct. These
# tests pin that, because it is load-bearing behaviour of a dependency
# that nothing in this codebase asserts -- and because the ONE path that
# does not get it for free (the PIL-preconvert path for WEBP/HEIC/HEIF,
# which re-encodes to PNG, a format with no orientation tag) had to be
# fixed. Without these, a future switch away from fitz would silently
# start feeding OCR sideways phone photos.
# ----------------------------------------------------------------------
_EXIF_ORIENTATION_TAG = 274

# Landscape, so a quarter-turn is visible in the dimensions, with a marked
# corner so the 180-degree case -- which preserves dimensions -- is
# answerable too.
_EXIF_W, _EXIF_H = 400, 200


def _marked_image():
    image = PIL_Image.new("RGB", (_EXIF_W, _EXIF_H), "white")
    image.paste(PIL_Image.new("RGB", (40, 40), "black"), (0, 0))  # top-left
    return image


def _with_exif_orientation(fmt: str, orientation: int) -> bytes:
    exif = PIL_Image.Exif()
    exif[_EXIF_ORIENTATION_TAG] = orientation
    buf = io.BytesIO()
    _marked_image().save(buf, format=fmt, exif=exif)
    return buf.getvalue()


def _black_corner_of(image) -> str:
    w, h = image.size
    corners = {
        "TL": image.getpixel((10, 10)),
        "TR": image.getpixel((w - 10, 10)),
        "BL": image.getpixel((10, h - 10)),
        "BR": image.getpixel((w - 10, h - 10)),
    }
    dark = [name for name, px in corners.items() if sum(px[:3]) < 200]
    assert len(dark) == 1, f"expected exactly one marked corner, found {dark}"
    return dark[0]


def _fitz_render(data: bytes, filetype: str):
    doc = fitz.open(stream=data, filetype=filetype)
    pixmap = doc[0].get_pixmap()
    image = PIL_Image.frombytes("RGB", (pixmap.width, pixmap.height), pixmap.samples)
    doc.close()
    return image


@pytest.mark.parametrize(
    "orientation,expected_landscape,expected_corner",
    [
        (1, True, "TL"),   # already upright
        (3, True, "BR"),   # 180
        (6, False, "TR"),  # 90 CW -- the common iPhone/Android portrait shot
        (8, False, "BL"),  # 90 CCW
    ],
)
def test_fitz_applies_exif_orientation_when_it_rasterizes(
    orientation, expected_landscape, expected_corner
):
    rendered = _fitz_render(_with_exif_orientation("JPEG", orientation), "jpg")
    assert (rendered.size[0] > rendered.size[1]) is expected_landscape
    assert _black_corner_of(rendered) == expected_corner


def test_the_pil_preconvert_path_applies_exif_orientation_itself():
    """The one path that does not inherit fitz's handling: WEBP/HEIC/HEIF
    are decoded by Pillow and re-encoded as PNG, and PNG carries no
    orientation tag -- so whatever is not applied here is lost for good.

    Verified against the installed Pillow: a WEBP written with
    Orientation=6 opens at its raw 400x200 with tag 274 still set, i.e.
    Pillow does NOT apply it on open (unlike pillow-heif, which does), and
    the page reached OCR sideways before this.
    """
    from ocr_worker.layout import _decode_via_pil_to_png

    data = _with_exif_orientation("WEBP", 6)
    assert PIL_Image.open(io.BytesIO(data)).size == (_EXIF_W, _EXIF_H)  # not yet applied

    rendered = _fitz_render(_decode_via_pil_to_png(data, "image/webp"), "png")

    assert rendered.size[0] < rendered.size[1]  # now portrait: the rotation was applied
    assert _black_corner_of(rendered) == "TR"


def test_the_pil_preconvert_path_leaves_an_already_upright_image_alone():
    from ocr_worker.layout import _decode_via_pil_to_png

    rendered = _fitz_render(_decode_via_pil_to_png(_with_exif_orientation("WEBP", 1), "image/webp"), "png")

    assert rendered.size[0] > rendered.size[1]
    assert _black_corner_of(rendered) == "TL"


# ----------------------------------------------------------------------
# Discarded scanner pixels are reported, not thrown away in silence
#
# The render budget means a genuine 300- or 600-DPI scan is downsampled
# on arrival. That is a legitimate trade against a measured OOM ceiling,
# but doing it silently is not: a high-DPI scan that reads badly is
# indistinguishable from a bad scan unless the output says the worker
# threw most of it away. See docs/ocr/phase2-verification.md Step 4.
# ----------------------------------------------------------------------
def test_a_high_resolution_scan_records_what_was_discarded():
    # A real 300-DPI A4 scan: 2480x3508 native. PyMuPDF opens a bare
    # raster as a one-page document whose point size is its pixel size
    # scaled by 72/96, so this is the true "supplied a big scan" case.
    source = _jpeg_bytes(2480, 3508)
    doc = DoclingLayoutDocument(source, "image/jpeg", "scan.jpg", _FakeDoclingConverter())

    geometry = doc.page_geometry(0, dpi=200)

    assert geometry.downsampled_from == (2480, 3508)
    reported = geometry.to_dict()["downsampled_from"]
    assert reported["width_px"] == 2480
    assert reported["height_px"] == 3508
    assert reported["megapixels"] == pytest.approx(8.70, abs=0.01)
    # A real fraction of the source, so "how much was lost" is answerable
    # without the consumer redoing the arithmetic.
    assert 0 < reported["scale"] < 1


def test_a_page_that_lost_nothing_says_nothing():
    """Absence has to mean "nothing was discarded", not "nobody looked" --
    otherwise the field is useless for deciding whether to re-scan."""
    source = _pdf_bytes(612, 792)  # vector, no raster at all
    doc = DoclingLayoutDocument(source, "application/pdf", "letter.pdf", _FakeDoclingConverter())

    geometry = doc.page_geometry(0, dpi=200)

    assert geometry.downsampled_from is None
    assert "downsampled_from" not in geometry.to_dict()


def test_a_small_image_rendered_at_or_above_its_own_size_is_not_downsampled():
    """An upscale is not a loss. A 400x300 image rendered larger than
    itself has had nothing discarded, and reporting it as downsampled
    would train whoever reads these to ignore the field."""
    source = _jpeg_bytes(400, 300)
    doc = DoclingLayoutDocument(source, "image/jpeg", "small.jpg", _FakeDoclingConverter())

    geometry = doc.page_geometry(0, dpi=200)

    assert geometry.width_px >= 400
    assert geometry.downsampled_from is None


def test_the_discard_is_logged_with_both_sizes(caplog):
    """The log line is what a support engineer has when nobody kept the
    output JSON, so it has to carry the source size, the rendered size
    and the budget that caused it."""
    import logging

    source = _jpeg_bytes(2480, 3508)
    doc = DoclingLayoutDocument(source, "image/jpeg", "scan.jpg", _FakeDoclingConverter())

    with caplog.at_level(logging.INFO, logger="ocr_worker.layout"):
        doc.render_page(0, dpi=200)

    messages = [r.getMessage() for r in caplog.records]
    downsample_lines = [m for m in messages if "downsampled" in m]
    assert len(downsample_lines) == 1
    line = downsample_lines[0]
    assert "2480x3508" in line
    assert "OCR_RENDER_MAX_MP" in line


def test_forgetting_the_budget_still_caps_the_render():
    """`max_megapixels=None` means the default, NOT "uncapped".

    Got this wrong first time: with None meaning no cap, this very
    fixture rendered 3000x4000pt at 12500x16667 = 208 MP, which at the
    measured ~0.7 GiB/MP is an instant OOM kill taking every other
    in-flight document in that worker with it. The safe value has to be
    the one you get by forgetting.
    """
    source = _pdf_bytes(3000, 4000)
    doc = DoclingLayoutDocument(
        source, "application/pdf", "drawing.pdf", _FakeDoclingConverter(), max_megapixels=None
    )

    page_image = doc.render_page(0, dpi=300)

    slack = page_image.width_px + page_image.height_px + 1
    assert page_image.width_px * page_image.height_px <= DEFAULT_RENDER_MAX_MP * 1_000_000 + slack


def test_a_scanned_pdf_page_also_reports_its_source_raster():
    """The other shape of the same case, and the reason `get_images()`
    was not enough: a scan usually arrives as a PDF wrapping one big
    image, not as a bare JPEG. Both must be detected, or the field is
    only trustworthy for half the population it describes."""
    photo = _jpeg_bytes(2480, 3508)
    pdf_doc = fitz.open()
    page = pdf_doc.new_page(width=595, height=842)  # A4, in points
    page.insert_image(fitz.Rect(0, 0, 595, 842), stream=photo)
    buf = io.BytesIO()
    pdf_doc.save(buf)
    pdf_doc.close()

    doc = DoclingLayoutDocument(buf.getvalue(), "application/pdf", "scan.pdf", _FakeDoclingConverter())
    geometry = doc.page_geometry(0, dpi=200)

    assert geometry.downsampled_from == (2480, 3508)
    assert geometry.to_dict()["downsampled_from"]["megapixels"] == pytest.approx(8.70, abs=0.01)


def test_a_pdf_page_never_takes_the_expensive_native_size_path():
    """A performance regression that cost whole documents, pinned.

    `page_geometry()` is called at least twice per page, and it calls
    `native_pixel_size()`. Using `get_image_info()` there -- which parses
    the content stream and decodes enough of each image to describe it --
    measured **1930ms for a single scanned page** and 3341ms for a
    35-page scan. Under the 50-upload concurrent run that pushed
    one-page documents past their 85s budget and timed out a 35-page scan
    at page 34 of 35.

    `get_images()` reads only the resource dictionary, returns the
    identical dimensions on a PDF, and takes 0.4ms. Asserted by spying on
    the call rather than by timing, because a timing assertion on a
    loaded CI box is a flake generator.
    """
    from ocr_worker.layout import native_pixel_size

    photo = _jpeg_bytes(2480, 3508)
    pdf_doc = fitz.open()
    page = pdf_doc.new_page(width=595, height=842)
    page.insert_image(fitz.Rect(0, 0, 595, 842), stream=photo)
    buf = io.BytesIO()
    pdf_doc.save(buf)
    pdf_doc.close()

    doc = fitz.open(stream=buf.getvalue(), filetype="pdf")
    real_page = doc[0]
    calls = {"info": 0, "images": 0}
    real_get_image_info = real_page.get_image_info
    real_get_images = real_page.get_images

    class Spy:
        rect = real_page.rect

        def get_image_info(self, *a, **k):
            calls["info"] += 1
            return real_get_image_info(*a, **k)

        def get_images(self, *a, **k):
            calls["images"] += 1
            return real_get_images(*a, **k)

    try:
        size = native_pixel_size(Spy(), raster_source=False)
        assert size == (2480, 3508)
        assert calls["images"] == 1
        assert calls["info"] == 0, "a PDF must not take the content-stream-parsing path"
    finally:
        doc.close()


def test_a_bare_raster_uses_the_path_that_actually_works_for_it():
    """The other half of the split. `get_images()` lists a PDF page's
    image XObjects and returns NOTHING for a bare JPEG/TIFF, because
    MuPDF synthesises a page around such a file -- so a phone photo would
    report "nothing discarded". For that shape `get_image_info()` is both
    correct and already fast (0.4-1.0ms measured)."""
    from ocr_worker.layout import native_pixel_size

    doc = fitz.open(stream=_jpeg_bytes(2300, 3000), filetype="jpg")
    try:
        assert native_pixel_size(doc[0], raster_source=False) is None  # the wrong path sees nothing
        assert native_pixel_size(doc[0], raster_source=True) == (2300, 3000)
    finally:
        doc.close()


# ----------------------------------------------------------------------
# PageGeometry must describe the raster that actually gets rendered
#
# Every `bbox_norm` divides by these numbers while every bbox is measured
# in the pixmap's space, so a disagreement between the two is a box that
# normalizes outside [0, 1]. Both defects below were found by probing a
# real `/Rotate 90` scan in eval/corpus/real/.
# ----------------------------------------------------------------------
def test_geometry_pixel_size_matches_the_pixmap_exactly():
    """`round(width_pt * scale)` disagreed with PyMuPDF by a pixel on an
    ordinary A4 page -- 1131x1600 reported against a real 1132x1601 --
    because the rasterizer takes the integer rect of the transformed page
    rather than rounding each side independently."""
    source = _pdf_bytes(595.08, 841.68)  # A4 in points, the shape that broke
    doc = DoclingLayoutDocument(source, "application/pdf", "a4.pdf", _FakeDoclingConverter())

    geometry = doc.page_geometry(0, dpi=200)
    rendered = doc.render_page(0, dpi=200)

    assert (geometry.width_px, geometry.height_px) == (rendered.image.width, rendered.image.height)


@pytest.mark.parametrize("width_pt, height_pt", [(595.08, 841.68), (841.68, 595.08), (612, 792)])
def test_geometry_and_pixmap_agree_whatever_the_page_shape(width_pt, height_pt):
    doc = DoclingLayoutDocument(
        _pdf_bytes(width_pt, height_pt), "application/pdf", "p.pdf", _FakeDoclingConverter()
    )
    geometry = doc.page_geometry(0, dpi=200)
    rendered = doc.render_page(0, dpi=200)
    assert (geometry.width_px, geometry.height_px) == (rendered.image.width, rendered.image.height)


def test_downsampled_from_is_reported_in_the_rendered_pages_orientation():
    """A real corpus scan (SCAN0002) is a landscape 2338x1653 raster on a
    page that renders portrait, and `downsampled_from` came back
    (2338, 1653) against a 1132x1601 render -- a width compared against a
    height. The whole point of the field is to say what resolution was
    discarded, so a transposed answer is worse than none.

    Keyed on the two aspects disagreeing, NOT on `page.rotation`: the
    load path normalizes `/Rotate` away, so that page arrives reporting
    rotation 0 and a rotation-based swap would never fire.
    """
    import fitz

    # A portrait page carrying a landscape raster -- the real shape.
    pdf = fitz.open()
    page = pdf.new_page(width=595.08, height=841.68)
    page.insert_image(page.rect, stream=_jpeg_bytes(2338, 1653))
    buf = io.BytesIO()
    pdf.save(buf)
    pdf.close()

    doc = DoclingLayoutDocument(buf.getvalue(), "application/pdf", "rot.pdf", _FakeDoclingConverter())
    geometry = doc.page_geometry(0, dpi=200)

    assert geometry.height_px > geometry.width_px, "fixture must render portrait"
    assert geometry.downsampled_from == (1653, 2338)


def test_a_raster_already_matching_the_page_orientation_is_not_swapped():
    source = _jpeg_bytes(2480, 3508)  # portrait scan, portrait page
    doc = DoclingLayoutDocument(source, "image/jpeg", "scan.jpg", _FakeDoclingConverter())
    assert doc.page_geometry(0, dpi=200).downsampled_from == (2480, 3508)
