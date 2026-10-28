package com.oak.dms.document;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** Nearly the same text -> nearly the same fingerprint; different text -> far apart. */
class DocumentFingerprintsTest {

    private static final String REPORT = """
            The quarterly board report summarises revenue, costs and the outlook for the coming quarter.
            Revenue grew twelve percent compared with the first quarter, driven by the two new branches
            opened in Kandy and Galle, while operating costs stayed flat thanks to the procurement review.
            The board is asked to approve the revised budget for the third quarter and to note the risks
            described in the appendix, including the delay to the warehouse system and currency movements.
            Prepared by the finance team for the meeting on the first of October.
            """;

    private static long fingerprint(String name, String text) {
        return DocumentFingerprints.of(name, text.getBytes(StandardCharsets.UTF_8)).orElseThrow();
    }

    @Test
    void aLightlyEditedCopyUnderAnotherName_isSimilar() {
        long original = fingerprint("report.txt", REPORT);
        long edited = fingerprint("Board report FINAL v2.md",
                REPORT.replace("twelve percent", "thirteen percent").replace("first of October", "second of October"));

        assertThat(Long.bitCount(original ^ edited)).isLessThanOrEqualTo(DocumentFingerprints.SIMILAR_WITHIN_BITS);
        assertThat(DocumentFingerprints.similarity(original, edited)).isGreaterThanOrEqualTo(90);
    }

    @Test
    void aDifferentDocument_isNot() {
        long report = fingerprint("report.txt", REPORT);
        long other = fingerprint("policy.txt", """
                This policy sets out how staff request annual leave, how managers approve it and how unused
                days are carried into the next year. Requests are made through the HR system at least two weeks
                ahead; longer absences need the department head's approval. Up to five unused days may be
                carried forward and must be taken before the end of March. Sick leave is covered separately.
                """);

        assertThat(Long.bitCount(report ^ other)).isGreaterThan(DocumentFingerprints.SIMILAR_WITHIN_BITS);
    }

    @Test
    void tooShort_orUnreadable_hasNoFingerprint() {
        assertThat(DocumentFingerprints.of("note.txt", "Just a few words".getBytes())).isEmpty();
        assertThat(DocumentFingerprints.of("photo.png", REPORT.getBytes())).isEmpty();
        assertThat(DocumentFingerprints.of("broken.pdf", "not a pdf at all".getBytes())).isEmpty();
    }
}
