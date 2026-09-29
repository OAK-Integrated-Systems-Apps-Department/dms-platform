package com.oak.dms.document;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The row-to-map rule now has three callers -- the {@code .completed}
 * event listener, the poll-time re-apply, and {@code OcrFieldsReconciler}.
 * It used to be two hand-copied loops, which was fine until it became
 * three. These tests pin the skip rule itself, so the three cannot drift
 * apart in what they consider applicable.
 */
class OcrFieldResultMapperTest {

    private DocumentOcrFieldResult row(String key, String value, String status, Double confidence) {
        return DocumentOcrFieldResult.builder()
                .fieldResultId(UUID.randomUUID())
                .fieldKey(key)
                .value(value)
                .status(status)
                .confidence(confidence == null ? null : BigDecimal.valueOf(confidence))
                .build();
    }

    @Test
    void mapsAnExtractedValueWithItsConfidence() {
        Map<String, Object> out = OcrFieldResultMapper.toOcrFieldsRaw(
                List.of(row("invoice_number", "INV-1", "extracted", 0.93)));

        assertThat(out).containsOnlyKeys("invoice_number");
        @SuppressWarnings("unchecked")
        Map<String, Object> entry = (Map<String, Object>) out.get("invoice_number");
        assertThat(entry)
                .containsEntry("value", "INV-1")
                .containsEntry("confidence", BigDecimal.valueOf(0.93));
    }

    @Test
    void skipsNotFoundRows() {
        /* A not_found row exists precisely to record that the field was
         * looked for and missed (V35's own comment), so the upload page can
         * show "not found" rather than a blank. It must never become a
         * metadata value. */
        assertThat(OcrFieldResultMapper.toOcrFieldsRaw(
                List.of(row("invoice_number", "something", "not_found", 0.1))))
                .isEmpty();
    }

    @Test
    void skipsRowsWithNoValue() {
        assertThat(OcrFieldResultMapper.toOcrFieldsRaw(
                List.of(row("invoice_number", null, "extracted", 0.9))))
                .isEmpty();
    }

    @Test
    void keepsLowConfidenceRows() {
        /* Deliberate: a low_confidence value is returned either way and
         * flagged for review, never dropped. Dropping it here would make
         * the review flow show nothing to review. */
        assertThat(OcrFieldResultMapper.toOcrFieldsRaw(
                List.of(row("amount", "2500.00", "low_confidence", 0.4))))
                .containsKey("amount");
    }

    @Test
    void omitsTheConfidenceKeyEntirelyWhenThereIsNone() {
        /* applyOcrFields reads confidence with `instanceof Number`, so a
         * null would simply be ignored -- but omitting the key keeps the
         * map the same shape the HTTP path produces. */
        Map<String, Object> out = OcrFieldResultMapper.toOcrFieldsRaw(
                List.of(row("currency", "GBP", "extracted", null)));

        @SuppressWarnings("unchecked")
        Map<String, Object> entry = (Map<String, Object>) out.get("currency");
        assertThat(entry).containsOnlyKeys("value");
    }

    @Test
    void preservesInsertionOrder() {
        Map<String, Object> out = OcrFieldResultMapper.toOcrFieldsRaw(List.of(
                row("invoice_number", "INV-1", "extracted", 0.9),
                row("amount", "10.00", "extracted", 0.9),
                row("currency", "GBP", "extracted", 0.9)));

        assertThat(out.keySet()).containsExactly("invoice_number", "amount", "currency");
    }

    @Test
    void anEmptyInputProducesAnEmptyMapRatherThanNull() {
        assertThat(OcrFieldResultMapper.toOcrFieldsRaw(List.of())).isEmpty();
    }

    /**
     * V39 added {@code raw_value}. The metadata value must stay the NORMALISED
     * one: a {@code date} field handed "10/08/2026" instead of "2026-08-10"
     * fails to parse in DocumentMetadataServiceImpl and the field silently
     * stays blank -- the exact failure normalisation exists to prevent.
     */
    @Test
    void rawValueIsCarriedOnTheRowButNeverBecomesTheMetadataValue() {
        DocumentOcrFieldResult dateRow = DocumentOcrFieldResult.builder()
                .fieldResultId(UUID.randomUUID())
                .fieldKey("invoice_date")
                .value("2026-08-10")
                .rawValue("10/08/2026")
                .status("extracted")
                .confidence(BigDecimal.valueOf(0.9))
                .build();

        assertThat(dateRow.getRawValue()).isEqualTo("10/08/2026");

        Map<String, Object> out = OcrFieldResultMapper.toOcrFieldsRaw(List.of(dateRow));
        @SuppressWarnings("unchecked")
        Map<String, Object> entry = (Map<String, Object>) out.get("invoice_date");
        assertThat(entry).containsEntry("value", "2026-08-10");
        assertThat(entry).doesNotContainKey("raw_value");
        assertThat(entry).doesNotContainKey("rawValue");
    }

    /**
     * A row written before Phase 3 has no raw_value. It must map exactly as it
     * always did -- the column is additive, not a new requirement.
     */
    @Test
    void aPrePhase3RowWithNoRawValueMapsUnchanged() {
        Map<String, Object> out = OcrFieldResultMapper.toOcrFieldsRaw(
                List.of(row("invoice_number", "INV-1", "extracted", 0.93)));

        @SuppressWarnings("unchecked")
        Map<String, Object> entry = (Map<String, Object>) out.get("invoice_number");
        assertThat(entry).containsEntry("value", "INV-1");
    }
}
