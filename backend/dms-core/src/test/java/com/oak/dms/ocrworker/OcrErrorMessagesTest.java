package com.oak.dms.ocrworker;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link OcrErrorMessages} -- no Spring context, pure logic.
 */
class OcrErrorMessagesTest {

    @Test
    void parsesTheLeadingCodeFromOcrWorkersFormattedErrorString() {
        assertThat(OcrErrorMessages.codeOf("UNSUPPORTED_FORMAT: image/heic is not supported")).isEqualTo("UNSUPPORTED_FORMAT");
        assertThat(OcrErrorMessages.codeOf("ENCRYPTED_PDF: invoice.pdf is password-protected")).isEqualTo("ENCRYPTED_PDF");
    }

    @Test
    void codeOfReturnsNullForNullOrBlankInput() {
        assertThat(OcrErrorMessages.codeOf(null)).isNull();
        assertThat(OcrErrorMessages.codeOf("")).isNull();
        assertThat(OcrErrorMessages.codeOf("   ")).isNull();
    }

    @Test
    void codeOfFallsBackToTheWholeStringWhenThereIsNoColon() {
        // Defensive -- every real OcrEngineError.formatted() call always
        // includes "CODE: message", but this must not throw on a malformed
        // or legacy raw exception string.
        assertThat(OcrErrorMessages.codeOf("some raw exception text with no code")).isEqualTo("some raw exception text with no code");
    }

    @Test
    void mapsEveryKnownCodeToADistinctFriendlyMessage() {
        String[] codes = {
                "UNSUPPORTED_FORMAT", "ENCRYPTED_PDF", "CORRUPT_FILE", "TOO_MANY_PAGES",
                "FILE_TOO_LARGE", "TIMEOUT", "WORKER_CRASH", "ENGINE_ERROR", "LOW_IMAGE_QUALITY",
        };
        for (String code : codes) {
            String message = OcrErrorMessages.friendlyMessageFor(code);
            assertThat(message).isNotBlank();
            assertThat(message).doesNotContain(code); // never leaks the raw code into user-facing text
        }
    }

    @Test
    void fallsBackToAGenericMessageForAnUnrecognizedCode() {
        assertThat(OcrErrorMessages.friendlyMessageFor("SOME_FUTURE_CODE")).isEqualTo("OCR could not process this document.");
    }

    @Test
    void friendlyMessageForReturnsNullForNullCode() {
        assertThat(OcrErrorMessages.friendlyMessageFor(null)).isNull();
    }
}
