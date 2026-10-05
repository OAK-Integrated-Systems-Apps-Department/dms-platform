package com.oak.dms.importexport.validation;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class ImportValueConverterTest {

    private static final Function<String, Optional<UUID>> NO_USERS = email -> Optional.empty();

    private final ImportValueConverter converter = new ImportValueConverter();

    @Test
    void convert_blankCell_isAbsent() {
        assertThat(converter.convert(field("number"), null, NO_USERS).isAbsent()).isTrue();
        assertThat(converter.convert(field("text"), "   ", NO_USERS).isAbsent()).isTrue();
    }

    @Test
    void convert_textTypes_areTrimmedStrings() {
        for (String type : List.of("text", "long_text", "dropdown", "lookup")) {
            assertThat(converter.convert(field(type), "  Acme Ltd ", NO_USERS).value()).isEqualTo("Acme Ltd");
        }
    }

    @Test
    void convert_number_wholeNumbersOnly() {
        assertThat(converter.convert(field("number"), "12345678901", NO_USERS).value()).isEqualTo(12345678901L);
        assertThat(converter.convert(field("number"), "42.0", NO_USERS).value()).isEqualTo(42L);
        assertThat(converter.convert(field("number"), "-7", NO_USERS).value()).isEqualTo(-7L);

        for (String raw : List.of("1.5", "abc", "99999999999999999999")) {
            ImportRowError error = converter.convert(field("number"), raw, NO_USERS).error();
            assertThat(error).isEqualTo(ImportRowError.error("contract_value", "INVALID_NUMBER",
                    "Contract Value must be a whole number"));
        }
    }

    @Test
    void convert_decimal_toBigDecimal() {
        assertThat(converter.convert(field("decimal"), "1250.50", NO_USERS).value())
                .isEqualTo(new BigDecimal("1250.50"));

        ImportRowError error = converter.convert(field("decimal"), "12,5", NO_USERS).error();
        assertThat(error.code()).isEqualTo("INVALID_DECIMAL");
        assertThat(error.message()).isEqualTo("Contract Value must be a number");
        assertThat(error.severity()).isEqualTo(ImportRowError.ERROR);
    }

    @Test
    void convert_date_acceptsDateOrDateTimeKeepingTheDate() {
        assertThat(converter.convert(field("date"), "2026-09-30", NO_USERS).value()).isEqualTo("2026-09-30");
        assertThat(converter.convert(field("date"), "2026-09-30T14:30:00", NO_USERS).value()).isEqualTo("2026-09-30");
        assertThat(converter.convert(field("date"), "2026-09-30 14:30", NO_USERS).value()).isEqualTo("2026-09-30");
        assertThat(converter.convert(field("date"), "2026-09-30T14:30:00+05:30", NO_USERS).value())
                .isEqualTo("2026-09-30");

        for (String raw : List.of("30/09/2026", "2026-02-30", "soon")) {
            assertThat(converter.convert(field("date"), raw, NO_USERS).error().code()).isEqualTo("INVALID_DATE");
        }
    }

    @Test
    void convert_datetime_isoDateTimeOrStartOfDay() {
        assertThat(converter.convert(field("datetime"), "2026-09-30T14:30", NO_USERS).value())
                .isEqualTo("2026-09-30T14:30:00");
        assertThat(converter.convert(field("datetime"), "2026-09-30 14:30:15", NO_USERS).value())
                .isEqualTo("2026-09-30T14:30:15");
        assertThat(converter.convert(field("datetime"), "2026-09-30T14:30:00Z", NO_USERS).value())
                .isEqualTo("2026-09-30T14:30:00Z");
        assertThat(converter.convert(field("datetime"), "2026-09-30T14:30:00+05:30", NO_USERS).value())
                .isEqualTo("2026-09-30T14:30:00+05:30");
        assertThat(converter.convert(field("datetime"), "2026-09-30", NO_USERS).value())
                .isEqualTo("2026-09-30T00:00:00");

        assertThat(converter.convert(field("datetime"), "2026-09-30T25:00", NO_USERS).error().code())
                .isEqualTo("INVALID_DATETIME");
    }

    @Test
    void convert_boolean_acceptsWordsAndDigitsCaseInsensitively() {
        for (String raw : List.of("true", "TRUE", "Yes", "1")) {
            assertThat(converter.convert(field("boolean"), raw, NO_USERS).value()).isEqualTo(Boolean.TRUE);
        }
        for (String raw : List.of("false", "No", "NO", "0")) {
            assertThat(converter.convert(field("boolean"), raw, NO_USERS).value()).isEqualTo(Boolean.FALSE);
        }
        assertThat(converter.convert(field("boolean"), "maybe", NO_USERS).error().code()).isEqualTo("INVALID_BOOLEAN");
    }

    @Test
    void convert_multiSelect_splitsTrimsAndDropsEmpties() {
        assertThat(converter.convert(field("multi_select"), " urgent ; ;q3;", NO_USERS).value())
                .isEqualTo(List.of("urgent", "q3"));
        assertThat(converter.convert(field("multi_select"), " ; ; ", NO_USERS).isAbsent()).isTrue();
    }

    @Test
    void convert_userRef_exactEmailMatchGivesUserId() {
        UUID userId = UUID.randomUUID();
        Map<String, UUID> users = Map.of("john@oak.lk", userId);
        Function<String, Optional<UUID>> lookup = email -> Optional.ofNullable(users.get(email));

        assertThat(converter.convert(field("user_ref"), "  john@oak.lk ", lookup).value()).isEqualTo(userId.toString());
    }

    @Test
    void convert_userRef_unknownOrCaseMismatchedEmail_isUnknownUserWithHint() {
        Map<String, UUID> users = Map.of("john@oak.lk", UUID.randomUUID());
        Function<String, Optional<UUID>> lookup = email -> Optional.ofNullable(users.get(email));
        ImportField owner = new ImportField(UUID.randomUUID(), "owner", "Owner", "user_ref", false, false, null);

        ImportRowError error = converter.convert(owner, "John@oak.lk", lookup).error();

        assertThat(error).isEqualTo(ImportRowError.error("owner", "UNKNOWN_USER",
                "Owner: no active user with email 'John@oak.lk' - check it matches the user's email exactly,"
                        + " including capital letters"));
        assertThat(converter.convert(owner, "nobody@oak.lk", lookup).error().code()).isEqualTo("UNKNOWN_USER");
    }

    private static ImportField field(String dataType) {
        return new ImportField(UUID.randomUUID(), "contract_value", "Contract Value", dataType, false, false, null);
    }
}
