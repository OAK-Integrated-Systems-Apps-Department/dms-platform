"""Phase 2 Step 3 -- the false-rotation rate.

Correcting a rotated page is worth ~81 field-accuracy points; rotating an
upright one costs the same. So the number that decides whether this stage
is safe to have on by default is not "how many rotated pages were fixed"
but "how many upright pages were rotated by mistake".

This walks the whole synthetic corpus through the SAME routing the
pipeline uses -- a page with a native text layer is never rasterized and
never classified; only a page bound for PaddleOCR is -- and reports what
the classifier decided for every page it actually sees, against the
ground truth carried in the condition name.

    docker run --rm -v "$(pwd):/repo" \
        -v local_ocr_worker_models:/home/oakdms/.paddlex \
        -v local_ocr_worker_hf_cache:/home/oakdms/.cache/huggingface \
        -w /repo/backend/workers/ocr-worker ocr-worker-test:latest \
        python /repo/scripts/dev/step3_orientation_audit.py
"""
from __future__ import annotations

import collections
import os
import sys
import time
from pathlib import Path

os.environ.setdefault("PADDLE_PDX_ENABLE_MKLDNN_BYDEFAULT", "False")

WORKER = Path("/repo/backend/workers/ocr-worker")
sys.path.insert(0, str(WORKER / "src"))
sys.path.insert(0, str(WORKER))

from ocr_worker.config import Settings  # noqa: E402
from ocr_worker.layout import LayoutEngine  # noqa: E402
from ocr_worker.orientation import PaddleDocOrientationClassifier, decide  # noqa: E402

CORPUS = WORKER / "eval" / "corpus"

_MIME = {
    ".pdf": "application/pdf",
    ".png": "image/png",
    ".jpg": "image/jpeg",
    ".jpeg": "image/jpeg",
    ".tiff": "image/tiff",
    ".tif": "image/tiff",
}


def truth_for(condition: str) -> int:
    """The rotation the page really carries, from the condition name.
    Every condition other than `rotated_*` is upright -- including
    `skewed_2deg`, which is a small-angle skew and must NOT be quarter
    turned."""
    if condition.startswith("rotated_"):
        return int(condition.split("_")[1])
    return 0


def main() -> int:
    settings = Settings()
    engine = LayoutEngine()
    engine.warm_up()
    classifier = PaddleDocOrientationClassifier()
    classifier.warm_up()

    seen = collections.Counter()
    confusion = collections.Counter()   # (truth, predicted) over classified pages
    applied_wrongly = []
    missed = []
    native_pages = 0
    classified_pages = 0
    scores = []

    started = time.perf_counter()
    paths = sorted(p for p in CORPUS.iterdir() if p.is_file())
    for index, path in enumerate(paths, start=1):
        # doc ids are `<type>-<condition>-<replica>`; the condition is
        # everything between the first and last hyphen-delimited fields.
        parts = path.stem.split("-")
        condition = "-".join(parts[1:-1])
        truth = truth_for(condition)

        mime = _MIME.get(path.suffix.lower())
        if mime is None:
            continue
        with engine.load(path.read_bytes(), mime, path.name) as doc:
            for page_index in range(doc.page_count()):
                # Exactly pipeline._run_full_document's routing test.
                if doc.native_text_blocks_for_page(page_index + 1, settings.ocr_render_dpi):
                    native_pages += 1
                    continue
                page_image = doc.render_page(page_index, settings.ocr_render_dpi)
                _, decision = decide(
                    classifier,
                    page_image.image,
                    original_size=(page_image.width_px, page_image.height_px),
                    min_confidence=settings.ocr_orientation_min_confidence,
                )
                classified_pages += 1
                scores.append(decision.confidence)
                confusion[(truth, decision.angle if decision.applied else 0)] += 1
                seen[condition] += 1
                if truth == 0 and decision.applied:
                    applied_wrongly.append((path.name, page_index, decision.angle, decision.confidence))
                if truth != 0 and not (decision.applied and decision.angle == truth):
                    missed.append((path.name, page_index, truth, decision.angle, decision.confidence, decision.applied))
        if index % 20 == 0:
            print(f"  ... {index}/{len(paths)} documents", flush=True)

    upright = sum(n for (t, _), n in confusion.items() if t == 0)
    rotated = sum(n for (t, _), n in confusion.items() if t != 0)
    print()
    print(f"documents            {len(paths)}")
    print(f"pages, native-text   {native_pages}  (never rasterized, never classified)")
    print(f"pages, classified    {classified_pages}   in {time.perf_counter() - started:.1f}s")
    print()
    print(f"upright pages        {upright}")
    print(f"  wrongly rotated    {len(applied_wrongly)}  ({len(applied_wrongly) / upright:.2%} false-rotation rate)" if upright else "")
    print(f"rotated pages        {rotated}")
    print(f"  not corrected      {len(missed)}" if rotated else "")
    print()
    print("confusion (truth -> what was APPLIED, 0 = left alone):")
    for (truth, applied), n in sorted(confusion.items()):
        mark = "" if truth == applied else "   <-- wrong"
        print(f"  {truth:>3} -> {applied:>3}   x{n}{mark}")
    if applied_wrongly:
        print("\nupright pages that were rotated:")
        for row in applied_wrongly[:20]:
            print(f"  {row}")
    if missed:
        print("\nrotated pages that were not corrected:")
        for row in missed[:20]:
            print(f"  {row}")
    if scores:
        ordered = sorted(scores)
        print(
            f"\nconfidence: min {ordered[0]:.4f}  p50 {ordered[len(ordered) // 2]:.4f}  "
            f"max {ordered[-1]:.4f}  (spread {ordered[-1] - ordered[0]:.4f})"
        )
    return 0


if __name__ == "__main__":
    sys.exit(main())
