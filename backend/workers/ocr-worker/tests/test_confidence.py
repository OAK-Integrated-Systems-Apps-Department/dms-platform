from __future__ import annotations

import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "src"))

from ocr_worker.confidence import ConfidenceEvaluator  # noqa: E402


def test_default_threshold_is_95_percent_per_os_nfr_001():
    evaluator = ConfidenceEvaluator()
    assert evaluator.threshold == 0.95


def test_confidence_at_or_above_threshold_is_accepted():
    evaluator = ConfidenceEvaluator(threshold=0.95)
    decision = evaluator.evaluate(0.97)
    assert decision.flagged is False
    assert decision.accepted is True


def test_confidence_exactly_at_threshold_is_accepted():
    evaluator = ConfidenceEvaluator(threshold=0.95)
    decision = evaluator.evaluate(0.95)
    assert decision.flagged is False


def test_confidence_below_threshold_is_flagged_not_dropped():
    evaluator = ConfidenceEvaluator(threshold=0.95)
    decision = evaluator.evaluate(0.42)
    assert decision.flagged is True
    assert decision.accepted is False
    # The result is still returned/persisted by the pipeline -- flagging is
    # never the same as discarding the OCR output (OS-NFR-006).
    assert decision.mean_confidence == 0.42


def test_configurable_threshold_overrides_default():
    evaluator = ConfidenceEvaluator(threshold=0.80)
    assert evaluator.evaluate(0.85).flagged is False
    assert evaluator.evaluate(0.75).flagged is True


@pytest.mark.parametrize("bad_threshold", [-0.1, 1.1])
def test_threshold_must_be_a_valid_fraction(bad_threshold):
    with pytest.raises(ValueError):
        ConfidenceEvaluator(threshold=bad_threshold)
