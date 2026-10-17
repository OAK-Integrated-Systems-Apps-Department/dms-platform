"""DocumentDeadline -- the per-document timeout that replaced Phase 1's one
uniform `ocr_max_pages`-sized ceiling (Phase 1 open item #1).

These are deliberately fast: tiny budgets, no real document, no pool
worker. What they pin is the arithmetic (the budget really is derived from
the page count, not from ocr_max_pages), the soft/hard split, and the one
race that can corrupt state -- the main thread settling at the same moment
the watchdog fires.
"""
from __future__ import annotations

import threading
import time

from ocr_worker.deadline import DocumentDeadline


def test_budget_scales_with_the_documents_real_page_count():
    """Uses the production defaults deliberately, so this also documents
    what a real document actually gets.

    The per-page allowance was raised 25 -> 45 on 2026-09-29: under a
    10-way concurrent burst a 35-page scan measured 29.2s/page, which
    already exceeded the old 25s allowance before any headroom, and it
    was killed at its budget for being slow rather than for hanging.
    """
    deadline = DocumentDeadline(base_s=60, per_page_s=45, hard_grace_s=30)

    assert deadline.budget_for(1) == 105
    assert deadline.budget_for(35) == 1635
    # Comfortably above the 1020.9s that document really took under load.
    assert deadline.budget_for(35) > 1020.9
    assert deadline.budget_for(50) == 2310


def test_not_armed_means_never_expired():
    """A document that failed before it could be opened (unsupported type,
    too large, corrupt) has no page count and therefore no budget -- it
    must not look expired to the between-pages check.
    """
    deadline = DocumentDeadline(base_s=0, per_page_s=0, hard_grace_s=0)

    assert deadline.remaining_s() is None
    assert deadline.expired() is False
    assert deadline.budget_s is None


def test_arm_then_expire_is_visible_to_the_soft_check():
    deadline = DocumentDeadline(base_s=0.01, per_page_s=0, hard_grace_s=60)

    assert deadline.arm(1) == 0.01
    assert deadline.expired() is False
    time.sleep(0.05)
    assert deadline.expired() is True
    assert deadline.budget_s == 0.01


def test_arming_twice_keeps_the_first_deadline():
    deadline = DocumentDeadline(base_s=10, per_page_s=1, hard_grace_s=60)

    assert deadline.arm(1) == 11
    assert deadline.arm(40) == 11
    assert deadline.budget_s == 11


def test_hard_watchdog_fires_after_budget_plus_grace():
    fired = threading.Event()
    seen: list[float] = []

    deadline = DocumentDeadline(
        base_s=0.02, per_page_s=0, hard_grace_s=0.02,
        on_expiry=lambda budget: (seen.append(budget), fired.set()),
    )
    deadline.arm(1)

    assert fired.wait(timeout=2.0), "hard watchdog never fired"
    assert seen == [0.02]


def test_settle_disarms_the_hard_watchdog():
    fired = threading.Event()
    deadline = DocumentDeadline(
        base_s=0.05, per_page_s=0, hard_grace_s=0.05, on_expiry=lambda _budget: fired.set()
    )
    deadline.arm(1)

    assert deadline.settle() is True
    assert not fired.wait(timeout=0.3), "watchdog fired after the document was already settled"


def test_settle_returns_false_once_the_watchdog_has_claimed_the_document():
    """The one race that matters: inference finishing at the same instant
    the watchdog fires. Exactly one side may write an outcome, and the
    watchdog -- which is about to os._exit() the process -- is the one that
    wins, so `settle()` must tell the main thread to stand down.
    """
    fired = threading.Event()
    deadline = DocumentDeadline(
        base_s=0.01, per_page_s=0, hard_grace_s=0.0, on_expiry=lambda _budget: fired.set()
    )
    deadline.arm(1)
    assert fired.wait(timeout=2.0)

    assert deadline.settle() is False


def test_settle_is_idempotent_only_the_first_call_wins():
    deadline = DocumentDeadline(base_s=10, per_page_s=1, hard_grace_s=10)
    deadline.arm(1)

    assert deadline.settle() is True
    assert deadline.settle() is False


def test_no_watchdog_thread_when_no_expiry_callback_is_given():
    """The synchronous zone/rubber-band paths hold a transaction open
    across their inference, so a watchdog writing from another thread
    would block on their own row lock -- they pass no callback and get the
    soft check only.
    """
    deadline = DocumentDeadline(base_s=0.01, per_page_s=0, hard_grace_s=0.0)
    deadline.arm(1)
    time.sleep(0.05)

    assert deadline.expired() is True  # soft check still works
    assert deadline._timer is None  # nothing was ever scheduled
