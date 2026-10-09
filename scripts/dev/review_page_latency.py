#!/usr/bin/env python3
"""Measure review-screen page latency on a 50-page PDF.

WHAT THIS MEASURES
------------------
The thing a reviewer actually waits for: the round trip of

    GET /api/v1/documents/{documentId}/page-image?page=N

which is frontend -> dms-core (auth, folder access, cache) -> ocr-worker
(render) -> dms-core -> frontend. It reports the cold number (nothing cached)
and the warm number (served from `PageImageCache`) separately, because they
are different experiences and averaging them hides both.

WHY A SCRIPT AND NOT A TEST
---------------------------
A page render is ocr-worker doing real work with real models. There is no
Testcontainers harness in this repo and the model weights are multi-gigabyte,
so this cannot live in the unit or integration suite - it needs a running
stack. Putting it in a test would mean a test that is skipped everywhere,
which is a worse answer than a script that says what it needs.

WHAT IT NEEDS
-------------
  * the local stack up: postgres, rabbitmq, seaweedfs, clamav, dms-core,
    ocr-worker (with its model caches warm - the FIRST render after a worker
    restart includes model load and is not representative)
  * a 50-page PDF already uploaded, OCR'd and visible to the token's user
  * DMS_BASE_URL and DMS_TOKEN (a Keycloak access token for that user)

    DMS_BASE_URL=http://localhost:8080 \
    DMS_TOKEN=... \
    python scripts/dev/review_page_latency.py <documentId>

NOTE ON THE DATABASE
--------------------
This script only reads over HTTP. It does not create or migrate a database.
Point dms-core at a throwaway database if you do not want to touch the
development one.
"""

from __future__ import annotations

import os
import statistics
import sys
import time
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

PAGES = 50
BASE_URL = os.environ.get("DMS_BASE_URL", "http://localhost:8080")
TOKEN = os.environ.get("DMS_TOKEN", "")


def fetch_page(document_id: str, page: int) -> tuple[float, int]:
    """Returns (seconds, bytes). Raises on anything but a 200."""
    url = f"{BASE_URL}/api/v1/documents/{document_id}/page-image?page={page}"
    request = Request(url, headers={"Authorization": f"Bearer {TOKEN}"})
    started = time.perf_counter()
    with urlopen(request, timeout=300) as response:
        body = response.read()
    return time.perf_counter() - started, len(body)


def summarise(label: str, samples: list[float]) -> None:
    ordered = sorted(samples)
    # p95 by nearest-rank. With 50 samples that is the 48th, which is a real
    # measurement rather than an interpolation between two.
    p95 = ordered[min(len(ordered) - 1, int(round(0.95 * len(ordered))) - 1)]
    print(
        f"{label:<22} n={len(samples):<4} "
        f"min={min(ordered) * 1000:7.0f}ms  "
        f"median={statistics.median(ordered) * 1000:7.0f}ms  "
        f"p95={p95 * 1000:7.0f}ms  "
        f"max={max(ordered) * 1000:7.0f}ms"
    )


def main() -> int:
    if len(sys.argv) != 2:
        print(__doc__)
        return 2
    if not TOKEN:
        print("DMS_TOKEN is not set - this needs a real access token.", file=sys.stderr)
        return 2
    document_id = sys.argv[1]

    print(f"Measuring {PAGES} pages of document {document_id} at {BASE_URL}\n")

    cold: list[float] = []
    total_bytes = 0
    for page in range(1, PAGES + 1):
        try:
            seconds, size = fetch_page(document_id, page)
        except HTTPError as exc:
            print(f"page {page}: HTTP {exc.code} - {exc.read()[:200]!r}", file=sys.stderr)
            return 1
        except URLError as exc:
            print(f"page {page}: {exc.reason} (is the stack up?)", file=sys.stderr)
            return 1
        cold.append(seconds)
        total_bytes += size
        print(f"  page {page:>3}  {seconds * 1000:7.0f}ms  {size / 1024:7.1f} KiB")

    print()
    # Second pass over the same pages: every one is now in PageImageCache, so
    # this is dms-core's own overhead - auth, folder access, the cache lookup
    # and writing the bytes out - with the render removed.
    warm = [fetch_page(document_id, page)[0] for page in range(1, PAGES + 1)]

    summarise("cold (rendered)", cold)
    summarise("warm (cached)", warm)
    print(
        f"\ntotal cold wall clock: {sum(cold):.1f}s"
        f"   images: {total_bytes / 1024 / 1024:.1f} MiB"
        f"   cache budget: dms.ocr.page-image-cache-bytes (default 64 MiB)"
    )
    if total_bytes > 64 * 1024 * 1024:
        print(
            "NOTE: this document's pages exceed the default cache budget, so the "
            "warm numbers above include some re-renders. Raise "
            "dms.ocr.page-image-cache-bytes to measure a true warm path."
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
