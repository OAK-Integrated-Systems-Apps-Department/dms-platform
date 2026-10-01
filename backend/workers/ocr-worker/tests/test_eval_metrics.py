"""Tests for the eval harness's own scoring.

The harness decides whether every Phase 2 accuracy change ships. A bug in
`metrics.py` would not fail loudly -- it would quietly report the wrong
number and a change would be kept or reverted on it. These cases are
hand-computed rather than golden-file, so they verify the arithmetic
rather than pinning whatever it happens to produce today.
"""

from __future__ import annotations

import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "src"))
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from eval import metrics  # noqa: E402
from eval import schema as eval_schema  # noqa: E402


# ----------------------------------------------------------------------
# Edit distance
# ----------------------------------------------------------------------
def test_levenshtein_known_distances():
    assert metrics.levenshtein("kitten", "sitting") == 3  # k->s, e->i, +g
    assert metrics.levenshtein("", "abc") == 3
    assert metrics.levenshtein("abc", "abc") == 0
    assert metrics.levenshtein("flaw", "lawn") == 2


def test_levenshtein_is_symmetric():
    assert metrics.levenshtein("invoice", "1nvo1ce") == metrics.levenshtein("1nvo1ce", "invoice")


def test_cer_counts_one_substitution_over_reference_length():
    # "INV-001" -> "1NV-001": one substitution over 7 reference characters.
    assert metrics.character_error_rate("INV-001", "1NV-001") == pytest.approx(1 / 7)


def test_cer_ignores_line_break_placement():
    """Reading order across a tiled page legitimately moves line breaks.

    Scoring that as character errors would measure layout reconstruction,
    not recognition -- so whitespace runs collapse before comparison.
    """
    assert metrics.character_error_rate("alpha beta gamma", "alpha\nbeta\n gamma") == 0.0


def test_cer_of_an_empty_reference_is_zero_only_when_nothing_was_invented():
    assert metrics.character_error_rate("", "") == 0.0
    assert metrics.character_error_rate("", "hallucinated") == 1.0


def test_wer_counts_whole_tokens():
    # One wrong word out of four.
    assert metrics.word_error_rate("the total is due", "the total was due") == pytest.approx(0.25)


# ----------------------------------------------------------------------
# CER/WER are rates, and a reference that is not page text is refused
#
# The real set reported "CER (mean) 55.0000" and "WER (mean) 76.0000" --
# arithmetically correct for `edits / len(reference)`, meaningless as
# rates, and produced from a single label whose `reference_text` held a
# receipt number instead of the page's text.
# ----------------------------------------------------------------------
def test_a_hypothesis_longer_than_its_reference_reports_a_rate_not_a_ratio():
    ref, hyp = "RCP04269792", "MEDIHELP HOSPITALS Payment Receipt " * 20

    # The uncapped ratio is kept, because how far past 1.0 a document went
    # is the signal that the label, not the engine, is at fault.
    assert metrics.character_error_ratio(ref, hyp) > 1.0
    assert metrics.word_error_ratio(ref, hyp) > 1.0

    assert metrics.character_error_rate(ref, hyp) == 1.0
    assert metrics.word_error_rate(ref, hyp) == 1.0


def test_capping_does_not_hide_a_real_recognition_failure():
    # A blank page the engine covered in invented text is still the worst
    # possible result, and must keep saying so.
    assert metrics.character_error_rate("", "hallucinated") == 1.0
    assert metrics.word_error_rate("", "hallucinated") == 1.0


def test_a_reference_too_short_to_be_page_text_is_refused_by_name():
    problem = metrics.reference_text_problem("RCP04269792")
    assert problem is not None
    assert "11 characters" in problem


def test_absent_and_full_length_references_are_both_fine():
    # Empty is an opt-out, not a defect; a real page's text passes.
    assert metrics.reference_text_problem("") is None
    assert metrics.reference_text_problem("   \n ") is None
    assert metrics.reference_text_problem("x" * metrics.MIN_REFERENCE_CHARS) is None


