"""Upload validation (validation.py) against the real fixtures plus
deliberately broken files. Needs PyMuPDF + Pillow (runtime dependencies),
not Docling or PaddleOCR."""
from __future__ import annotations

import io
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "src"))

fitz = pytest.importorskip("fitz")
Image = pytest.importorskip("PIL.Image")

from ocr_worker.validation import InvalidDocument, sniff_mime_type, validate_document_bytes  # noqa: E402

FIXTURES = Path(__file__).resolve().parent / "fixtures"
LIMITS = {"max_bytes": 50 * 1024 * 1024, "max_pages": 500}


def _pdf(pages: int = 1, **save_kwargs) -> bytes:
    doc = fitz.open()
    for i in range(pages):
        doc.new_page().insert_text((72, 72), f"Page {i + 1}")
    data = doc.tobytes(**save_kwargs)
    doc.close()
    return data


def _png() -> bytes:
    buffer = io.BytesIO()
    Image.new("RGB", (64, 32), "white").save(buffer, format="PNG")
    return buffer.getvalue()


def _code(data: bytes, mime: str, **limits) -> str:
    with pytest.raises(InvalidDocument) as info:
        validate_document_bytes(data, mime, **{**LIMITS, **limits})
    return info.value.code


@pytest.mark.parametrize(
    "name, mime",
    [("native_text.pdf", "application/pdf"), ("scanned_image_only.pdf", "application/pdf"), ("scanned_image.png", "image/png")],
)
def test_real_fixtures_pass(name, mime):
    validate_document_bytes((FIXTURES / name).read_bytes(), mime, **LIMITS)


def test_generated_jpeg_and_tiff_pass():
    for fmt, mime in (("JPEG", "image/jpeg"), ("TIFF", "image/tiff")):
        buffer = io.BytesIO()
        Image.new("RGB", (64, 32), "white").save(buffer, format=fmt)
        validate_document_bytes(buffer.getvalue(), mime, **LIMITS)


def test_sniffing_recognizes_each_supported_signature():
    assert sniff_mime_type(_pdf()) == "application/pdf"
    assert sniff_mime_type(b"junk before header %PDF-1.4 ...") == "application/pdf"
    assert sniff_mime_type(_png()) == "image/png"
    assert sniff_mime_type(b"\xff\xd8\xff\xe0rest") == "image/jpeg"
    assert sniff_mime_type(b"II*\x00rest") == sniff_mime_type(b"MM\x00*rest") == "image/tiff"
    assert sniff_mime_type(b"MZ\x90\x00") is None


def test_empty_file():
    assert _code(b"", "application/pdf") == "EMPTY_DOCUMENT"


def test_executable_declared_as_pdf_is_unsupported():
    assert _code(b"MZ\x90\x00\x03" + b"\x00" * 100, "application/pdf") == "UNSUPPORTED_FORMAT"


def test_declared_type_must_match_content():
    assert _code(_png(), "application/pdf") == "UNSUPPORTED_FORMAT"
    assert _code(_pdf(), "image/png") == "UNSUPPORTED_FORMAT"


def test_size_and_page_limits():
    assert _code(_pdf(), "application/pdf", max_bytes=100) == "DOCUMENT_TOO_LARGE"
    assert _code(_pdf(pages=3), "application/pdf", max_pages=2) == "DOCUMENT_TOO_LARGE"


def test_truncated_pdf_is_corrupted():
    data = (FIXTURES / "native_text.pdf").read_bytes()
    assert _code(data[: len(data) // 4], "application/pdf") == "CORRUPTED_DOCUMENT"


def test_garbage_after_pdf_header_is_corrupted():
    assert _code(b"%PDF-1.7\n" + b"\x00garbage" * 50, "application/pdf") == "CORRUPTED_DOCUMENT"


def test_password_protected_pdf_is_rejected():
    encrypted = _pdf(encryption=fitz.PDF_ENCRYPT_AES_256, user_pw="secret", owner_pw="owner")
    assert _code(encrypted, "application/pdf") == "ENCRYPTED_DOCUMENT"


def test_truncated_png_is_corrupted():
    data = _png()
    assert _code(data[: len(data) // 2], "image/png") == "CORRUPTED_DOCUMENT"


def test_error_message_never_contains_file_content():
    secret = b"%PDF-1.7\nCONFIDENTIAL SALARY 150000" + b"\x00" * 64
    with pytest.raises(InvalidDocument) as info:
        validate_document_bytes(secret, "application/pdf", **LIMITS)
    assert "CONFIDENTIAL" not in str(info.value) and "150000" not in str(info.value)
