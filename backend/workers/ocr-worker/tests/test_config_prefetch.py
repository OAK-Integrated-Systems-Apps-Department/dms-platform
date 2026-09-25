"""`Settings.effective_prefetch_count`.

Phase 1 ran with a flat `prefetch_count=4` against `ocr_worker_processes=2`
(confirmed live in docs/ocr/phase1-verification.md's "Concurrency behavior":
RabbitMQ held exactly 4 unacked throughout). Two of those four were
deliveries this worker had no free pool process to start -- held, invisible
to any other consumer, for as long as the two running documents took (up to
~1000s for a 35-page scan in that run).
"""
from __future__ import annotations

from ocr_worker.config import Settings


def test_prefetch_defaults_to_the_pool_size():
    assert Settings(ocr_worker_processes=2).effective_prefetch_count == 2
    assert Settings(ocr_worker_processes=6).effective_prefetch_count == 6


def test_an_explicit_prefetch_count_still_wins():
    """Operators can still decouple the two deliberately (e.g. to hold a
    small buffer against queue latency); the tie is a default, not a rule.
    """
    assert Settings(ocr_worker_processes=2, prefetch_count=8).effective_prefetch_count == 8
    assert Settings(ocr_worker_processes=4, prefetch_count=1).effective_prefetch_count == 1