# ----------------------------------------------------------------------
# Field comparison
# ----------------------------------------------------------------------
def test_field_match_folds_case_and_whitespace():
    assert metrics.field_matches("Confidential", "confidential")
    assert metrics.field_matches("Northwind  Trading Ltd", "Northwind Trading Ltd")
    assert not metrics.field_matches("Northwind Trading Ltd", "Northwind Trading")


def test_multi_select_order_is_not_an_error():
    assert metrics.field_matches(["archive", "urgent"], ["urgent", "archive"])


def test_missing_value_never_matches_a_present_one():
    assert not metrics.field_matches("INV-1", None)
    assert metrics.field_matches(None, "")


# ----------------------------------------------------------------------
# Accumulation
# ----------------------------------------------------------------------
def test_counts_separate_precision_from_recall():
    counts: dict[str, metrics.FieldCounts] = {}
    expected = {"invoice_number": "INV-1", "amount": "10.00"}

    # invoice_number right, amount wrong, plus a field nothing expected.
    metrics.accumulate_field_counts(
        counts,
        expected,
        {
            "invoice_number": {"value": "INV-1", "status": "extracted"},
            "amount": {"value": "99.00", "status": "extracted"},
            "currency": {"value": "USD", "status": "extracted"},
        },
    )

    assert counts["invoice_number"].precision == 1.0
    assert counts["invoice_number"].recall == 1.0
    assert counts["amount"].precision == 0.0
    assert counts["amount"].recall == 0.0
    # Produced but never expected: precision 0 over one produced value,
    # and recall 0 over zero expectations -- not a division error.
    assert counts["currency"].produced == 1
    assert counts["currency"].expected == 0
    assert counts["currency"].recall == 0.0


def test_a_field_that_is_never_attempted_scores_zero_recall_not_undefined_precision():
    counts: dict[str, metrics.FieldCounts] = {}
    metrics.accumulate_field_counts(counts, {"cost_centre": "CC-1"}, {})
    assert counts["cost_centre"].expected == 1
    assert counts["cost_centre"].produced == 0
    assert counts["cost_centre"].recall == 0.0
    assert counts["cost_centre"].precision == 0.0


def test_empty_and_null_values_do_not_count_as_produced():
    counts: dict[str, metrics.FieldCounts] = {}
    metrics.accumulate_field_counts(
        counts,
        {"department": "Finance"},
        {"department": {"value": None}, "author": {"value": "   "}},
    )
    assert counts["department"].produced == 0
    assert counts["author"].produced == 0


def test_extracted_precision_only_counts_values_that_claimed_that_status():
    counts: dict[str, metrics.FieldCounts] = {}
    expected = {"a": "1", "b": "2"}
    metrics.accumulate_field_counts(
        counts,
        expected,
        {
            "a": {"value": "1", "status": "extracted"},
            "b": {"value": "wrong", "status": "low_confidence"},
        },
    )
    # "b" was wrong but never claimed to be extracted, so it must not
    # drag down the extracted-precision figure -- that number exists to
    # answer "when we said we were sure, were we right".
    assert counts["a"].extracted_precision == 1.0
    assert counts["b"].status_extracted == 0
    assert counts["b"].extracted_precision == 0.0


def test_status_comes_from_the_field_results_table_not_the_output_json():
    """Pins the fix for a harness bug that reported 0% everywhere.

    `requested_fields_to_output_dict` emits only value/confidence/bbox --
    the per-field status lives solely in `document_ocr_field_results`.
    Reading status off the output dict made section 5's
    extracted-precision metric report 0% for every field on every run,
    which looks like a catastrophic product result and was in fact a
    measurement error.
    """
    counts: dict[str, metrics.FieldCounts] = {}
    metrics.accumulate_field_counts(
        counts,
        {"invoice_number": "INV-1"},
        {"invoice_number": {"value": "INV-1", "confidence": 0.8}},  # no status here
        {"invoice_number": "extracted"},                            # ... it comes from here
    )
    assert counts["invoice_number"].status_extracted == 1
    assert counts["invoice_number"].extracted_precision == 1.0


