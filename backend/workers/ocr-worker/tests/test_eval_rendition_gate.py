"""The eval must SEE a searchable-PDF failure (Phase 4, addition 1).

The image bug -- `show_pdf_page` rejecting a non-PDF source, so every
image upload silently produced no rendition -- passed the whole harness
untouched. Accuracy, CER and latency are all unaffected by a missing
rendition, so nothing in the report moved. The flag existed; nothing
read it.

These pin that it is read now, and that any failure fails the gate.
"""
from __future__ import annotations

from eval.compare import compare, verdict


def _report(*, failed=0, attempted=10, reasons=None, peak=3.0):
    return {
        "variant": "baseline",
        "settings": {"a": 1},
        "memory": {"peak_gib": peak},
        "sets": {
            "synthetic": {
                "set": "synthetic",
                "documents": 10,
                "headline": {
                    "field_accuracy": 0.9, "extracted_precision": 0.95,
                    "cer_mean": 0.05, "wer_mean": 0.06,
                },
                "by_condition": {},
                "fields": {},
                "searchable_pdf": {
                    "attempted": attempted,
                    "succeeded": attempted - failed,
                    "failed": failed,
                    "success_rate": (attempted - failed) / attempted if attempted else None,
                    "failure_reasons": reasons or {},
                },
            }
        },
    }


def test_a_clean_run_passes_the_rendition_gate():
    result = compare(_report(), _report(), 0.005)
    assert result["rendition_failures"] is None
    assert verdict(result)[1] == 0


def test_any_rendition_failure_fails_the_gate():
    result = compare(_report(), _report(failed=4, reasons={"is no PDF": 4}), 0.005)
    assert result["rendition_failures"] is not None
    name, code, why = verdict(result)
    assert code == 1
    assert name == "FAILED"
    assert "searchable PDF" in why


def test_the_image_bug_would_now_be_caught():
    """Every image in the corpus produced no rendition, with this exact
    cause. Under the old harness the run was indistinguishable from a
    clean one."""
    candidate = _report(attempted=117, failed=45, reasons={"is no PDF": 45})
    result = compare(_report(attempted=117), candidate, 0.005)
    assert verdict(result)[1] == 1
    assert "45 of 117" in result["rendition_failures"]


def test_it_is_not_compared_against_the_baseline():
    """A rendition either happened or it did not. 'No worse than last
    time' would have let the image bug pass, because the baseline was
    equally broken."""
    broken = _report(attempted=117, failed=45, reasons={"is no PDF": 45})
    result = compare(broken, broken, 0.005)
    assert verdict(result)[1] == 1, "an equally-broken baseline must not excuse the candidate"


def test_a_run_with_no_renditions_attempted_is_not_scored_as_perfect():
    """A rate over zero documents is no measurement, not a perfect one."""
    report = _report(attempted=0)
    block = report["sets"]["synthetic"]["searchable_pdf"]
    assert block["success_rate"] is None
    result = compare(report, report, 0.005)
    assert result["rendition_failures"] is None


def test_the_failure_names_its_cause():
    """So the reader is sent at the real problem, not told only that
    something failed."""
    result = compare(
        _report(attempted=117),
        _report(attempted=117, failed=45, reasons={"is no PDF": 45}),
        0.005,
    )
    assert "is no PDF" in result["rendition_failures"]
