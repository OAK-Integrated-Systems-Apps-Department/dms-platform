"""Computed field confidence and the `requires_review` rule (3.10)."""
from __future__ import annotations

import pytest

from ocr_worker import field_confidence as fc
from ocr_worker import review
from ocr_worker.extraction import RequestedFieldResult


def _result(key, status="extracted"):
    return RequestedFieldResult(field_key=key, value="x", confidence=0.9, status=status)


class TestComputeFieldConfidence:
    def test_it_is_the_product_of_the_three_terms(self):
        assert fc.compute(ocr_confidence=0.8, tier=fc.TIER_SPATIAL, validation_factor=1.0) == pytest.approx(0.8 * 0.90)

    def test_native_text_contributes_one_not_zero(self):
        """`ocr_confidence=None` means a PDF text layer -- there is no
        character-level uncertainty to propagate. Treating it as 0.0 would
        make every native-text field worthless."""
        assert fc.compute(ocr_confidence=None, tier=fc.TIER_ZONE) == pytest.approx(1.0)
        assert fc.compute(ocr_confidence=0.0, tier=fc.TIER_ZONE) == pytest.approx(0.0)

    def test_a_stronger_tier_scores_higher_for_the_same_reading(self):
        scores = [
            fc.compute(ocr_confidence=0.9, tier=tier)
            for tier in (fc.TIER_ZONE, fc.TIER_ANCHOR, fc.TIER_SPATIAL, fc.TIER_PATTERN, fc.TIER_FALLBACK)
        ]
        assert scores == sorted(scores, reverse=True), "tier ordering is the claim these weights make"

    def test_a_validation_failure_lowers_but_never_zeroes(self):
        """The value is still a real reading of the document. Zeroing it
        would make a failed cross-check look like 'found nothing'."""
        failed = fc.compute(ocr_confidence=0.9, tier=fc.TIER_SPATIAL, validation_factor=fc.VALIDATION_FAIL)
        passed = fc.compute(ocr_confidence=0.9, tier=fc.TIER_SPATIAL, validation_factor=fc.VALIDATION_PASS)
        assert 0 < failed < passed

    def test_nothing_to_validate_is_not_a_penalty(self):
        assert fc.VALIDATION_NONE == fc.VALIDATION_PASS

    def test_an_unknown_tier_falls_back_to_the_weakest_weight(self):
        """A tier nobody has weighted is a tier nobody has evaluated, and
        the safe direction for that is less confident, not 1.0."""
        assert fc.compute(ocr_confidence=1.0, tier="something_new") == pytest.approx(
            fc.DEFAULT_TIER_WEIGHTS[fc.TIER_FALLBACK]
        )

    def test_the_result_is_clamped(self):
        assert fc.compute(ocr_confidence=5.0, tier=fc.TIER_ZONE) == 1.0
        assert fc.compute(ocr_confidence=-1.0, tier=fc.TIER_ZONE) == 0.0

    def test_weights_are_overridable_so_calibration_is_config_not_code(self):
        custom = dict(fc.DEFAULT_TIER_WEIGHTS, spatial=0.5)
        assert fc.compute(ocr_confidence=1.0, tier=fc.TIER_SPATIAL, tier_weights=custom) == pytest.approx(0.5)


class TestMeanOcrConfidenceForBbox:
    def test_it_averages_the_lines_a_value_overlaps(self):
        lines = [((0, 0, 100, 20), 0.8), ((0, 30, 100, 50), 0.6), ((0, 60, 100, 80), 0.99)]
        assert fc.mean_ocr_confidence_for_bbox(lines, (0, 0, 100, 20)) == pytest.approx(0.8)

    def test_a_value_clipped_out_of_its_line_still_matches_it(self):
        """A field bbox is usually the VALUE's box, a strict sub-box of the
        line that also held the label -- equality would never match."""
        lines = [((0, 0, 200, 20), 0.75)]
        assert fc.mean_ocr_confidence_for_bbox(lines, (120, 2, 190, 18)) == pytest.approx(0.75)

    def test_no_line_confidences_at_all_is_none_meaning_native_text(self):
        assert fc.mean_ocr_confidence_for_bbox(None, (0, 0, 10, 10)) is None
        assert fc.mean_ocr_confidence_for_bbox([], (0, 0, 10, 10)) is None

    def test_nothing_overlapping_is_none_rather_than_an_invented_number(self):
        lines = [((0, 0, 100, 20), 0.8)]
        assert fc.mean_ocr_confidence_for_bbox(lines, (500, 500, 520, 520)) is None

    def test_a_degenerate_bbox_is_none(self):
        assert fc.mean_ocr_confidence_for_bbox([((0, 0, 10, 10), 0.9)], (5, 5, 5, 5)) is None