def test_a_decimal_with_thousands_separators_matches_its_canonical_form():
    """The pipeline emits what the document printed.

    A real invoice total comes back as `"41,252.70"`, and that is correct
    -- dms-core's `parseNumber` strips the comma before storing it (a bug
    it found and fixed live). Comparing as strings scored `amount` at 0%
    across the whole corpus and looked exactly like a product defect.
    """
    assert metrics.field_matches("41252.70", "41,252.70")
    assert metrics.field_matches("41252.70", "41 252.70")
    assert metrics.field_matches("2500.00", "2500")
    assert not metrics.field_matches("41252.70", "41252.71")


def test_identifiers_containing_digits_are_never_compared_numerically():
    """`PO-74300` and `CC-1042` are identifiers, not numbers.

    The numeric path is anchored end to end so a value that merely
    contains digits cannot slip into it -- otherwise a transposed
    identifier could compare equal to the right one.
    """
    assert not metrics.field_matches("PO-74300", "PO-74301")
    assert not metrics.field_matches("CC-1042", "1042")
    assert metrics.field_matches("PO-74300", "PO-74300")
    # A date is not a number either.
    assert not metrics.field_matches("2026-03-14", "2026-03-15")


def test_json_encoded_multi_select_matches_the_decoded_list():
    """ocr-worker JSON-encodes multi_select values; dms-core decodes them.

    That is the contract (`DocumentMetadataServiceImpl` uses an
    ObjectMapper for exactly this), so the encoded form is correct and
    must not be scored as a miss.
    """
    assert metrics.field_matches(["audited", "urgent"], '["audited", "urgent"]')
    assert metrics.field_matches(["urgent", "audited"], '["audited", "urgent"]')
    assert not metrics.field_matches(["audited", "urgent"], '["audited"]')
    # A value that merely looks bracketed is still compared as text.
    assert metrics.field_matches("[not json", "[not json")


def test_a_value_with_no_status_is_produced_but_not_extracted():
    """The legacy full-document path emits no `status`.

    It must count toward precision/recall but never toward
    extracted-precision, which would otherwise be flattered by values
    that never claimed the confidence.
    """
    counts: dict[str, metrics.FieldCounts] = {}
    metrics.accumulate_field_counts(counts, {"a": "1"}, {"a": {"value": "1"}})
    assert counts["a"].produced == 1
    assert counts["a"].correct == 1
    assert counts["a"].status_extracted == 0


# ----------------------------------------------------------------------
# Percentiles
# ----------------------------------------------------------------------
def test_percentile_is_nearest_rank_and_returns_an_observed_value():
    values = [1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0, 9.0, 10.0]
    assert metrics.percentile(values, 50) == 5.0
    assert metrics.percentile(values, 95) == 10.0
    assert metrics.percentile(values, 100) == 10.0
    # Never interpolates into a value nothing produced.
    assert metrics.percentile([1.0, 2.0], 50) in (1.0, 2.0)


def test_percentile_of_nothing_is_zero_not_an_error():
    assert metrics.percentile([], 95) == 0.0


# ----------------------------------------------------------------------
# Quality-flag confusion counts
# ----------------------------------------------------------------------
def test_binary_counts_precision_and_recall():
    counts = metrics.BinaryCounts()
    counts.add(True, True)    # tp
    counts.add(True, False)   # fp
    counts.add(False, True)   # fn
    counts.add(False, False)  # tn
    assert counts.precision == pytest.approx(0.5)
    assert counts.recall == pytest.approx(0.5)


def test_binary_counts_with_no_predictions_is_zero_not_an_error():
    counts = metrics.BinaryCounts()
    counts.add(False, True)
    assert counts.precision == 0.0
    assert counts.recall == 0.0


# ----------------------------------------------------------------------
# The mirrored schema must not drift from V30
# ----------------------------------------------------------------------
_MIGRATION_RELATIVE = Path(
    "dms-core/src/main/resources/db/migration/"
    "V30__seed_metadata_field_catalogue_and_document_types.sql"
)


