#!/usr/bin/env python
"""Per-page timing and RSS for one document, on a quiet machine.

Written for `large_35page_scanned.pdf`, the one fixture that has never
completed inside its budget. Two very different explanations were open
after the 2026-09-29 runs and the concurrent numbers could not separate
them:

  * it is simply slow, and the budget was too tight (the case the
    per-page allowance was raised for); or
  * something grows page by page -- RSS, or per-page wall-clock -- so the
    document gets slower the further into it we are, which no budget
    fixes and which points at retained state.

The difference is visible only per page, and only without ten other
documents competing for the same two cores. Hence this.

    docker run --rm -v <repo>:/repo -v <fixtures>:/fixtures \\
        -e PYTHONPATH=/repo/backend/workers/ocr-worker/src \\
        local-ocr-worker:latest \\
        python /repo/scripts/dev/probe_large_scan.py --file /fixtures/large_35page_scanned.pdf

Reports, per page: wall-clock for render / orientation / OCR / quality,
and RSS after the page. Prints a first-half vs second-half comparison at
the end, which is the shape that answers the question.
"""
from __future__ import annotations

import argparse
import gc
import json
import time
from pathlib import Path


def rss_gib() -> float:
    """This process's resident set, from /proc. `ru_maxrss` is a HIGH
    WATER MARK and would only ever go up, which cannot show a leak being
    released -- the whole point here is the shape over time."""
    try:
        for line in Path("/proc/self/status").read_text().splitlines():
            if line.startswith("VmRSS:"):
                return int(line.split()[1]) / (1024.0 * 1024.0)
    except Exception:  # noqa: BLE001
        pass
    return float("nan")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--file", required=True)
    parser.add_argument("--report-json", default=None)
    parser.add_argument("--max-pages", type=int, default=0, help="0 = all")
    args = parser.parse_args()

    from ocr_worker.config import Settings
    from ocr_worker.layout import LayoutEngine
    from ocr_worker.ocr_engine import PaddleOcrEngine
    from ocr_worker.orientation import PaddleDocOrientationClassifier
    from ocr_worker import quality as quality_mod

    settings = Settings()
    print(f"render_dpi={settings.ocr_render_dpi} max_mp={settings.ocr_render_max_mp} "
          f"table_bound={settings.ocr_table_timeout_per_page_s}s "
          f"quality={settings.ocr_quality_enabled}", flush=True)

    engine = LayoutEngine(
        table_timeout_per_page_s=settings.ocr_table_timeout_per_page_s,
        normalize_rotation=settings.ocr_normalize_page_rotation,
    )
    t0 = time.monotonic()
    engine.warm_up()
    ocr = PaddleOcrEngine()
    orient = PaddleDocOrientationClassifier()
    print(f"warm_up+models {time.monotonic() - t0:6.1f}s  rss={rss_gib():.3f} GiB", flush=True)
    baseline_rss = rss_gib()

    data = Path(args.file).read_bytes()
    t0 = time.monotonic()
    doc = engine.load(data, "application/pdf", Path(args.file).name,
                      max_megapixels=settings.ocr_render_max_mp)
    load_s = time.monotonic() - t0
    page_count = doc.page_count()
    print(f"load (docling convert) {load_s:6.1f}s  pages={page_count}  rss={rss_gib():.3f} GiB\n",
          flush=True)

    limit = page_count if args.max_pages <= 0 else min(args.max_pages, page_count)
    print(f"{'pg':>3} {'render':>7} {'orient':>7} {'ocr':>7} {'qual':>6} {'total':>7} "
          f"{'rss GiB':>8} {'words':>6}", flush=True)

    rows = []
    for page_index in range(limit):
        t_page = time.monotonic()
        t = time.monotonic()
        page_image = doc.render_page(page_index, settings.ocr_render_dpi)
        render_s = time.monotonic() - t

        t = time.monotonic()
        from ocr_worker.orientation import decide as decide_orientation
        image, _decision = decide_orientation(
            orient, page_image.image,
            original_size=(page_image.width_px, page_image.height_px),
            min_confidence=settings.ocr_orientation_min_confidence,
        )
        orient_s = time.monotonic() - t

        t = time.monotonic()
        result = ocr.recognize(image, lang=settings.ocr_languages)
        ocr_s = time.monotonic() - t

        t = time.monotonic()
        geometry = page_image.geometry
        quality_mod.assess_page(
            image,
            width_pt=getattr(geometry, "width_pt", None),
            height_pt=getattr(geometry, "height_pt", None),
            native_pixel_size=doc.native_pixel_size_for_page(page_index),
            min_dpi=settings.ocr_quality_min_dpi,
            min_blur=settings.ocr_quality_min_blur,
            min_contrast=settings.ocr_quality_min_contrast,
        )
        quality_s = time.monotonic() - t

        total_s = time.monotonic() - t_page
        rss = rss_gib()
        rows.append({
            "page": page_index + 1, "render_s": round(render_s, 2),
            "orient_s": round(orient_s, 2), "ocr_s": round(ocr_s, 2),
            "quality_s": round(quality_s, 3), "total_s": round(total_s, 2),
            "rss_gib": round(rss, 3), "words": len(result.words),
        })
        print(f"{page_index + 1:>3} {render_s:7.2f} {orient_s:7.2f} {ocr_s:7.2f} "
              f"{quality_s:6.3f} {total_s:7.2f} {rss:8.3f} {len(result.words):6}", flush=True)

    doc.close() if hasattr(doc, "close") else None
    gc.collect()

    totals = [r["total_s"] for r in rows]
    half = len(rows) // 2
    first, second = totals[:half], totals[half:]
    summary = {
        "file": args.file,
        "pages": len(rows),
        "load_s": round(load_s, 1),
        "baseline_rss_gib": round(baseline_rss, 3),
        "total_page_s": round(sum(totals), 1),
        "mean_page_s": round(sum(totals) / len(totals), 2) if totals else None,
        "first_half_mean_s": round(sum(first) / len(first), 2) if first else None,
        "second_half_mean_s": round(sum(second) / len(second), 2) if second else None,
        "rss_after_page_1_gib": rows[0]["rss_gib"] if rows else None,
        "rss_after_last_page_gib": rows[-1]["rss_gib"] if rows else None,
        "rss_growth_gib": round(rows[-1]["rss_gib"] - rows[0]["rss_gib"], 3) if rows else None,
        "rss_after_gc_gib": round(rss_gib(), 3),
        "pages": rows,
    }
    print("\n" + "=" * 70, flush=True)
    print(f"load {summary['load_s']}s + pages {summary['total_page_s']}s "
          f"= {round(summary['load_s'] + summary['total_page_s'], 1)}s total", flush=True)
    print(f"mean/page {summary['mean_page_s']}s   "
          f"first half {summary['first_half_mean_s']}s vs second half {summary['second_half_mean_s']}s",
          flush=True)
    print(f"RSS after page 1 {summary['rss_after_page_1_gib']} GiB -> "
          f"after last {summary['rss_after_last_page_gib']} GiB "
          f"(growth {summary['rss_growth_gib']} GiB, after gc {summary['rss_after_gc_gib']})", flush=True)

    if args.report_json:
        Path(args.report_json).write_text(json.dumps(summary, indent=2))
        print(f"wrote {args.report_json}", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
