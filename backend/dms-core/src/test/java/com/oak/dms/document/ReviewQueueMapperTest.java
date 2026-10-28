package com.oak.dms.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oak.dms.document.dto.ReviewQueueItemResponse;
import com.oak.dms.ocrworker.ProcessingJob;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The queue's whole value is the "why" column. These tests pin that a row
 * NEVER reaches the reviewer without one -- including the two cases where
 * it would be easiest to end up blank: a failed version (which has no
 * review_reasons at all, because it never got far enough to compute any)
 * and a version flagged with reasons we cannot parse.
 */
class ReviewQueueMapperTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private Document document(String title) {
        return Document.builder()
                .documentId(UUID.randomUUID())
                .tenantId(UUID.randomUUID())
                .title(title)
                .build();
    }

    private DocumentVersion version(String ocrStatus, boolean requiresReview, String reasons) {
        return DocumentVersion.builder()
                .versionId(UUID.randomUUID())
                .documentId(UUID.randomUUID())
                .originalFilename("invoice.pdf")
                .mimeType("application/pdf")
                .createdAt(OffsetDateTime.now())
                .ocrStatus(ocrStatus)
                .requiresReview(requiresReview)
                .reviewReasons(reasons)
                .build();
    }

    private ProcessingJob failedJob(String lastError) {
        return ProcessingJob.builder()
                .jobId(UUID.randomUUID())
                .status("failed")
                .lastError(lastError)
                .build();
    }

    @Test
    void namesTheMissingMandatoryFields() {
        ReviewQueueItemResponse item = ReviewQueueMapper.toItem(
                document("Invoice 41"),
                version("completed", true, """
                        {"reasons":["missing_mandatory_field"],
                         "missing_mandatory":["invoice_number","total_amount"],
                         "validation_failures":[],"poor_quality_pages":[]}"""),
                null, mapper);

        assertThat(item.reasons()).containsExactly("missing_mandatory_field");
        assertThat(item.missingMandatory()).containsExactly("invoice_number", "total_amount");
        assertThat(item.summary())
                .isEqualTo("2 required fields were not found: invoice_number, total_amount.");
        assertThat(item.errorCode()).isNull();
    }

    @Test
    void usesSingularPhrasingForOneFieldAndOnePage() {
        ReviewQueueItemResponse item = ReviewQueueMapper.toItem(
                document("Invoice 42"),
                version("completed", true, """
                        {"reasons":["missing_mandatory_field","poor_image_quality"],
                         "missing_mandatory":["total_amount"],
                         "validation_failures":[],"poor_quality_pages":[3]}"""),
                null, mapper);

        assertThat(item.summary())
                .isEqualTo("1 required field was not found: total_amount; page 3 is poor quality.");
    }

    @Test
    void readsValidationRuleNamesAsProse() {
        ReviewQueueItemResponse item = ReviewQueueMapper.toItem(
                document("Invoice 43"),
                version("completed", true, """
                        {"reasons":["validation_failed"],"missing_mandatory":[],
                         "validation_failures":["subtotal_plus_tax_equals_total"],
                         "poor_quality_pages":[]}"""),
                null, mapper);

        assertThat(item.summary())
                .isEqualTo("A validation check failed: subtotal plus tax equals total.");
        assertThat(item.validationFailures()).containsExactly("subtotal_plus_tax_equals_total");
    }

    @Test
    void aFailedVersionCarriesItsErrorCodeAndFriendlyMessage() {
        ReviewQueueItemResponse item = ReviewQueueMapper.toItem(
                document("Scan 7"),
                version("failed", false, null),
                failedJob("ENCRYPTED_PDF: password required"),
                mapper);

        assertThat(item.errorCode()).isEqualTo("ENCRYPTED_PDF");
        assertThat(item.reasons()).startsWith(ReviewQueueMapper.OCR_FAILED);
        assertThat(item.summary()).isNotBlank();
        // The reviewer must never see the raw code or a stack trace.
        assertThat(item.summary()).doesNotContain("ENCRYPTED_PDF");
        assertThat(item.requiresReview()).isFalse();
    }

    @Test
    void aFailedVersionWithNoJobStillSaysSomething() {
        ReviewQueueItemResponse item = ReviewQueueMapper.toItem(
                document("Scan 8"), version("failed", false, null), null, mapper);

        assertThat(item.reasons()).containsExactly(ReviewQueueMapper.OCR_FAILED);
        assertThat(item.summary()).isNotBlank();
    }

    @Test
    void unparseableReasonsStillProduceARow() {
        ReviewQueueItemResponse item = ReviewQueueMapper.toItem(
                document("Invoice 44"),
                version("completed", true, "{not json"),
                null, mapper);

        assertThat(item.reasons()).isEmpty();
        assertThat(item.summary()).isEqualTo("Flagged for review, but no reason was recorded.");
    }

    @Test
    void aRepairedFileIsExplainedInItsOwnWords() {
        ReviewQueueItemResponse item = ReviewQueueMapper.toItem(
                document("Invoice 45"),
                version("completed", true, """
                        {"reasons":["file_repaired"],"missing_mandatory":[],
                         "validation_failures":[],"poor_quality_pages":[]}"""),
                null, mapper);

        assertThat(item.summary()).contains("repaired");
    }
}