def _find_migration() -> Path | None:
    """Walk up looking for the dms-core migration.

    Resolved by search rather than by a fixed number of `.parents` hops
    because the test runs both from the repo checkout and from inside the
    container, where only `backend/workers/ocr-worker` is mounted and the
    dms-core tree is simply absent. A hard-coded hop count raises
    IndexError there instead of skipping.
    """
    for parent in Path(__file__).resolve().parents:
        candidate = parent / _MIGRATION_RELATIVE
        if candidate.exists():
            return candidate
    return None


_MIGRATION = _find_migration()


@pytest.mark.skipif(_MIGRATION is None, reason="dms-core tree not present")
def test_eval_schema_field_sets_match_the_v30_seed():
    """Guards the one real risk in mirroring the schema into Python.

    `eval/schema.py` copies V30's document_type_fields so the harness can
    run without Postgres. If someone adds a field to a seeded type and
    only the migration changes, every accuracy number silently starts
    ignoring that field. This fails instead.
    """
    import re

    sql = _MIGRATION.read_text(encoding="utf-8")
    for type_code, entries in eval_schema.TYPE_FIELDS.items():
        for field_key, _mandatory in entries:
            # The seed aligns its VALUES rows into columns, so the gap
            # after the comma varies. Matching on a fixed number of
            # spaces made this test fail on formatting rather than drift.
            pattern = rf"\('{type_code}',\s*'{field_key}'"
            assert re.search(pattern, sql), (
                f"{type_code}.{field_key} is in eval/schema.py but not in V30"
            )


@pytest.mark.skipif(_MIGRATION is None, reason="dms-core tree not present")
def test_eval_schema_options_match_the_v30_seed():
    sql = _MIGRATION.read_text(encoding="utf-8")
    for field_key, options in eval_schema.OPTIONS.items():
        for option in options:
            assert f"('{field_key}'," in sql and f"'{option}'" in sql, (
                f"{field_key} option {option!r} is in eval/schema.py but not in V30"
            )


def test_every_mirrored_field_has_a_declared_data_type():
    for type_code, entries in eval_schema.TYPE_FIELDS.items():
        for field_key, _mandatory in entries:
            assert field_key in eval_schema.DATA_TYPES, f"{type_code}.{field_key} has no data_type"


def test_only_fields_with_seeded_options_carry_them():
    """Not every dropdown has options, and that is V30's real state.

    `classification_level`, `currency` and `retention_class` are the only
    three the migration seeds. `language` is deliberately left optionless
    (V30 says so, to exercise the MDTM alternate flow), and `cost_centre`
    and `project_code` are dropdowns with no options either. The harness
    must pass exactly what the schema resolver would -- inventing options
    here would let an extraction that can only succeed against a known
    option list score better than it will in production.
    """
    for type_code in eval_schema.TYPE_FIELDS:
        for spec in eval_schema.field_specs_for(type_code):
            if spec.field_key in eval_schema.OPTIONS:
                assert spec.options == eval_schema.OPTIONS[spec.field_key]
            else:
                assert spec.options is None, (
                    f"{spec.field_key} carries options that V30 does not seed"
                )


def test_optionless_dropdowns_are_recorded_deliberately():
    optionless = {
        key
        for key, data_type in eval_schema.DATA_TYPES.items()
        if data_type == "dropdown" and key not in eval_schema.OPTIONS
    }
    # Pinned so that seeding options for one of these later is a
    # deliberate edit here rather than a silent change in what the
    # corpus measures.
    assert optionless == {"language", "cost_centre", "project_code"}


# ----------------------------------------------------------------------
# Preprocessing accounting -- the orientation false-rotation rate
#
# This is the number that decides whether orientation correction is safe
# to have on by default: rotating an upright page costs as much accuracy
# as correcting a rotated one gains. If this arithmetic is wrong, a stage
# that had started mangling good pages would look clean in the report.
# ----------------------------------------------------------------------
from types import SimpleNamespace  # noqa: E402

from eval.run import accumulate_orientation, finalize_orientation, metrics_orientation_counts  # noqa: E402


def _doc(doc_id: str, condition: str):
    return SimpleNamespace(doc_id=doc_id, condition=condition)


