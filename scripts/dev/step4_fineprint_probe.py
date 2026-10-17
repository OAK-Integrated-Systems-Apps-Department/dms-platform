"""Phase 2 Step 4 research -- does resolution help the small print?

The whole-page probe (`step4_resolution_probe.py`) found byte-identical
output at 137 and 200 DPI, and an OOM kill at 250. But whole-page CER is
a blunt instrument for this question: the 6pt fine print is a few hundred
characters out of ~1000, so a real improvement there could be diluted,
and a wrong conclusion here would either waste the tiling work or skip a
change that mattered.

So this asks the question directly, on the region that is supposed to be
resolution-sensitive. It crops ONLY the fine-print band -- which fits in
memory untiled even at 600 DPI, so the answer costs minutes rather than a
tiling implementation -- and compares what comes back against the exact
strings the corpus generator drew there.

The corpus draws, at 6pt (see eval/corpus.py `_FINE_PRINT_TERMS` and
`_FINE_PRINT_FIELD`):
  - one real FIELD (`cost_centre` on an invoice), and
  - four lines of boilerplate whose only job is to be hard to read.

6pt is ~11px tall at the ~137 DPI the renderer actually produces today,
and ~25px at a true 300. If more pixels ever help this engine, they help
here.

MEASURED RESULT (2026-09-28, invoice-clean_scan_300-00, 8.5 GiB, 1 proc):

    dpi   crop          MP     s     fineCER  field
    137   1133x577     0.7    13.0    0.0000  OK (cost_centre = CC-4450)
    200   1654x843     1.4    19.4    0.0000  OK
    300   2480x1264    3.1    39.7    0.0000  OK
    400   3306x1685    5.6     --     OOM-KILLED (exit 137, OOMKilled=true)

The engine already reads the 6pt print with **zero** character errors at
today's resolution, so there is no headroom for more pixels to recover:
137 -> 300 DPI is 3x the time on the same region for an identical result.
And the ~6 MP memory ceiling is a property of one inference call, not of
whole pages -- a 5.6 MP *crop* dies exactly as a 6.0 MP page does, which
is the number any tiling scheme has to size its tiles against.

Caveat that this experiment cannot settle: the corpus draws vector text
and degrades it with a model we wrote. Real 6pt print on a real scanner
is worse than that. This says resolution buys nothing HERE, which vetoes
the change on the evidence available -- it does not prove it would buy
nothing on a real document.

    docker run --rm --memory 8704m -v "$(pwd):/repo" \
        -v local_ocr_worker_models:/home/oakdms/.paddlex \
        -v local_ocr_worker_hf_cache:/home/oakdms/.cache/huggingface \
        -w /repo/backend/workers/ocr-worker ocr-worker-test:latest \
        python /repo/scripts/dev/step4_fineprint_probe.py
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
from eval.corpus import _FINE_PRINT_TERMS  # noqa: E402

CORPUS = WORKER / "eval" / "corpus"
LABELS = WORKER / "eval" / "labels"

# The conditions that bracket the question: a source with real 300-DPI
# detail to recover, and one with none (a 72-DPI scan -- more pixels can
# only magnify blur, and if accuracy "improves" there something is wrong
# with the experiment).
DOCS = ["invoice-clean_scan_300-00", "invoice-low_dpi_scan_72-00"]
TARGET_DPI = [137, 200, 300, 400, 600]

# The fine print sits in the lower half of the page, drawn from y~380pt
# down on an A4 (842pt). Cropping as a FRACTION of the page keeps the same
# physical region at every scale, which is the whole point.
BAND_TOP, BAND_BOTTOM = 0.44, 0.80


def find(doc_id: str) -> Path:
    for path in CORPUS.iterdir():
        if path.stem == doc_id:
            return path
    raise SystemExit(f"no corpus file for {doc_id}")


def crop_band_at(path: Path, dpi: int):
    doc = fitz.open(path)
    page = doc[0]
    zoom = dpi / 72.0
    pixmap = page.get_pixmap(matrix=fitz.Matrix(zoom, zoom))
    image = Image.frombytes("RGB", (pixmap.width, pixmap.height), pixmap.samples)
    doc.close()
    height = image.size[1]
    return image.crop((0, int(height * BAND_TOP), image.size[0], int(height * BAND_BOTTOM)))


def main() -> int:
    engine = PaddleOcrEngine()
    engine.warm_up()

    for doc_id in DOCS:
        path = find(doc_id)
        label = json.loads((LABELS / f"{doc_id}.json").read_text())
        fine_field_value = label["fields"].get("cost_centre")
        reference = "\n".join(_FINE_PRINT_TERMS)

        print(f"\n=== {doc_id} ===")
        print(f"expected fine-print field cost_centre = {fine_field_value!r}")
        print(f"{'dpi':>4} {'crop':>12} {'MP':>5} {'s':>6} {'fineCER':>8} {'field':>6}  text")
        for dpi in TARGET_DPI:
            band = crop_band_at(path, dpi)
            megapixels = band.size[0] * band.size[1] / 1e6
            started = time.perf_counter()
            result = engine.recognize(band, lang="en")
            elapsed = time.perf_counter() - started

            got = result.text
            cer = metrics.character_error_rate(reference, _only_boilerplate(got))
            field_ok = bool(fine_field_value) and fine_field_value in got.replace(" ", "")
            first = " | ".join(line for line in got.splitlines() if line.strip())[:110]
            print(f"{dpi:>4} {str(band.size):>12} {megapixels:5.1f} {elapsed:6.1f} "
                  f"{cer:8.4f} {'OK' if field_ok else 'MISS':>6}  {first}", flush=True)
            del band
    return 0


def _only_boilerplate(text: str) -> str:
    """Keep the lines that are plausibly the 6pt boilerplate, so the CER
    is about those four lines and not about whatever else the crop caught
    (the line-items table, the terms line at 8pt)."""
    wanted = []
    for line in text.splitlines():
        stripped = line.strip()
        if len(stripped) > 40 and any(
            token in stripped for token in ("Remittance", "Registered", "Late payment", "Commercial Debts")
        ):
            wanted.append(stripped)
    return "\n".join(wanted)


if __name__ == "__main__":
    sys.exit(main())
