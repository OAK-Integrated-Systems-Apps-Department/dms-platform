"""Phase 2 Step 4 research -- what does resolution actually buy, and cost?

Step 4 is "300 DPI rendering with tiling". Before writing any tiling
code, three things have to be measured, because the answer decides
whether the step is worth doing at all:

1. **Does accuracy improve with resolution?** The corpus was built with
   6pt fine print precisely so this question has an answer (see Step 1).
2. **What does it cost in time?** PaddleX's detection config is
   `limit_type: min`, `limit_side_len: 64`, `max_side_limit: 4000` -- so
   detection never downscales its input. Pixels go up 4.4x from A4@137
   to A4@300, and det/rec time goes up with them.
3. **What does it cost in memory?** `_MAX_RENDER_DIMENSION_PX = 1600`
   exists because a correctly-capped ~3000x4000 input still OOM-killed a
   worker (layout.py's own docstring). That needs re-testing, not
   assuming, since it is the number tiling has to be sized against.

MEASURED RESULT (2026-09-28, one A4 invoice, 8.5 GiB limit, 1 process):

    dpi  raster       MP    s     peakRSS   CER     chars
    137  1133x1603   1.8   53.5   2.059 GiB 0.0164   974
    200  1654x2340   3.9   85.5   3.488 GiB 0.0164   974
    250  2067x2925   6.0   --     OOM-KILLED (exit 137, OOMKilled=true)

So: memory grows ~0.7 GiB per megapixel on top of a ~0.8 GiB warm floor,
untiled 250 DPI is not survivable at the production mem_limit even with
ONE process (production runs two), and 137 -> 200 DPI changed neither the
character error rate nor the character count -- byte-identical output for
1.6x the time and 1.7x the memory.

This renders ONE page at a series of scales, bypassing the render cap,
and runs the real PaddleOCR on each -- no tiling, deliberately, so the
untiled cost is visible. Peak RSS is the process's own high-water mark
(VmHWM), reset is impossible so it is read as a running maximum.

    docker run --rm --memory 8704m -v "$(pwd):/repo" \
        -v local_ocr_worker_models:/home/oakdms/.paddlex \
        -v local_ocr_worker_hf_cache:/home/oakdms/.cache/huggingface \
        -w /repo/backend/workers/ocr-worker ocr-worker-test:latest \
        python /repo/scripts/dev/step4_resolution_probe.py
"""
from __future__ import annotations

import json
import os
import sys
import time
from pathlib import Path

os.environ.setdefault("PADDLE_PDX_ENABLE_MKLDNN_BYDEFAULT", "False")

WORKER = Path("/repo/backend/workers/ocr-worker")
sys.path.insert(0, str(WORKER / "src"))
sys.path.insert(0, str(WORKER))

import fitz  # noqa: E402
from PIL import Image  # noqa: E402

from ocr_worker.ocr_engine import PaddleOcrEngine  # noqa: E402
from eval import metrics  # noqa: E402

CORPUS = WORKER / "eval" / "corpus"
LABELS = WORKER / "eval" / "labels"

# The conditions where resolution should matter most and least, so the
# result is a curve rather than one number:
#   clean_scan_300 -- a real 300-DPI scan; fine print is there to be read
#   low_dpi_scan_72 -- there is no detail to recover; more pixels should
#                      buy nothing, and that is the control
DOCS = ["invoice-clean_scan_300-00", "invoice-low_dpi_scan_72-00"]

# Effective DPI on the long side of an A4 page. 137 is what the 1600px
# cap actually produces today (see test_coordinates.py).
TARGET_DPI = [137, 200, 250, 300]


def peak_rss_gib() -> float:
    with open("/proc/self/status") as fh:
        for line in fh:
            if line.startswith("VmHWM:"):
                return int(line.split()[1]) / 1024 / 1024
    return -1.0


def find(doc_id: str) -> Path:
    for path in CORPUS.iterdir():
        if path.stem == doc_id:
            return path
    raise SystemExit(f"no corpus file for {doc_id}")


def render_at(path: Path, dpi: int):
    """Render page 0 at a true DPI, with NO cap -- the thing Step 4 is
    considering doing."""
    doc = fitz.open(path)
    page = doc[0]
    zoom = dpi / 72.0
    pixmap = page.get_pixmap(matrix=fitz.Matrix(zoom, zoom))
    image = Image.frombytes("RGB", (pixmap.width, pixmap.height), pixmap.samples)
    doc.close()
    return image


def main() -> int:
    engine = PaddleOcrEngine()
    engine.warm_up()
    print(f"baseline RSS after warm-up: {peak_rss_gib():.3f} GiB\n")

    print(f"{'document':28} {'dpi':>4} {'raster':>12} {'MP':>6} {'s':>7} {'peakRSS':>8} {'CER':>7} {'chars':>6}")
    for doc_id in DOCS:
        path = find(doc_id)
        reference = json.loads((LABELS / f"{doc_id}.json").read_text())["reference_text"]
        for dpi in TARGET_DPI:
            image = render_at(path, dpi)
            megapixels = image.size[0] * image.size[1] / 1e6
            started = time.perf_counter()
            try:
                result = engine.recognize(image, lang="en")
                elapsed = time.perf_counter() - started
                cer = metrics.character_error_rate(reference, result.text)
                chars = len(result.text)
            except Exception as exc:  # noqa: BLE001
                elapsed = time.perf_counter() - started
                print(f"{doc_id:28} {dpi:>4} {str(image.size):>12} {megapixels:6.1f} {elapsed:7.1f} "
                      f"{peak_rss_gib():8.3f}   FAILED {type(exc).__name__}: {exc}")
                continue
            print(f"{doc_id:28} {dpi:>4} {str(image.size):>12} {megapixels:6.1f} {elapsed:7.1f} "
                  f"{peak_rss_gib():8.3f} {cer:7.4f} {chars:6d}", flush=True)
            del image
    return 0


if __name__ == "__main__":
    sys.exit(main())
