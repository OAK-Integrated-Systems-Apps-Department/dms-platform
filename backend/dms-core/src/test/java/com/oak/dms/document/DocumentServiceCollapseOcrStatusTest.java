package com.oak.dms.document;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link DocumentServiceImpl#collapseOcrStatus} -- the
 * external polling contract the frontend branches on. Phase 1 B.8 added
 * the "queued" bucket: since B.1, upload/rerun set ocr_status='pending'
 * (queued, unclaimed) and only an ocr-worker claim sets 'processing', so
 * collapsing both into "processing" would hide a now-real distinction.
 */
class DocumentServiceCollapseOcrStatusTest {

    @Test
    void pendingCollapsesToQueuedNotProcessing() {
        assertThat(DocumentServiceImpl.collapseOcrStatus("pending")).isEqualTo("queued");
    }

    @Test
    void processingStaysProcessing() {
        assertThat(DocumentServiceImpl.collapseOcrStatus("processing")).isEqualTo("processing");
    }

    @Test
    void completedAndNotRequiredBothCollapseToCompleted() {
        assertThat(DocumentServiceImpl.collapseOcrStatus("completed")).isEqualTo("completed");
        assertThat(DocumentServiceImpl.collapseOcrStatus("not_required")).isEqualTo("completed");
    }

    @Test
    void failedAndUnsupportedBothCollapseToFailed() {
        assertThat(DocumentServiceImpl.collapseOcrStatus("failed")).isEqualTo("failed");
        assertThat(DocumentServiceImpl.collapseOcrStatus("unsupported")).isEqualTo("failed");
    }

    @Test
    void anUnrecognizedValueFallsBackToProcessingRatherThanLeakingItToTheClient() {
        assertThat(DocumentServiceImpl.collapseOcrStatus("something_new")).isEqualTo("processing");
    }
}
