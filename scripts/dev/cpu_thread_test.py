"""Standalone diagnostic: compares PaddleOCR throughput under today's
default thread config (PADDLE_PDX_CPU_NUM_THREADS unset -> PaddleX's own
default of 10) vs a capped config (threads_per_process = cpu_count //
concurrency), running N separate PROCESSES concurrently -- matching how
Phase 1's real process pool would behave. Does NOT touch pipeline.py,
ocr_engine.py, or the running oakdms-ocr-worker service at all; this is a
standalone script run inside the container for measurement only.
"""
from __future__ import annotations

import multiprocessing as mp
import os
import sys
import time


def _worker(image_path: str, thread_count: int | None, result_queue: mp.Queue) -> None:
    if thread_count is not None:
        os.environ["PADDLE_PDX_CPU_NUM_THREADS"] = str(thread_count)
        os.environ["OMP_NUM_THREADS"] = str(thread_count)
        os.environ["MKL_NUM_THREADS"] = str(thread_count)
    os.environ.setdefault("PADDLE_PDX_ENABLE_MKLDNN_BYDEFAULT", "False")

    import numpy as np
    from paddleocr import PaddleOCR
    from PIL import Image

    t0 = time.time()
    engine = PaddleOCR(
        use_doc_orientation_classify=False,
        use_doc_unwarping=False,
        use_textline_orientation=True,
        text_recognition_batch_size=8,
        textline_orientation_batch_size=8,
        lang="en",
    )
    construct_s = time.time() - t0

    image = Image.open(image_path).convert("RGB")
    t1 = time.time()
    result = engine.predict(np.array(image))
    list(result)  # force full evaluation
    predict_s = time.time() - t1

    result_queue.put({"pid": os.getpid(), "construct_s": construct_s, "predict_s": predict_s})


def run_concurrent(image_path: str, concurrency: int, thread_count: int | None, label: str) -> None:
    ctx = mp.get_context("spawn")
    result_queue = ctx.Queue()
    procs = []
    t_start = time.time()
    for _ in range(concurrency):
        p = ctx.Process(target=_worker, args=(image_path, thread_count, result_queue))
        p.start()
        procs.append(p)
    for p in procs:
        p.join()
    total_s = time.time() - t_start

    results = [result_queue.get() for _ in range(concurrency)]
    predicts = [r["predict_s"] for r in results]
    print(f"\n=== {label} (concurrency={concurrency}, thread_count={thread_count}) ===")
    print(f"total_wall_clock_s: {total_s:.1f}")
    print(f"per_process_predict_s: {[round(p, 1) for p in predicts]}")
    print(f"mean_predict_s: {sum(predicts)/len(predicts):.1f}")
    print(f"max_predict_s: {max(predicts):.1f}")


if __name__ == "__main__":
    image_path = sys.argv[1] if len(sys.argv) > 1 else "/tmp/fixtures-src/scanned_image.png"
    concurrency = 4
    cpu_count = os.cpu_count() or 4
    capped_threads = max(1, cpu_count // concurrency)

    print(f"cpu_count={cpu_count}, concurrency={concurrency}, capped_threads_per_process={capped_threads}")

    run_concurrent(image_path, concurrency, None, "BASELINE (today's default, unset PADDLE_PDX_CPU_NUM_THREADS -> 10)")
    run_concurrent(image_path, concurrency, capped_threads, "CAPPED (threads_per_process = cpu_count // concurrency)")
