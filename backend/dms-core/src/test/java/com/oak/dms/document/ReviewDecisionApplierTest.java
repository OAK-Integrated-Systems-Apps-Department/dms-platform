package com.oak.dms.document;

import com.oak.dms.document.dto.ReviewFieldResponse;
import com.oak.dms.document.dto.SaveReviewRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The accuracy measurement this whole table exists for is only as good as
 * what gets written into it, and there are two ways to ruin it quietly:
 * count an edit that changed nothing as a correction (inflating the error
 * rate), or take the client's word for what OCR had said (recording a
 * correction as an acceptance). Both are pinned here.
 *
 * The other half is {@link ReviewDecisionApplier#unsettled}: a queue that
 * empties itself the moment anything is saved is worse than a long queue,
 * because it is lying.
 */
class ReviewDecisionApplierTest {

    private ReviewFieldResponse field(String key, Object value, String status, boolean mandatory) {
        return new ReviewFieldResponse(key, key, mandatory, "text", value, null, 0.9, status, null);
    }

    private SaveReviewRequest.Decision accept(String key) {
        return new SaveReviewRequest.Decision(key, "accepted", null, null);
    }

    private SaveReviewRequest.Decision correct(String key, String value) {
        return new SaveReviewRequest.Decision(key, "corrected", value, "typed");
    }

    // ------------------------------------------------------------------
    // What gets recorded
    // ------------------------------------------------------------------

    @Test
    void anAcceptanceRecordsTheServersOwnValueNotTheClientsEcho() {
        ReviewFieldResponse invoice = field("invoice_number", "INV-4471", "extracted", false);

        // The client sent a different value alongside "accepted". Trusting
        // it would store a correction as an acceptance.
        String value = ReviewDecisionApplier.valueFor(
                invoice, new SaveReviewRequest.Decision("invoice_number", "accepted", "SOMETHING ELSE", null));

        assertThat(value).isEqualTo("INV-4471");
    }

    @Test
    void anEditThatChangedNothingIsAnAcceptanceNotACorrection() {
        ReviewFieldResponse invoice = field("invoice_number", "INV-4471", "extracted", false);

        String action = ReviewDecisionApplier.effectiveAction(invoice, correct("invoice_number", "INV-4471"));

        // Otherwise opening the editor and closing it would count against
        // OCR's measured accuracy.
        assertThat(action).isEqualTo(DocumentOcrCorrection.ACTION_ACCEPTED);
    }

    @Test
    void arealEditIsACorrection() {
        ReviewFieldResponse invoice = field("invoice_number", "INV-4471", "extracted", false);

        assertThat(ReviewDecisionApplier.effectiveAction(invoice, correct("invoice_number", "INV-4472")))
                .isEqualTo(DocumentOcrCorrection.ACTION_CORRECTED);
    }

    @Test
    void fillingInAFieldOcrNeverFoundIsACorrection() {
        ReviewFieldResponse total = field("total_amount", null, "not_found", true);

        assertThat(ReviewDecisionApplier.effectiveAction(total, correct("total_amount", "1234.56")))
                .isEqualTo(DocumentOcrCorrection.ACTION_CORRECTED);
    }

    @Test
    void aDecisionAboutAFieldTheDocumentDoesNotHaveIsDropped() {
        List<ReviewFieldResponse> fields = List.of(field("invoice_number", "INV-1", "extracted", false));

        assertThat(ReviewDecisionApplier.settledValues(fields, List.of(correct("not_a_field", "x"))))
                .isEmpty();
    }

    @Test
    void aMultiSelectValueIsFlattenedTheSameWayEverywhere() {
        ReviewFieldResponse tags = field("tags", List.of("finance", "q3"), "extracted", false);

        assertThat(ReviewDecisionApplier.valueFor(tags, accept("tags"))).isEqualTo("finance, q3");
    }

    // ------------------------------------------------------------------
    // Whether the document leaves the queue
    // ------------------------------------------------------------------

    @Test
    void aDocumentWhoseEveryProblemFieldWasDecidedLeavesTheQueue() {
        List<ReviewFieldResponse> fields = List.of(
                field("invoice_number", "INV-1", "extracted", false),
                field("total_amount", null, "not_found", true));

        List<String> remaining = ReviewDecisionApplier.unsettled(
                fields, List.of(correct("total_amount", "1234.56")));

        // invoice_number needed no attention, so not deciding on it is
        // not a reason to keep the document queued.
        assertThat(remaining).isEmpty();
    }

    @Test
    void aMandatoryFieldLeftEmptyKeepsTheDocumentQueued() {
        List<ReviewFieldResponse> fields = List.of(field("total_amount", null, "not_found", true));

        // They accepted "nothing found" -- which settles nothing: the
        // document is still missing a value it is required to have.
        assertThat(ReviewDecisionApplier.unsettled(fields, List.of(accept("total_amount"))))
                .containsExactly("total_amount");
    }

    @Test
    void aLowConfidenceFieldNobodyLookedAtKeepsTheDocumentQueued() {
        List<ReviewFieldResponse> fields = List.of(
                field("invoice_number", "INV-1", "extracted", false),
                field("vat_number", "GB12 3456", "low_confidence", false));

        assertThat(ReviewDecisionApplier.unsettled(fields, List.of(accept("invoice_number"))))
                .containsExactly("vat_number");
    }

    @Test
    void acceptingALowConfidenceValueSettlesIt() {
        List<ReviewFieldResponse> fields = List.of(field("vat_number", "GB12 3456", "low_confidence", false));

        assertThat(ReviewDecisionApplier.unsettled(fields, List.of(accept("vat_number")))).isEmpty();
    }

    @Test
    void anEmptyReviewLeavesEveryProblemOutstanding() {
        List<ReviewFieldResponse> fields = List.of(
                field("total_amount", null, "not_found", true),
                field("vat_number", "GB12", "low_confidence", false),
                field("invoice_number", "INV-1", "extracted", false));

        assertThat(ReviewDecisionApplier.unsettled(fields, List.of()))
                .containsExactly("total_amount", "vat_number");
    }

    @Test
    void aBlankCorrectionDoesNotSatisfyAMandatoryField() {
        List<ReviewFieldResponse> fields = List.of(field("total_amount", null, "not_found", true));

        assertThat(ReviewDecisionApplier.unsettled(fields, List.of(correct("total_amount", "   "))))
                .containsExactly("total_amount");
    }

    @Test
    void aCleanDocumentNobodyTouchedIsAlreadySettled() {
        List<ReviewFieldResponse> fields = List.of(
                field("invoice_number", "INV-1", "extracted", true),
                field("total_amount", "1234.56", "extracted", true));

        assertThat(ReviewDecisionApplier.unsettled(fields, List.of())).isEmpty();
    }

    // ------------------------------------------------------------------
    // multi_select round-trip
    // ------------------------------------------------------------------

    @Test
    void aCorrectedMultiSelectValueIsSplitBackIntoItsParts() {
        ReviewFieldResponse tags = new ReviewFieldResponse(
                "tags", "Tags", false, "multi_select", List.of("finance"), null, 0.9, "extracted", null);

        // It is joined for display and stored joined in the correction log
        // (one text column, one decision), but document_metadata_values
        // holds one row per selected option -- saving the joined string
        // would create a single tag literally named "finance, q3".
        assertThat(DocumentServiceImpl.splitIfMultiSelect(tags, "finance, q3"))
                .isEqualTo(List.of("finance", "q3"));
    }

    @Test
    void aSingleValuedFieldIsLeftAloneEvenIfItContainsACommaK() {
        ReviewFieldResponse description = new ReviewFieldResponse(
                "description", "Description", false, "text", null, null, null, "not_found", null);

        // A comma in ordinary text is just a comma.
        assertThat(DocumentServiceImpl.splitIfMultiSelect(description, "Widgets, blue, large"))
                .isEqualTo("Widgets, blue, large");
    }

    @Test
    void anEmptiedMultiSelectClearsItRatherThanStoringABlankOption() {
        ReviewFieldResponse tags = new ReviewFieldResponse(
                "tags", "Tags", false, "multi_select", List.of("finance"), null, 0.9, "extracted", null);

        assertThat(DocumentServiceImpl.splitIfMultiSelect(tags, "")).isEqualTo(List.of());
        assertThat(DocumentServiceImpl.splitIfMultiSelect(tags, "finance, , q3"))
                .isEqualTo(List.of("finance", "q3"));
    }
}
