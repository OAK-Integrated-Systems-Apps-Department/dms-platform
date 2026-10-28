package com.oak.dms.ocrworker;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ocr-worker's status codes already mean the right thing to a browser, so
 * the client passes them through rather than collapsing everything into a
 * 500. What it must NOT pass through is the worker's own wording: a
 * reviewer is not the person who chose the mime type or set the timeout.
 *
 * These tests pin the sentence each status turns into, and -- more
 * importantly -- that an internal failure says nothing about internals.
 */
class OcrWorkerClientTest {

    @Test
    void eachRecoverableStatusGetsItsOwnSentence() {
        String notFound = OcrWorkerClient.friendlyFor(HttpStatus.NOT_FOUND);
        String notScanned = OcrWorkerClient.friendlyFor(HttpStatus.CONFLICT);
        String unreadable = OcrWorkerClient.friendlyFor(HttpStatus.UNPROCESSABLE_ENTITY);
        String tooSlow = OcrWorkerClient.friendlyFor(HttpStatus.GATEWAY_TIMEOUT);

        assertThat(notFound).isNotBlank();
        assertThat(notScanned).contains("scan");
        // The two the reviewer can actually act on say what to do next.
        assertThat(unreadable).contains("larger area");
        assertThat(tooSlow).contains("smaller selection");
        assertThat(java.util.Set.of(notFound, notScanned, unreadable, tooSlow)).hasSize(4);
    }

    @Test
    void anInternalFailureSaysNothingAboutInternals() {
        for (HttpStatus status : java.util.List.of(
                HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN, HttpStatus.INTERNAL_SERVER_ERROR)) {
            String message = OcrWorkerClient.friendlyFor(status);

            assertThat(message).isEqualTo("Could not read that part of the document.");
            assertThat(message.toLowerCase())
                    .doesNotContain("token")
                    .doesNotContain("ocr-worker")
                    .doesNotContain("internal");
        }
    }

    @Test
    void noMessageIsAStackTraceOrACode() {
        for (HttpStatus status : HttpStatus.values()) {
            String message = OcrWorkerClient.friendlyFor(status);

            assertThat(message).isNotBlank();
            assertThat(message).doesNotContain("Exception");
            assertThat(message).doesNotContain("at com.oak");
            assertThat(message).endsWith(".");
        }
    }
}
