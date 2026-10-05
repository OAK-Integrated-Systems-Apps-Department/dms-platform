package com.oak.dms.importexport.validation;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * Converts one raw value - a field's configured default, always a string
 * (see {@link ImportDefaults}) - to the value stored for its field's data
 * type, in the shapes {@code DocumentMetadata.FieldValue} documents:
 * strings, {@link Long} whole numbers, {@link BigDecimal} decimals,
 * {@link Boolean}s, ISO-8601 strings for dates and date-times, and a list
 * of strings for multi-select.
 *
 * <p>Pure apart from the user lookup it is handed, so it can be unit tested
 * on its own.
 */
@Component
public class ImportValueConverter {

    /**
     * The outcome for one value: a converted value, a conversion error, or
     * neither when the value is blank (the field is then absent).
     */
    public record Result(Object value, ImportRowError error) {

        static final Result ABSENT = new Result(null, null);

        static Result of(Object value) {
            return new Result(value, null);
        }

        static Result failed(ImportRowError error) {
            return new Result(null, error);
        }

        public boolean isAbsent() {
            return value == null && error == null;
        }
    }

    /**
     * @param activeUserByEmail resolves a trimmed email to an active user's
     *                          id in the batch's tenant
     */
    public Result convert(ImportField field, String raw, Function<String, Optional<UUID>> activeUserByEmail) {
        if (raw == null || raw.isBlank()) {
            return Result.ABSENT;
        }
        String value = raw.trim();
        String label = field.label();
        String key = field.fieldKey();
        String dataType = field.dataType() != null ? field.dataType() : "";

        return switch (dataType) {
            case "number" -> toWholeNumber(value)
                    .map(Result::of)
                    .orElseGet(() -> fail(key, "INVALID_NUMBER", label + " must be a whole number"));
            case "decimal" -> toDecimal(value)
                    .map(Result::of)
                    .orElseGet(() -> fail(key, "INVALID_DECIMAL", label + " must be a number"));
            case "date" -> toDate(value)
                    .map(Result::of)
                    .orElseGet(() -> fail(key, "INVALID_DATE", label + " must be a date, e.g. 2026-09-30"));
            case "datetime" -> toDateTime(value)
                    .map(Result::of)
                    .orElseGet(() -> fail(key, "INVALID_DATETIME",
                            label + " must be a date and time, e.g. 2026-09-30T14:30:00"));
            case "boolean" -> toBoolean(value)
                    .map(Result::of)
                    .orElseGet(() -> fail(key, "INVALID_BOOLEAN", label + " must be yes or no (or true/false, 1/0)"));
            case "multi_select" -> {
                List<String> values = Arrays.stream(value.split(";"))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .toList();
                // Only separators ("; ;") - treat as blank so a mandatory
                // field still reports as missing (the metadata validator
                // counts an empty list as present).
                yield values.isEmpty() ? Result.ABSENT : Result.of(values);
            }
            // TODO(users): users.email is stored exactly as supplied (not
            // normalised on save, case-sensitive unique key), so this match
            // is exact - "John@oak.lk" won't find "john@oak.lk". The proper
            // fix is normalising emails on save plus a migration in the
            // user module; the message below points people at the cause.
            case "user_ref" -> activeUserByEmail.apply(value)
                    .map(userId -> Result.of(userId.toString()))
                    .orElseGet(() -> fail(key, "UNKNOWN_USER", label + ": no active user with email '" + value
                            + "' - check it matches the user's email exactly, including capital letters"));
            // TODO(lookup): no lookup source exists yet, so lookup values are
            // taken as entered.
            // text, long_text, dropdown (option membership is the metadata
            // validator's job) and lookup are strings as entered.
            default -> Result.of(value);
        };
    }

    private static Result fail(String fieldKey, String code, String message) {
        return Result.failed(ImportRowError.error(fieldKey, code, message));
    }

    private static Optional<Long> toWholeNumber(String value) {
        return toDecimal(value).flatMap(number -> {
            try {
                return Optional.of(number.longValueExact());
            } catch (ArithmeticException e) {
                // Has a fractional part, or doesn't fit in a long.
                return Optional.empty();
            }
        });
    }

    private static Optional<BigDecimal> toDecimal(String value) {
        try {
            return Optional.of(new BigDecimal(value));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    /** yyyy-MM-dd, or a date-time of which only the date is kept. */
    private static Optional<String> toDate(String value) {
        try {
            return Optional.of(LocalDate.parse(value).toString());
        } catch (DateTimeParseException e) {
            return parseDateTime(value).map(dateTime -> dateTime.toLocalDate().toString());
        }
    }

    /**
     * An ISO-8601 date-time (its offset kept if it has one), or yyyy-MM-dd
     * taken as the start of that day.
     */
    private static Optional<String> toDateTime(String value) {
        Optional<String> dateTime = parseOffsetDateTime(value)
                .map(DateTimeFormatter.ISO_OFFSET_DATE_TIME::format)
                .or(() -> parseLocalDateTime(value).map(DateTimeFormatter.ISO_LOCAL_DATE_TIME::format));
        if (dateTime.isPresent()) {
            return dateTime;
        }
        try {
            return Optional.of(DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(LocalDate.parse(value).atStartOfDay()));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    private static Optional<LocalDateTime> parseDateTime(String value) {
        return parseLocalDateTime(value).or(() -> parseOffsetDateTime(value).map(OffsetDateTime::toLocalDateTime));
    }

    private static Optional<LocalDateTime> parseLocalDateTime(String value) {
        try {
            return Optional.of(LocalDateTime.parse(withT(value)));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    private static Optional<OffsetDateTime> parseOffsetDateTime(String value) {
        try {
            return Optional.of(OffsetDateTime.parse(withT(value)));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    /** Also accept a space between date and time ("2026-09-30 14:30"). */
    private static String withT(String value) {
        return value.length() > 10 && value.charAt(10) == ' '
                ? value.substring(0, 10) + 'T' + value.substring(11)
                : value;
    }

    private static Optional<Boolean> toBoolean(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "true", "yes", "1" -> Optional.of(Boolean.TRUE);
            case "false", "no", "0" -> Optional.of(Boolean.FALSE);
            default -> Optional.empty();
        };
    }
}
