"""Builds docs/ocr/failure-analysis.md's required reproduction batch from the
3 existing real fixtures in backend/workers/ocr-worker/tests/fixtures/ (phone
photo, HEIC, multi-page TIFF incl. CCITT G4, rotated scans, large 30+ page
PDFs, password-protected/corrupt PDFs, DOCX with a table).

Needs fitz/PIL/pypdf/pillow_heif/docx -- either run inside the ocr-worker
container after `pip install pypdf pillow-heif` (it already has fitz/PIL/
python-docx), or in the same throwaway container smoke_test_ocr.py's own
docstring shows for running scripts against the compose network:

    docker exec <ocr-worker container> pip install --quiet pypdf pillow-heif
    docker cp backend/workers/ocr-worker/tests/fixtures <container>:/tmp/fixtures-src
    docker cp scripts/dev/build_fixtures.py <container>:/tmp/build_fixtures.py
    docker exec <ocr-worker container> python /tmp/build_fixtures.py \\
        --src-dir /tmp/fixtures-src --out-dir /tmp/repro-fixtures
    docker cp <container>:/tmp/repro-fixtures ./repro-fixtures

Output fixtures then feed repro_batch.py's --fixtures-dir.
"""
from __future__ import annotations

import argparse
import io
from pathlib import Path

import fitz
from PIL import Image


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--src-dir", type=Path, default=Path("/tmp/fixtures-src"),
                         help="directory containing native_text.pdf/scanned_image_only.pdf/scanned_image.png")
    parser.add_argument("--out-dir", type=Path, default=Path("/tmp/repro-fixtures"))
    return parser.parse_args()


args = parse_args()
SRC = args.src_dir
OUT = args.out_dir
OUT.mkdir(parents=True, exist_ok=True)

png_bytes = (SRC / "scanned_image.png").read_bytes()
native_pdf_bytes = (SRC / "native_text.pdf").read_bytes()
scanned_pdf_bytes = (SRC / "scanned_image_only.pdf").read_bytes()

# --- phone photo JPEG (synthetic) ---
img = Image.open(io.BytesIO(png_bytes)).convert("RGB")
img.save(OUT / "phone_photo_small.jpg", quality=85)
big = img.resize((2300, 3000))
big.save(OUT / "phone_photo_large.jpg", quality=85)
print("phone_photo_small.jpg", "phone_photo_large.jpg")

# --- iPhone HEIC (synthetic) ---
import pillow_heif  # noqa: E402

heif = pillow_heif.from_pillow(img)
heif.save(OUT / "phone_photo.heic")
print("phone_photo.heic")

# --- multi-page TIFF ---
pages = [img, img.rotate(5, expand=True).convert("RGB"), img.transpose(Image.FLIP_LEFT_RIGHT)]
pages[0].save(OUT / "multipage.tiff", save_all=True, append_images=pages[1:])
print("multipage.tiff")

# CCITT Group 4 (1-bit) multi-page TIFF
bw_pages = [p.convert("1") for p in pages]
bw_pages[0].save(OUT / "multipage_g4.tiff", save_all=True, append_images=bw_pages[1:], compression="group4")
print("multipage_g4.tiff")

# --- rotated scans (raster) ---
for deg in (90, 180, 270):
    img.rotate(deg, expand=True).save(OUT / f"rotated_{deg}.png")
print("rotated_90/180/270.png")

# --- rotated scan (PDF page-rotation metadata, distinct code path) ---
doc = fitz.open(stream=native_pdf_bytes, filetype="pdf")
doc[0].set_rotation(90)
doc.save(OUT / "rotated_native_text.pdf")
doc.close()
print("rotated_native_text.pdf")

# --- large 30+ page PDF (native-text, repeated) ---
from pypdf import PdfReader, PdfWriter  # noqa: E402

reader = PdfReader(io.BytesIO(native_pdf_bytes))
writer = PdfWriter()
for _ in range(35):
    writer.append(reader)
with open(OUT / "large_35page_native.pdf", "wb") as f:
    writer.write(f)
print("large_35page_native.pdf")

# large scanned-equivalent (image-only page repeated) -- the one most likely
# to reproduce a real crash/OOM given each page embeds a large raster.
reader2 = PdfReader(io.BytesIO(scanned_pdf_bytes))
writer2 = PdfWriter()
for _ in range(35):
    writer2.append(reader2)
with open(OUT / "large_35page_scanned.pdf", "wb") as f:
    writer2.write(f)
print("large_35page_scanned.pdf")

# --- password-protected PDF ---
reader3 = PdfReader(io.BytesIO(native_pdf_bytes))
writer3 = PdfWriter()
writer3.append(reader3)
writer3.encrypt(user_password="test123", algorithm="AES-256")
with open(OUT / "password_protected.pdf", "wb") as f:
    writer3.write(f)
print("password_protected.pdf")

# --- corrupt PDF, three corruption classes ---
# Real finding (docs/ocr/failure-analysis.md #2.9): PyMuPDF's parser is
# lenient enough that only the most severe of these three actually fails to
# open -- the other two are silently repaired/tolerated. All three are kept
# so that negative result stays reproducible, not just asserted.
(OUT / "corrupt_truncated.pdf").write_bytes(native_pdf_bytes[: len(native_pdf_bytes) // 2])

mutated = bytearray(native_pdf_bytes)
mid = len(mutated) // 2
for i in range(mid, mid + 30):
    mutated[i] ^= 0xFF
(OUT / "corrupt_mutated.pdf").write_bytes(bytes(mutated))  # trailer/xref region flipped -- still opens fine

bad_header = bytearray(native_pdf_bytes)
bad_header[0:8] = b"NOTAPDF!"
(OUT / "corrupt_bad_header.pdf").write_bytes(bytes(bad_header))  # magic bytes replaced -- still opens fine

(OUT / "corrupt_tiny_fragment.pdf").write_bytes(native_pdf_bytes[:200])  # no valid xref/trailer at all -- this one genuinely fails

print("corrupt_truncated.pdf", "corrupt_mutated.pdf", "corrupt_bad_header.pdf", "corrupt_tiny_fragment.pdf")

# --- DOCX with a table ---
from docx import Document  # noqa: E402

d = Document()
d.add_heading("Repro Test Document", level=1)
d.add_paragraph("This is a synthetic docx built for OCR intake reproduction testing.")
table = d.add_table(rows=2, cols=2)
table.cell(0, 0).text = "DOCUMENT REF"
table.cell(0, 1).text = "REPRO-0001"
table.cell(1, 0).text = "DATE"
table.cell(1, 1).text = "2026-09-23"
d.save(OUT / "sample.docx")
print("sample.docx")

print("\nAll fixtures built under", OUT)
for p in sorted(OUT.iterdir()):
    print(f"  {p.name}\t{p.stat().st_size} bytes")
