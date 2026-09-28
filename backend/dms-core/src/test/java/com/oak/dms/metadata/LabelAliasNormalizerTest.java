package com.oak.dms.metadata;

import com.oak.dms.common.dto.ErrorResponse;
import com.oak.dms.common.exception.FieldValidationException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Unit tests for {@link LabelAliasNormalizer} - the authoritative
 * normalization/validation of OCR label hints (SPEC §2). No Spring context:
 * the class is pure.
 */
class LabelAliasNormalizerTest {

    private static List<ErrorResponse.FieldValidationError> errorsOf(List<String> raw) {
        FieldValidationException ex = catchThrowableOfType(FieldValidationException.class,
                () -> LabelAliasNormalizer.normalize(raw));
        assertThat(ex).as("expected a FieldValidationException for %s", raw).isNotNull();
        return ex.getFieldErrors();
    }

    @Test
    void nullOrEmptyListMeansNoHints() {
        assertThat(LabelAliasNormalizer.normalize(null)).isEmpty();
        assertThat(LabelAliasNormalizer.normalize(List.of())).isEmpty();
    }

    @Test
    void trimsAndCollapsesEveryInternalWhitespaceRunIncludingNonBreakingSpaces() {
        assertThat(LabelAliasNormalizer.normalize(List.of(
                "  PO   Number ",
                "\tInvoice\n No\t",
                "Vendor  / Supplier",
                " Ref # ")))
                .containsExactly("PO Number", "Invoice No", "Vendor / Supplier", "Ref #");
    }

    @Test
    void keepsOrderAndOriginalCasing() {
        assertThat(LabelAliasNormalizer.normalize(List.of("Purchase Order Number", "PO Number", "Number")))
                .containsExactly("Purchase Order Number", "PO Number", "Number");
    }

    @Test
    void dropsCaseInsensitiveDuplicatesSilentlyKeepingTheFirstOccurrence() {
        assertThat(LabelAliasNormalizer.normalize(List.of("PO Number", "Order No", "po  number", "ORDER NO", "Number")))
                .containsExactly("PO Number", "Order No", "Number");
    }

    @Test
    void acceptsTheBoundaryLengthsAndShortSymbolHints() {
        String hundred = "a".repeat(100);
        assertThat(LabelAliasNormalizer.normalize(List.of("po", hundred, "po#", "ref. no")))
                .containsExactly("po", hundred, "po#", "ref. no");
    }

    @Test
    void measuresLengthInCharactersNotUtf16UnitsLikePostgres() {
        // 99 emoji (two UTF-16 units each) + one letter = 100 characters:
        // exactly what VARCHAR(100)/char_length accept.
        String hundredCodePoints = "😀".repeat(99) + "a";
        assertThat(hundredCodePoints.length()).isEqualTo(199);
        assertThat(LabelAliasNormalizer.normalize(List.of(hundredCodePoints))).containsExactly(hundredCodePoints);

        assertThat(errorsOf(List.of("😀".repeat(100) + "a")))
                .singleElement()
                .satisfies(e -> assertThat(e.message()).contains("at most 100"));
    }

    @Test
    void rejectsTooShortEntriesUnderTheLabelAliasesFieldName() {
        List<ErrorResponse.FieldValidationError> errors = errorsOf(List.of("PO Number", " x "));
        assertThat(errors).singleElement().satisfies(e -> {
            assertThat(e.field()).isEqualTo("labelAliases");
            assertThat(e.message()).contains("#2").contains("\"x\"").contains("at least 2");
        });
    }

    @Test
    void rejectsBlankAndNullEntriesAsTooShort() {
        List<ErrorResponse.FieldValidationError> errors = errorsOf(Arrays.asList("   ", null));
        assertThat(errors).hasSize(2)
                .allSatisfy(e -> {
                    assertThat(e.field()).isEqualTo("labelAliases");
                    assertThat(e.message()).contains("at least 2");
                });
    }

    @Test
    void rejectsTooLongEntries() {
        assertThat(errorsOf(List.of("a".repeat(101))))
                .singleElement()
                .satisfies(e -> assertThat(e.message()).contains("at most 100"));
    }