def _page(number: int, *, angle=None, applied=False, confidence=0.93):
    """A page's output entry. `angle=None` means no orientation key at
    all -- a native-text page, which is never rasterized."""
    preprocessing = (
        {} if angle is None
        else {"orientation": {"angle": angle, "confidence": confidence, "applied": applied}}
    )
    return {"page_number": number, "preprocessing": preprocessing}


def test_a_native_text_page_counts_as_never_classified():
    counts = metrics_orientation_counts()
    accumulate_orientation(counts, _doc("d", "native_pdf"), {"pages": [_page(1)]})
    finalize_orientation(counts)

    assert counts["pages_total"] == 1
    assert counts["pages_native_text"] == 1
    assert counts["pages_classified"] == 0
    # It contributes to neither numerator nor denominator: it was never a
    # page the classifier could have got right or wrong.
    assert counts["upright_pages"] == 0
    assert counts["false_rotation_rate"] is None


def test_an_upright_page_left_alone_is_not_a_false_rotation():
    counts = metrics_orientation_counts()
    accumulate_orientation(counts, _doc("d", "clean_scan_300"), {"pages": [_page(1, angle=0, applied=False)]})
    finalize_orientation(counts)

    assert counts["upright_pages"] == 1
    assert counts["upright_pages_rotated"] == 0
    assert counts["false_rotation_rate"] == 0.0


def test_an_upright_page_that_was_rotated_is_counted_and_named():
    counts = metrics_orientation_counts()
    accumulate_orientation(
        counts, _doc("invoice-clean_scan_300-00", "clean_scan_300"),
        {"pages": [_page(1, angle=90, applied=True, confidence=0.71)]},
    )
    finalize_orientation(counts)

    assert counts["upright_pages_rotated"] == 1
    assert counts["false_rotation_rate"] == 1.0
    assert counts["wrongly_rotated"] == [
        {"doc_id": "invoice-clean_scan_300-00", "page": 1, "angle": 90, "confidence": 0.71}
    ]


def test_a_recorded_but_unapplied_rotation_on_an_upright_page_is_not_a_false_rotation():
    """The gate's whole purpose: a prediction below the confidence floor
    is recorded and not acted on, and the page is unharmed."""
    counts = metrics_orientation_counts()
    accumulate_orientation(
        counts, _doc("d", "skewed_2deg"), {"pages": [_page(1, angle=90, applied=False, confidence=0.3)]}
    )
    finalize_orientation(counts)

    assert counts["upright_pages_rotated"] == 0
    assert counts["by_applied_angle"] == {"0": 1}


def test_a_skewed_page_is_ground_truth_upright():
    """`skewed_2deg` is a 2-degree skew, not a quarter turn. Treating it
    as rotated would make a correct 'leave it alone' look like a miss and
    invite someone to loosen the gate."""
    counts = metrics_orientation_counts()
    accumulate_orientation(counts, _doc("d", "skewed_2deg"), {"pages": [_page(1, angle=0, applied=False)]})
    finalize_orientation(counts)

    assert counts["upright_pages"] == 1
    assert counts["rotated_pages"] == 0


@pytest.mark.parametrize("angle", [90, 180, 270])
def test_a_rotated_page_corrected_to_its_true_angle_counts_as_corrected(angle):
    counts = metrics_orientation_counts()
    accumulate_orientation(
        counts, _doc("d", f"rotated_{angle}"), {"pages": [_page(1, angle=angle, applied=True)]}
    )
    finalize_orientation(counts)

    assert counts["rotated_pages"] == 1
    assert counts["rotated_pages_corrected"] == 1
    assert counts["correction_rate"] == 1.0


def test_a_rotated_page_corrected_to_the_wrong_angle_is_not_counted_as_corrected():
    counts = metrics_orientation_counts()
    accumulate_orientation(
        counts, _doc("d", "rotated_90"), {"pages": [_page(1, angle=180, applied=True)]}
    )
    finalize_orientation(counts)

    assert counts["rotated_pages_corrected"] == 0
    assert counts["correction_rate"] == 0.0
    # ...and it is NOT logged as a false rotation either: the page really
    # was rotated, it was just corrected wrongly. Conflating the two would
    # hide a miss inside the false-positive number.
    assert counts["upright_pages_rotated"] == 0