class TestRequiresReview:
    def test_a_clean_document_needs_no_review(self):
        decision = review.decide(
            field_results=[_result("invoice_number"), _result("amount")],
            mandatory_keys={"invoice_number", "amount"},
        )
        assert decision.requires_review is False
        assert decision.to_dict() == {"requires_review": False}

    def test_a_missing_mandatory_field_requires_review(self):
        decision = review.decide(
            field_results=[_result("invoice_number", status="not_found")],
            mandatory_keys={"invoice_number"},
        )
        assert decision.requires_review is True
        assert decision.missing_mandatory == ["invoice_number"]
        assert review.MISSING_MANDATORY in decision.reasons

    def test_a_low_confidence_mandatory_field_also_counts_as_missing(self):
        """The whole point of that status is that nobody should rely on
        the value without looking at it."""
        decision = review.decide(
            field_results=[_result("amount", status="low_confidence")],
            mandatory_keys={"amount"},
        )
        assert decision.requires_review is True
        assert decision.missing_mandatory == ["amount"]

    def test_a_mandatory_field_that_was_never_requested_is_not_a_failure(self):
        """Otherwise every document whose type has more fields than were
        requested would be flagged."""
        decision = review.decide(field_results=[_result("author")], mandatory_keys={"contract_value"})
        assert decision.requires_review is False

    def test_a_validation_failure_requires_review(self):
        decision = review.decide(validation_failures=["subtotal_plus_tax_equals_total"])
        assert decision.requires_review is True
        assert review.VALIDATION_FAILED in decision.reasons

    def test_poor_image_quality_requires_review(self):
        class Page:
            page_number = 3
            flags = {"poor_image_quality": True}

        decision = review.decide(pages=[Page()])
        assert decision.requires_review is True
        assert decision.poor_quality_pages == [3]
        assert review.POOR_IMAGE_QUALITY in decision.reasons

    def test_a_repaired_file_requires_review(self):
        decision = review.decide(file_repaired=True)
        assert decision.requires_review is True
        assert review.FILE_REPAIRED in decision.reasons

    def test_confidence_alone_is_no_longer_an_input(self):
        """The rule this replaces was `mean page confidence < 0.95`. A
        document whose body text read poorly but whose fields all came out
        is not something a human needs to re-key."""
        decision = review.decide(
            field_results=[_result("amount")], mandatory_keys={"amount"},
            pages=[type("P", (), {"page_number": 1, "flags": {}})()],
        )
        assert decision.requires_review is False

    def test_every_reason_that_fired_is_reported_not_just_the_first(self):
        class Page:
            page_number = 1
            flags = {"poor_image_quality": True}

        decision = review.decide(
            field_results=[_result("amount", status="not_found")],
            mandatory_keys={"amount"},
            validation_failures=["date_order"],
            pages=[Page()],
            file_repaired=True,
        )
        assert set(decision.reasons) == {
            review.MISSING_MANDATORY, review.VALIDATION_FAILED,
            review.POOR_IMAGE_QUALITY, review.FILE_REPAIRED,
        }


# ----------------------------------------------------------------------
# Vendor name truncation (Phase 3f).
#
# Measured before Step 7: `vendor_supplier_name expected='Contoso
# Supplies PLC' actual='Contoso'` on a hybrid page. The cause was the OLD
# routing rule, not the extractor -- a typed header over a scanned body
# made the page "native", so only the header's text layer was read and
# the name was whatever fragment that layer held. Routing fixed it, and
# the field now scores 39/39 on the corpus.
#
# These pin the extractor's half so a regression cannot return quietly:
# a multi-word company name must survive every path that can produce one.
# ----------------------------------------------------------------------
from ocr_worker.extraction import FieldSpec, extract_requested_fields  # noqa: E402


def _vendor(text, lines_by_page=None):
    results = extract_requested_fields(
        specs=[FieldSpec(field_key="vendor_supplier_name", data_type="text")],
        text=text, zone_fields={}, lines_by_page=lines_by_page,
    )
    return results[0]


@pytest.mark.parametrize("line", [
    "Vendor / Supplier: Contoso Supplies PLC",
    "Vendor: Contoso Supplies PLC",
    "Supplier: Contoso Supplies PLC",
    "Seller: Contoso Supplies PLC",
    "Billed By: Contoso Supplies PLC",
])
def test_a_multi_word_vendor_name_is_never_clipped_to_its_first_word(line):
    assert _vendor(line).value == "Contoso Supplies PLC"


def test_a_vendor_name_followed_by_another_label_keeps_all_of_its_own_words():
    """`_clip_at_next_label` exists to stop a value running into the NEXT
    field. It must cut at that label, not inside the name."""
    result = _vendor("Supplier: Contoso Supplies PLC  Cost Centre: CC-4450")
    assert result.value == "Contoso Supplies PLC"


def test_a_vendor_name_containing_a_word_like_a_label_survives():
    """'Invoice' inside a company name is part of the name -- clipping
    needs a real label marker, not a bare word."""
    assert _vendor("Vendor: Invoice Solutions International Ltd").value == (
        "Invoice Solutions International Ltd"
    )


def test_the_letterhead_fallback_returns_the_whole_company_line():
    """No Vendor:/Supplier: label anywhere -- the name is only ever a
    letterhead. The whole line is the name."""
    lines = {1: [
        ("TAX INVOICE", (0, 0, 200, 20)),
        ("Contoso Supplies PLC", (0, 30, 300, 50)),
        ("12 Example Road, Colombo 03, Sri Lanka", (0, 60, 400, 80)),
    ]}
    result = _vendor("TAX INVOICE\nContoso Supplies PLC\n12 Example Road, Colombo 03, Sri Lanka", lines)
    assert result.value == "Contoso Supplies PLC"
    # A positional guess, never silently trusted.
    assert result.status == "low_confidence"


def test_a_document_level_poor_image_quality_summary_is_emitted():
    """`eval/run.py` scores the quality flag from the DOCUMENT-level
    `flags.poor_image_quality`. With only the per-page flag set it saw 45
    false negatives and 0.0 recall -- a gate reading a key nobody wrote."""
    class Page:
        page_number = 2
        flags = {"poor_image_quality": True}

    out = review.decide(pages=[Page()]).to_dict()
    assert out["poor_image_quality"] is True
    assert out["poor_quality_pages"] == [2]


def test_a_clean_document_has_no_poor_image_quality_key_at_all():
    """Absent, not False: `flags` carries only what is true."""
    out = review.decide(pages=[type("P", (), {"page_number": 1, "flags": {}})()]).to_dict()
    assert "poor_image_quality" not in out