    @Test
    void rejectsEntriesWithNoLetterOrDigit() {
        assertThat(errorsOf(List.of("--", "# :")))
                .hasSize(2)
                .allSatisfy(e -> assertThat(e.message()).contains("letter or digit"));
    }

    @Test
    void acceptsNonAsciiLetters() {
        assertThat(LabelAliasNormalizer.normalize(List.of("Numéro de commande", "Bestellnr."))).hasSize(2);
    }

    /**
     * The de-dup must use the same case fold as Postgres' lower() in V40's
     * uq_mfla_field_scope_alias (per character, simple case mapping - checked
     * on the dev database: lower('İD') = lower('id') and
     * lower('ΟΔΟΣ') = lower('οδοσ') are both true). String#toLowerCase keeps
     * each of these pairs apart (dotted I -> "i" + combining dot, word-final
     * Sigma -> final sigma), so both entries reached the INSERT and the save
     * died on the unique index as a 409 instead of the silent keep-the-first.
     */
    @Test
    void dropsDuplicatesUnderTheSamePerCharacterCaseFoldAsPostgresLower() {
        assertThat(LabelAliasNormalizer.normalize(List.of("İD", "id")))
                .containsExactly("İD");
        assertThat(LabelAliasNormalizer.normalize(List.of("İRSALİYE NO", "irsaliye no")))
                .containsExactly("İRSALİYE NO");
        assertThat(LabelAliasNormalizer.normalize(List.of("ΟΔΟΣ", "οδοσ")))
                .containsExactly("ΟΔΟΣ");

        assertThat(LabelAliasNormalizer.caseFoldKey("İD")).isEqualTo("id");
        assertThat(LabelAliasNormalizer.caseFoldKey("ΟΔΟΣ")).isEqualTo("οδοσ");
    }

    @Test
    void stripsInvisibleFormatCharactersPickedUpByCopyPaste() {
        assertThat(LabelAliasNormalizer.normalize(List.of(
                "PO​ No",                // zero-width space
                "﻿Invoice Number",       // BOM / zero-width no-break space
                "Refer­ence",            // soft hyphen
                "‎Vendor‏",         // left-to-right / right-to-left marks
                "Cost ​ Centre")))       // leaves two spaces behind -> collapsed
                .containsExactly("PO No", "Invoice Number", "Reference", "Vendor", "Cost Centre");
    }

    @Test
    void aHintDifferingOnlyByAnInvisibleCharacterIsADuplicate() {
        assertThat(LabelAliasNormalizer.normalize(List.of("No​", "No"))).containsExactly("No");
    }

    @Test
    void keepsZeroWidthJoinersThatArePartOfTheSpelling() {
        // Sinhala "Sri" (sha + virama + ZWJ + ra + ii): the ZERO WIDTH JOINER
        // forms the conjunct, so stripping it would change the word.
        String sri = "ශ්‍රී";
        assertThat(LabelAliasNormalizer.normalize(List.of(sri))).containsExactly(sri);
        assertThat(LabelAliasNormalizer.normalize(List.of("ab‌cd"))).containsExactly("ab‌cd");
    }

    @Test
    void rejectsControlCharactersAsA400InsteadOfLettingThemReachTheDatabase() {
        // A NUL used to pass validation and then fail the INSERT ("invalid
        // byte sequence for encoding UTF8: 0x00") as a generic 409.
        List<ErrorResponse.FieldValidationError> errors = errorsOf(List.of("ab\u0000cd", "PO\u0001No", "ok hint"));
        assertThat(errors).hasSize(2).allSatisfy(e -> {
            assertThat(e.field()).isEqualTo("labelAliases");
            assertThat(e.message()).contains("control characters");
            // Echoed back printable, not with the raw control character.
            assertThat(e.message()).doesNotContain("\u0000", "\u0001").contains("�");
        });
    }

    @Test
    void stillCollapsesWhitespaceControlsRatherThanRejectingThem() {
        assertThat(LabelAliasNormalizer.normalize(List.of("PO\r\nNumber", "Inv\u0085No")))
                .containsExactly("PO Number", "Inv No");
    }

