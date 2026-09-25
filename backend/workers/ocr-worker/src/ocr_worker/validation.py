"""Upload validation that runs before any document byte reaches Docling or
PaddleOCR (layout.py's LayoutEngine.load is the single entry point every
OCR flow -- full document, zone, rubber-band -- goes through).

Checks, in order: empty file, size limit, declared mime type vs. the file's
real signature (a renamed .exe declared as application/pdf never gets
parsed), then a cheap structural open (corrupted/truncated PDF, encrypted
PDF, zero or too many pages, undecodable image). Every failure is an
InvalidDocument whose message starts with a stable machine-readable code --
it becomes processing_jobs.last_error and the ocr.failed event's `error`,
so callers can branch on the code. Messages never quote file content.
"""
from __future__ import annotations

import io

UNSUPPORTED_FORMAT = "UNSUPPORTED_FORMAT"
EMPTY_DOCUMENT = "EMPTY_DOCUMENT"
DOCUMENT_TOO_LARGE = "DOCUMENT_TOO_LARGE"
CORRUPTED_DOCUMENT = "CORRUPTED_DOCUMENT"
ENCRYPTED_DOCUMENT = "ENCRYPTED_DOCUMENT"

_PDF = "application/pdf"
_PNG = "image/png"
_JPEG = "image/jpeg"
_TIFF = "image/tiff"


class InvalidDocument(ValueError):
    def __init__(self, code: str, detail: str):
        super().__init__(f"{code}: {detail}")
        self.code = code
        self.detail = detail


def sniff_mime_type(data: bytes) -> str | None:
    """The real type from the file's leading bytes, or None if it isn't one
    of the formats this service supports."""
    head = data[:1024]
    # PDF allows junk before the header (the spec says within the first
    # 1024 bytes), which some scanners' output really does have.
    if b"%PDF-" in head:
        return _PDF
    if data.startswith(b"\x89PNG\r\n\x1a\n"):
        return _PNG
    if data.startswith(b"\xff\xd8\xff"):
        return _JPEG
    if data.startswith((b"II*\x00", b"MM\x00*")):
        return _TIFF
    return None


def validate_document_bytes(data: bytes, declared_mime_type: str, *, max_bytes: int, max_pages: int) -> None:
    if not data:
        raise InvalidDocument(EMPTY_DOCUMENT, "the stored file is empty (0 bytes)")
    if len(data) > max_bytes:
        raise InvalidDocument(DOCUMENT_TOO_LARGE, f"file is {len(data)} bytes, limit is {max_bytes}")

    actual = sniff_mime_type(data)
    if actual is None:
        raise InvalidDocument(UNSUPPORTED_FORMAT, f"file content is not a supported PDF/PNG/JPEG/TIFF (declared {declared_mime_type!r})")
    if actual != declared_mime_type:
        raise InvalidDocument(UNSUPPORTED_FORMAT, f"declared mime type {declared_mime_type!r} does not match file content ({actual})")

    if actual == _PDF:
        _validate_pdf(data, max_pages)
    else:
        _validate_image(data, max_pages)


def _validate_pdf(data: bytes, max_pages: int) -> None:
    import fitz  # PyMuPDF

    try:
        # stream= opens from the existing buffer; no temp file or copy.
        doc = fitz.open(stream=data, filetype="pdf")
    except Exception as exc:  # noqa: BLE001 - PyMuPDF raises several unrelated types for bad input
        raise InvalidDocument(CORRUPTED_DOCUMENT, f"PDF could not be opened ({type(exc).__name__})") from exc
    try:
        if doc.needs_pass:
            raise InvalidDocument(ENCRYPTED_DOCUMENT, "PDF is password-protected")
        pages = doc.page_count
        if pages == 0:
            raise InvalidDocument(CORRUPTED_DOCUMENT, "PDF has no pages")
        if pages > max_pages:
            raise InvalidDocument(DOCUMENT_TOO_LARGE, f"PDF has {pages} pages, limit is {max_pages}")
        # PyMuPDF silently "repairs" a truncated PDF and opens it with part
        # of its content missing (verified: a PDF cut to 25% opened with 1
        # page and no text) -- OCR would then succeed on a fraction of the
        # document. Repaired AND no %%EOF trailer means truncated; a
        # repaired file that still ends properly (scanner xref glitches)
        # is allowed.
        if doc.is_repaired and b"%%EOF" not in data[-1024:]:
            raise InvalidDocument(CORRUPTED_DOCUMENT, "PDF is truncated (no end-of-file trailer; structure had to be repaired)")
        # A truncated file often still opens (PyMuPDF repairs the xref) but
        # can't load its pages -- touching the first and last page catches
        # that here instead of halfway through OCR.
        for index in {0, pages - 1}:
            try:
                doc.load_page(index)
            except Exception as exc:  # noqa: BLE001
                raise InvalidDocument(CORRUPTED_DOCUMENT, f"PDF page {index + 1} could not be read ({type(exc).__name__})") from exc
    finally:
        doc.close()


def _validate_image(data: bytes, max_pages: int) -> None:
    from PIL import Image

    try:
        with Image.open(io.BytesIO(data)) as image:
            frames = getattr(image, "n_frames", 1)
            # verify() checks integrity without decoding every pixel.
            image.verify()
    except Image.DecompressionBombError as exc:
        raise InvalidDocument(DOCUMENT_TOO_LARGE, "image pixel dimensions exceed the decompression-bomb limit") from exc
    except Exception as exc:  # noqa: BLE001 - Pillow raises OSError/SyntaxError/ValueError variants for bad input
        raise InvalidDocument(CORRUPTED_DOCUMENT, f"image could not be decoded ({type(exc).__name__})") from exc
    if frames > max_pages:
        raise InvalidDocument(DOCUMENT_TOO_LARGE, f"image has {frames} frames, limit is {max_pages}")
