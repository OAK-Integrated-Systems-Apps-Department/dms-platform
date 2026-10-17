"""Peak-memory measurement for an eval run (section 5's "peak RSS").

Reads the *container's own* cgroup v2 accounting rather than
`psutil`/`getrusage`, for two reasons. It matches how every memory number
in this project has been measured so far (`docker stats`, which reads the
same counters), so the figures are comparable with the Phase 1 6.89 GiB
baseline. And it counts page cache and every child process, which an
in-process RSS read would miss -- Docling and PaddleOCR both spawn and
mmap heavily.

**What this number is, and is not.** The eval harness drives the pipeline
in a single process, so this measures one inference stream. Production
runs `OCR_WORKER_PROCESSES=2`, each with its own full model set, so the
production peak is roughly this plus another per-process model footprint.
Use this to compare variants against each other and to catch a
per-inference blowup early; use a live run with the real pool for the
absolute number that gets compared against `mem_limit`.
"""

from __future__ import annotations

import threading
import time
from dataclasses import dataclass
from pathlib import Path

_CGROUP = Path("/sys/fs/cgroup")
GIB = 1024 ** 3


def _read_int(name: str) -> int | None:
    try:
        text = (_CGROUP / name).read_text().strip()
    except OSError:
        return None
    if text in ("", "max"):
        return None
    try:
        return int(text)
    except ValueError:
        return None


def cgroup_limit_bytes() -> int | None:
    """The container's `mem_limit`, or None when unbounded / not in one."""
    return _read_int("memory.max")


def reset_cgroup_peak() -> bool:
    """Try to zero `memory.peak` so the run's peak excludes model warmup.

    Only supported on newer kernels and only when the file is writable;
    the caller must treat False as "the peak below includes everything
    since container start" rather than as an error. Silently reporting a
    warmup-inclusive peak as though it were the run's peak would be the
    real failure.
    """
    try:
        (_CGROUP / "memory.peak").write_text("0")
        return True
    except OSError:
        return False


@dataclass
class MemorySample:
    at: float
    current_bytes: int


class MemorySampler:
    """Polls `memory.current` on a daemon thread for the run's duration.

    Sampling as well as reading `memory.peak` is deliberate redundancy:
    `memory.peak` cannot always be reset (see above), and the sample
    series is what makes a median/p95 possible and lets a report show
    *when* memory climbed, not just how far.
    """

    def __init__(self, interval_s: float = 2.0):
        self._interval_s = interval_s
        self._samples: list[MemorySample] = []
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None
        self.peak_reset = False
        self.limit_bytes = cgroup_limit_bytes()

    def __enter__(self) -> "MemorySampler":
        self.peak_reset = reset_cgroup_peak()
        self._thread = threading.Thread(target=self._loop, name="eval-rss", daemon=True)
        self._thread.start()
        return self

    def __exit__(self, *_exc) -> None:
        self._stop.set()
        if self._thread is not None:
            self._thread.join(timeout=self._interval_s * 2)

    def _loop(self) -> None:
        while not self._stop.is_set():
            value = _read_int("memory.current")
            if value is not None:
                self._samples.append(MemorySample(time.monotonic(), value))
            self._stop.wait(self._interval_s)

    # ------------------------------------------------------------------
    def summary(self) -> dict:
        from .metrics import percentile

        values = [s.current_bytes for s in self._samples]
        cgroup_peak = _read_int("memory.peak")
        sampled_peak = max(values) if values else 0
        # The sampler can miss a spike between two polls; the cgroup's own
        # peak counter cannot. Report the larger, and say which it was.
        peak = max(sampled_peak, cgroup_peak or 0)

        return {
            "samples": len(values),
            "interval_s": self._interval_s,
            "peak_source": "cgroup.memory.peak" if (cgroup_peak or 0) >= sampled_peak else "sampled",
            "peak_reset_supported": self.peak_reset,
            "peak_bytes": peak,
            "peak_gib": round(peak / GIB, 3),
            "sampled_peak_gib": round(sampled_peak / GIB, 3),
            "cgroup_peak_gib": round((cgroup_peak or 0) / GIB, 3),
            "p95_gib": round(percentile(values, 95) / GIB, 3) if values else 0.0,
            "median_gib": round(percentile(values, 50) / GIB, 3) if values else 0.0,
            "limit_gib": round(self.limit_bytes / GIB, 3) if self.limit_bytes else None,
            "note": (
                "single-process measurement; production runs "
                "OCR_WORKER_PROCESSES=2, each with its own model set"
            ),
        }