    @Test
    void quotesALongRejectedEntryWithoutSplittingASurrogatePair() {
        // 101 emoji: too long. Its quoted excerpt is cut after 37 characters;
        // cutting after 37 UTF-16 units would leave half an emoji, a lone
        // surrogate that can't be written into the UTF-8 error response.
        assertThat(errorsOf(List.of("😀".repeat(101))))
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.message()).contains("😀".repeat(37) + "...\"");
                    assertThat(e.message().codePoints()).noneMatch(cp -> Character.getType(cp) == Character.SURROGATE);
                });
    }

    @Test
    void allowsTwentyHintsAndRejectsTwentyOne() {
        List<String> twenty = IntStream.rangeClosed(1, 20).mapToObj(i -> "hint " + i).toList();
        assertThat(LabelAliasNormalizer.normalize(twenty)).hasSize(20);

        List<String> twentyOne = IntStream.rangeClosed(1, 21).mapToObj(i -> "hint " + i).toList();
        assertThat(errorsOf(twentyOne))
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.field()).isEqualTo("labelAliases");
                    assertThat(e.message()).contains("At most 20");
                });
    }

    @Test
    void theTwentyLimitAppliesAfterDeDuplication() {
        List<String> raw = new ArrayList<>(IntStream.rangeClosed(1, 20).mapToObj(i -> "hint " + i).toList());
        raw.add("HINT 1");
        assertThat(LabelAliasNormalizer.normalize(raw)).hasSize(20);
    }

    @Test
    void reportsEveryProblemAtOnce() {
        assertThat(errorsOf(List.of("ok hint", "x", "--", "a".repeat(101))))
                .extracting(ErrorResponse.FieldValidationError::message)
                .hasSize(3)
                .anySatisfy(m -> assertThat(m).startsWith("Label hint #2"))
                .anySatisfy(m -> assertThat(m).startsWith("Label hint #3"))
                .anySatisfy(m -> assertThat(m).startsWith("Label hint #4"));
    }

    @Test
    void normalizingIsIdempotent() {
        List<String> once = LabelAliasNormalizer.normalize(List.of("  PO   Number ", "po number", "Inv #"));
        assertThat(LabelAliasNormalizer.normalize(once)).isEqualTo(once);
    }

    @Test
    void theExceptionMessageNamesTheField() {
        assertThatThrownBy(() -> LabelAliasNormalizer.normalize(List.of("x")))
                .isInstanceOf(FieldValidationException.class)
                .hasMessageContaining("labelAliases");
    }

    /**
     * Every hint V41 seeds must already be in normalized form and within the
     * API's limits - otherwise an admin opening a seeded field and saving it
     * unchanged through the UI would get a 400 (or see the hint silently
     * rewritten) for a list they never touched.
     */
    @Test
    void everyV41SeedHintIsAlreadyNormalizedAndWithinLimits() throws IOException {
        String sql;
        try (InputStream in = getClass().getResourceAsStream(
                "/db/migration/V41__seed_ocr_label_aliases_from_legacy_vocabulary.sql")) {
            assertThat(in).as("V41 on the test classpath").isNotNull();
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        Map<String, List<String>> seedByField = new LinkedHashMap<>();
        Map<String, List<Integer>> ordersByField = new LinkedHashMap<>();
        Matcher row = Pattern.compile("\\('([a-z_]+)', '([^']*)', (\\d+)\\)").matcher(sql);
        while (row.find()) {
            seedByField.computeIfAbsent(row.group(1), k -> new ArrayList<>()).add(row.group(2));
            ordersByField.computeIfAbsent(row.group(1), k -> new ArrayList<>()).add(Integer.parseInt(row.group(3)));
        }

        assertThat(seedByField.values().stream().mapToInt(List::size).sum()).isEqualTo(65);
        assertThat(seedByField).hasSize(17);
        assertThat(seedByField.get("purchase_order_number")).last().isEqualTo("number");
        seedByField.forEach((fieldKey, aliases) -> {
            assertThat(LabelAliasNormalizer.normalize(aliases)).as(fieldKey).isEqualTo(aliases);
            assertThat(ordersByField.get(fieldKey)).as(fieldKey + " display_order")
                    .isEqualTo(IntStream.rangeClosed(1, aliases.size()).mapToObj(i -> i * 10).toList());
        });
    }
}
