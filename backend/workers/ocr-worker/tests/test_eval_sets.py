"""Tests for the two-set split and the keep-or-drop decision rule.

The rule Phase 2 works under:

- the **real** set decides whether a change is kept;
- the **synthetic** set can only **veto** -- a regression fails the gate,
  an improvement justifies nothing on its own;
- with no real data the verdict is VETO-ONLY: nothing is broken, but the
  run cannot justify keeping the change.

These are the tests that stop that rule quietly becoming "whatever the
average says", which is what happens the first time someone adds the two
sets together.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "src"))
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from eval import compare as eval_compare  # noqa: E402
from eval import realset  # noqa: E402


# ----------------------------------------------------------------------
# Report fixtures
# ----------------------------------------------------------------------
def _set(documents: int, *, field_accuracy: float, cer: float = 0.05, conditions=None) -> dict:
    return {
        "documents": documents,
        "headline": {
            "field_accuracy": field_accuracy,
            "extracted_precision": 0.9,
            "cer_mean": cer,
            "wer_mean": cer,
        },
        "by_condition": conditions or {},
        "fields": {},
    }


def _report(*, synthetic=None, real=None, peak_gib=3.5, settings=None) -> dict:
    sets = {}
    if synthetic is not None:
        sets["synthetic"] = synthetic
    if real is not None:
        sets["real"] = real
    return {
        "variant": "v",
        "settings": settings if settings is not None else {"ocr_render_dpi": 200},
        "memory": {"peak_gib": peak_gib},
        "sets": sets,
    }


def _verdict(baseline, candidate, tolerance=0.005):
    return eval_compare.verdict(eval_compare.compare(baseline, candidate, tolerance))[0]


# ----------------------------------------------------------------------
# The decision rule
# ----------------------------------------------------------------------
def test_no_real_data_can_never_justify_a_change():
    """Even a large synthetic improvement is VETO-ONLY.

    This is the whole point of the rule: the synthetic set is generated
    and labelled by the same process being measured, so it can be
    improved by a change that does nothing for a real document.
    """
    baseline = _report(synthetic=_set(117, field_accuracy=0.50))
    candidate = _report(synthetic=_set(117, field_accuracy=0.90))
    assert _verdict(baseline, candidate) == "VETO-ONLY"


def test_a_synthetic_regression_vetoes_even_when_real_improved():
    baseline = _report(synthetic=_set(117, field_accuracy=0.80), real=_set(10, field_accuracy=0.60))
    candidate = _report(synthetic=_set(117, field_accuracy=0.60), real=_set(10, field_accuracy=0.90))
    assert _verdict(baseline, candidate) == "VETOED"


def test_a_real_improvement_with_no_regression_is_justified():
    baseline = _report(synthetic=_set(117, field_accuracy=0.80), real=_set(10, field_accuracy=0.60))
    candidate = _report(synthetic=_set(117, field_accuracy=0.80), real=_set(10, field_accuracy=0.75))
    assert _verdict(baseline, candidate) == "JUSTIFIED"


def test_real_data_that_does_not_improve_is_neutral_not_justified():
    """Nothing broke, but nothing got better either.

    Distinct from JUSTIFIED on purpose -- "the gate passed" must not be
    read as "the change earned its place".
    """
    baseline = _report(synthetic=_set(117, field_accuracy=0.80), real=_set(10, field_accuracy=0.60))
    candidate = _report(synthetic=_set(117, field_accuracy=0.80), real=_set(10, field_accuracy=0.601))
    assert _verdict(baseline, candidate) == "NEUTRAL"


def test_a_real_regression_fails():
    baseline = _report(synthetic=_set(117, field_accuracy=0.80), real=_set(10, field_accuracy=0.70))
    candidate = _report(synthetic=_set(117, field_accuracy=0.80), real=_set(10, field_accuracy=0.50))
    assert _verdict(baseline, candidate) == "FAILED"


def test_a_memory_regression_fails_regardless_of_accuracy():
    """Phase 2's design constraint: accuracy work must not raise the
    memory ceiling, because the container has ~1.6 GiB of headroom."""
    baseline = _report(synthetic=_set(117, field_accuracy=0.80), real=_set(10, field_accuracy=0.60), peak_gib=3.5)
    candidate = _report(synthetic=_set(117, field_accuracy=0.80), real=_set(10, field_accuracy=0.90), peak_gib=6.0)
    assert _verdict(baseline, candidate) == "FAILED"


def test_small_memory_jitter_is_not_a_regression():
    baseline = _report(synthetic=_set(117, field_accuracy=0.80), peak_gib=3.50)
    candidate = _report(synthetic=_set(117, field_accuracy=0.80), peak_gib=3.60)
    assert _verdict(baseline, candidate) == "VETO-ONLY"


def test_one_destroyed_condition_vetoes_even_when_the_average_rises():
    baseline = _report(synthetic=_set(
        117, field_accuracy=0.80,
        conditions={"rotated_90": {"field_exact_match": 0.80}, "native_pdf": {"field_exact_match": 0.90}},
    ))
    candidate = _report(synthetic=_set(
        117, field_accuracy=0.88,
        conditions={"rotated_90": {"field_exact_match": 0.10}, "native_pdf": {"field_exact_match": 0.99}},
    ))
    assert _verdict(baseline, candidate) == "VETOED"


def test_noise_within_tolerance_is_neither_improvement_nor_regression():
    baseline = _report(synthetic=_set(117, field_accuracy=0.8000), real=_set(10, field_accuracy=0.6000))
    candidate = _report(synthetic=_set(117, field_accuracy=0.8020), real=_set(10, field_accuracy=0.5990))
    assert _verdict(baseline, candidate) == "NEUTRAL"


def test_sets_are_compared_separately_and_never_averaged():
    """A real regression must not be cancelled out by a synthetic gain.

    Averaging the two sets would report a healthy number here, which is
    exactly the failure mode the split exists to prevent.
    """
    baseline = _report(synthetic=_set(117, field_accuracy=0.50), real=_set(10, field_accuracy=0.90))
    candidate = _report(synthetic=_set(117, field_accuracy=0.90), real=_set(10, field_accuracy=0.50))
    assert _verdict(baseline, candidate) == "FAILED"


def test_a_changed_setting_is_noted_but_not_a_failure():
    baseline = _report(synthetic=_set(117, field_accuracy=0.80), settings={"ocr_render_dpi": 200})
    candidate = _report(synthetic=_set(117, field_accuracy=0.80), settings={"ocr_render_dpi": 300})
    result = eval_compare.compare(baseline, candidate, 0.005)
    assert any("settings differ" in n for n in result["notes"])
    assert eval_compare.verdict(result)[1] == 0


# ----------------------------------------------------------------------
# Backward compatibility with a pre-two-set report
# ----------------------------------------------------------------------
def test_an_old_flat_report_is_read_as_the_synthetic_set(tmp_path):
    """An early baseline predates the split and was synthetic by
    definition. Adapting it keeps it usable instead of costing a fresh
    30-minute run."""
    flat = {
        "variant": "baseline",
        "settings": {"ocr_render_dpi": 200},
        "memory": {"peak_gib": 3.4},
        "corpus": {"documents": 117},
        "headline": {"field_accuracy": 0.6, "extracted_precision": 0.7, "cer_mean": 0.1, "wer_mean": 0.1},
        "by_condition": {},
        "fields": {},
    }
    path = tmp_path / "old.json"
    path.write_text(json.dumps(flat), encoding="utf-8")

    loaded = eval_compare._load(str(path))
    assert "synthetic" in loaded["sets"]
    assert "real" not in loaded["sets"]
    assert loaded["sets"]["synthetic"]["documents"] == 117
    assert loaded["sets"]["synthetic"]["headline"]["field_accuracy"] == 0.6


# ----------------------------------------------------------------------
# Real-set loading
# ----------------------------------------------------------------------
def test_an_unreviewed_label_is_never_scored(tmp_path, monkeypatch):
    """A draft label is the pipeline's own output.

    Scoring against it would compare the pipeline to itself and report
    perfection forever, which is worse than having no real set at all.
    """
    corpus = tmp_path / "corpus" / "real"
    labels = tmp_path / "labels" / "real"
    corpus.mkdir(parents=True)
    labels.mkdir(parents=True)
    (corpus / "acme.pdf").write_bytes(b"%PDF-1.4 fake")
    (labels / "acme.json").write_text(json.dumps({
        "reviewed": False, "doc_id": "acme", "filename": "acme.pdf",
        "type_code": "INVOICE", "fields": {"invoice_number": "INV-1"},
    }), encoding="utf-8")

    monkeypatch.setattr(realset, "REAL_CORPUS_DIR", corpus)
    monkeypatch.setattr(realset, "REAL_LABELS_DIR", labels)

    documents, pending, problems = realset.load_real_documents()
    assert documents == []
    assert pending == ["acme"]
    assert problems == []


def test_a_reviewed_label_is_loaded_as_a_real_document(tmp_path, monkeypatch):
    corpus = tmp_path / "corpus" / "real"
    labels = tmp_path / "labels" / "real"
    corpus.mkdir(parents=True)
    labels.mkdir(parents=True)
    (corpus / "acme.pdf").write_bytes(b"%PDF-1.4 fake")
    (labels / "acme.json").write_text(json.dumps({
        "reviewed": True, "doc_id": "acme", "filename": "acme.pdf",
        "type_code": "INVOICE", "mime_type": "application/pdf",
        "fields": {"invoice_number": "INV-1"}, "reference_text": "",
    }), encoding="utf-8")

    monkeypatch.setattr(realset, "REAL_CORPUS_DIR", corpus)
    monkeypatch.setattr(realset, "REAL_LABELS_DIR", labels)

    documents, pending, problems = realset.load_real_documents()
    assert pending == []
    assert problems == []
    assert len(documents) == 1
    assert documents[0].set_name == "real"
    assert documents[0].condition == "real"
    assert documents[0].fields == {"invoice_number": "INV-1"}


def test_a_document_with_no_label_is_reported_not_silently_skipped(tmp_path, monkeypatch):
    corpus = tmp_path / "corpus" / "real"
    labels = tmp_path / "labels" / "real"
    corpus.mkdir(parents=True)
    labels.mkdir(parents=True)
    (corpus / "acme.pdf").write_bytes(b"%PDF-1.4 fake")

    monkeypatch.setattr(realset, "REAL_CORPUS_DIR", corpus)
    monkeypatch.setattr(realset, "REAL_LABELS_DIR", labels)

    _documents, _pending, problems = realset.load_real_documents()
    assert any("no label" in p for p in problems)


def test_an_unknown_type_code_is_reported_rather_than_guessed(tmp_path, monkeypatch):
    """type_code decides which fields are asked for, so a wrong one makes
    every field wrong. Defaulting it would hide that."""
    corpus = tmp_path / "corpus" / "real"
    labels = tmp_path / "labels" / "real"
    corpus.mkdir(parents=True)
    labels.mkdir(parents=True)
    (corpus / "acme.pdf").write_bytes(b"%PDF-1.4 fake")
    (labels / "acme.json").write_text(json.dumps({
        "reviewed": True, "doc_id": "acme", "filename": "acme.pdf",
        "type_code": "RECEIPT", "fields": {},
    }), encoding="utf-8")

    monkeypatch.setattr(realset, "REAL_CORPUS_DIR", corpus)
    monkeypatch.setattr(realset, "REAL_LABELS_DIR", labels)

    documents, _pending, problems = realset.load_real_documents()
    assert documents == []
    assert any("RECEIPT" in p for p in problems)


def test_the_drafter_never_overwrites_an_existing_label(tmp_path, monkeypatch):
    """A corrected label is the most expensive artifact in this harness."""
    corpus = tmp_path / "corpus" / "real"
    labels = tmp_path / "labels" / "real"
    corpus.mkdir(parents=True)
    labels.mkdir(parents=True)
    document = corpus / "acme.pdf"
    document.write_bytes(b"%PDF-1.4 fake")
    existing = json.dumps({"reviewed": True, "doc_id": "acme", "fields": {"invoice_number": "CORRECTED"}})
    (labels / "acme.json").write_text(existing, encoding="utf-8")

    monkeypatch.setattr(realset, "REAL_CORPUS_DIR", corpus)
    monkeypatch.setattr(realset, "REAL_LABELS_DIR", labels)

    realset.write_draft_label(
        document, type_code="INVOICE", fields={"invoice_number": "OVERWRITTEN"},
        field_notes={}, page_count=1,
    )
    assert json.loads((labels / "acme.json").read_text(encoding="utf-8")) == json.loads(existing)


def test_a_fresh_draft_is_marked_unreviewed(tmp_path, monkeypatch):
    corpus = tmp_path / "corpus" / "real"
    labels = tmp_path / "labels" / "real"
    corpus.mkdir(parents=True)
    labels.mkdir(parents=True)
    document = corpus / "acme.pdf"
    document.write_bytes(b"%PDF-1.4 fake")

    monkeypatch.setattr(realset, "REAL_CORPUS_DIR", corpus)
    monkeypatch.setattr(realset, "REAL_LABELS_DIR", labels)

    target = realset.write_draft_label(
        document, type_code="INVOICE", fields={"invoice_number": "INV-9"},
        field_notes={"invoice_number": {"confidence": 0.8, "status": "extracted"}},
        page_count=2,
    )
    label = json.loads(target.read_text(encoding="utf-8"))
    assert label["reviewed"] is False
    assert label["type_code"] == "INVOICE"
    assert label["expected_page_count"] == 2
    assert "_README" in label


@pytest.mark.parametrize(
    "filename,expected",
    [
        ("ACME Corp invoice.pdf", "ACME-Corp-invoice"),
        ("../../etc/passwd.pdf", "passwd"),
        ("....pdf", "document"),
    ],
)
def test_doc_ids_are_filesystem_safe(filename, expected):
    assert realset.doc_id_for(Path(filename)) == expected