def test_a_multipage_document_accumulates_every_page():
    counts = metrics_orientation_counts()
    accumulate_orientation(
        counts,
        _doc("d", "multipage"),
        {"pages": [_page(1, angle=0), _page(2, angle=0), _page(3, angle=90, applied=True)]},
    )
    finalize_orientation(counts)

    assert counts["pages_classified"] == 3
    assert counts["upright_pages"] == 3
    assert counts["upright_pages_rotated"] == 1
    assert counts["false_rotation_rate"] == pytest.approx(1 / 3, abs=1e-4)


def test_a_real_document_contributes_pages_but_no_ground_truth():
    """A supplied real document has no known true orientation, so it can
    be tallied but never scored -- scoring it against an assumed 'upright'
    would manufacture false positives out of documents that really were
    sideways."""
    counts = metrics_orientation_counts()
    accumulate_orientation(counts, _doc("scan-042", ""), {"pages": [_page(1, angle=90, applied=True)]})
    finalize_orientation(counts)

    assert counts["pages_classified"] == 1
    assert counts["upright_pages"] == 0
    assert counts["rotated_pages"] == 0
    assert counts["false_rotation_rate"] is None


# ----------------------------------------------------------------------
# The gate must name what actually blocked
#
# An earlier `verdict()` fell through to "the real set regressed" whenever
# anything blocked -- so a run whose only problem was peak memory reported
# a regression in a set that, with no real corpus, does not exist. A gate
# that misdiagnoses sends someone to look in the wrong place, which is
# worse than one that only says "failed".
# ----------------------------------------------------------------------
from eval.compare import verdict  # noqa: E402


def _result(**overrides):
    base = {
        "synthetic_regressions": [],
        "real_regressions": [],
        "memory_regression": None,
        # Any searchable-PDF failure fails the candidate outright -- it is
        # not compared against the baseline (see compare.py).
        "rendition_failures": None,
        "has_real_data": False,
        "real_improvements": [],
    }
    base.update(overrides)
    return base


def test_a_memory_only_regression_says_so():
    name, code, why = verdict(_result(memory_regression="peak memory 3.6 GiB -> 4.3 GiB"))
    assert (name, code) == ("FAILED", 1)
    assert "memory" in why
    assert "real set" not in why


def test_a_synthetic_regression_is_a_veto():
    name, code, why = verdict(_result(synthetic_regressions=["field_accuracy dropped"]))
    assert (name, code) == ("VETOED", 1)
    assert "synthetic" in why


def test_a_real_regression_outranks_everything():
    name, code, why = verdict(_result(
        real_regressions=["field_accuracy dropped"],
        synthetic_regressions=["also dropped"],
        memory_regression="and memory",
    ))
    assert (name, code) == ("FAILED", 1)
    assert "real set" in why


def test_a_clean_synthetic_only_run_is_veto_only():
    name, code, why = verdict(_result())
    assert (name, code) == ("VETO-ONLY", 0)
    assert "cannot justify" in why


def test_a_searchable_pdf_failure_fails_the_verdict():
    """A rendition either happened or it did not. The image bug -- every
    image upload producing none -- passed the whole harness because
    nothing read the flag."""
    name, code, why = verdict(_result(rendition_failures="synthetic: 45 of 117 produced none"))
    assert (name, code) == ("FAILED", 1)
    assert "searchable PDF" in why
    assert "45 of 117" in why


def test_a_real_regression_still_outranks_a_rendition_failure():
    """The explanation must name what actually blocked, and the real set
    losing accuracy is the more serious finding."""
    name, code, why = verdict(_result(
        real_regressions=["field_accuracy dropped"],
        rendition_failures="synthetic: 1 of 3 produced none",
    ))
    assert (name, code) == ("FAILED", 1)
    assert "real set" in why
