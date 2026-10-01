package com.oak.dms.importexport.template;

import com.oak.dms.metadata.dto.DocumentTypeSchemaResponse.SchemaField;
import com.oak.dms.metadata.dto.MetadataFieldOptionResponse;
import com.oak.dms.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Turns a template row's cell text into typed metadata values, per the
 * field's data type. MetadataValidationService deliberately doesn't check
 * data types, so this is where "abc" in a number column is caught.
 * <p>
 * Two maps come back, both keyed by field_key: {@code stored} is what goes
 * into document_metadata (dates as ISO-8601, numbers as numbers, option
 * fields as option values); {@code forValidation} is what the validation
 * engine is given - the same, except date fields keep the text as typed,
 * because a field's date_format rule is checked against that text.
 */
@Component
@RequiredArgsConstructor
public class RowValueConverter {

    private static final Set<String> YES = Set.of("yes", "y", "true", "1");
    private static final Set<String> NO = Set.of("no", "n", "false", "0");

    private final UserRepository userRepository;

    public record Converted(Map<String, Object> stored, Map<String, Object> forValidation, List<String> errors) {
    }

    public Converted convert(List<SchemaField> fields, Map<String, String> cells, UUID tenantId) {
        Map<String, Object> stored = new LinkedHashMap<>();
        Map<String, Object> forValidation = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();
        for (SchemaField field : fields) {
            String text = cells.get(field.fieldKey());
            if (text == null || text.isBlank()) {
                continue;
            }
            try {
                Object value = convert(field, text.trim(), tenantId);
                stored.put(field.fieldKey(), value);
                boolean dateTyped = "date".equals(field.dataType()) || "datetime".equals(field.dataType());
                forValidation.put(field.fieldKey(), dateTyped ? text.trim() : value);
            } catch (IllegalArgumentException | DateTimeParseException | ArithmeticException e) {
                errors.add(field.fieldKey() + ": " + e.getMessage());
            }
        }
        return new Converted(stored, forValidation, errors);
    }

    private Object convert(SchemaField field, String text, UUID tenantId) {
        return switch (field.dataType()) {
            case "number" -> {
                try {
                    yield new BigDecimal(text).longValueExact();
                } catch (NumberFormatException | ArithmeticException e) {
                    throw new IllegalArgumentException("\"" + text + "\" is not a whole number");
                }
            }
            case "decimal" -> {
                try {
                    yield new BigDecimal(text);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("\"" + text + "\" is not a number (use . as the decimal point)");
                }
            }
            case "date" -> parseDate(field, text).toString();
            case "datetime" -> parseDateTime(text).toString();
            case "boolean" -> {
                String lower = text.toLowerCase(Locale.ROOT);
                if (YES.contains(lower)) {
                    yield Boolean.TRUE;
                }
                if (NO.contains(lower)) {
                    yield Boolean.FALSE;
                }
                throw new IllegalArgumentException("\"" + text + "\" must be Yes or No");
            }
            case "dropdown" -> option(field, text);
            case "multi_select" -> {
                List<String> values = new ArrayList<>();
                for (String part : text.split("[|;]")) {
                    if (!part.isBlank()) {
                        String value = option(field, part.trim());
                        if (!values.contains(value)) {
                            values.add(value);
                        }
                    }
                }
                yield values;
            }
            case "user_ref" -> resolveUser(text, tenantId);
            default -> text;
        };
    }

    private static LocalDate parseDate(SchemaField field, String text) {
        try {
            return LocalDate.parse(text);
        } catch (DateTimeParseException iso) {
            Optional<String> pattern = field.validations().stream()
                    .filter(v -> "date_format".equals(v.ruleType()))
                    .map(v -> v.ruleValue())
                    .findFirst();
            if (pattern.isPresent()) {
                try {
                    return LocalDate.parse(text, DateTimeFormatter.ofPattern(pattern.get()));
                } catch (RuntimeException ignored) {
                    throw new IllegalArgumentException("\"" + text + "\" is not a date as " + pattern.get());
                }
            }
            throw new IllegalArgumentException("\"" + text + "\" is not a date as YYYY-MM-DD");
        }
    }

    /** ISO date-time; without an offset it is taken as UTC. */
    private static OffsetDateTime parseDateTime(String text) {
        try {
            return OffsetDateTime.parse(text);
        } catch (DateTimeParseException e) {
            try {
                return LocalDateTime.parse(text.replace(' ', 'T')).atOffset(ZoneOffset.UTC);
            } catch (DateTimeParseException e2) {
                throw new IllegalArgumentException("\"" + text + "\" is not a date and time as YYYY-MM-DDTHH:MM");
            }
        }
    }

    /** An option by value, or by label, ignoring case; returns the option's value. */
    private static String option(SchemaField field, String text) {
        for (MetadataFieldOptionResponse o : field.options()) {
            if (o.optionValue().equals(text)) {
                return o.optionValue();
            }
        }
        for (MetadataFieldOptionResponse o : field.options()) {
            if (o.optionValue().equalsIgnoreCase(text) || (o.optionLabel() != null && o.optionLabel().equalsIgnoreCase(text))) {
                return o.optionValue();
            }
        }
        throw new IllegalArgumentException("\"" + text + "\" is not one of the allowed values");
    }

    /** A user of this tenant, by email or user id; stored as the user id. */
    private String resolveUser(String text, UUID tenantId) {
        UUID id = asUuid(text);
        if (id != null) {
            return userRepository.findByUserIdAndTenantIdAndDeletedFalse(id, tenantId)
                    .map(u -> u.getUserId().toString())
                    .orElseThrow(() -> new IllegalArgumentException("no user with id " + text));
        }
        if (!text.contains("@")) {
            throw new IllegalArgumentException("\"" + text + "\" is not a user's email address");
        }
        return userRepository.findByTenantIdAndEmailAndDeletedFalse(tenantId, text)
                .or(() -> userRepository.findByTenantIdAndEmailAndDeletedFalse(tenantId, text.toLowerCase(Locale.ROOT)))
                .map(u -> u.getUserId().toString())
                .orElseThrow(() -> new IllegalArgumentException("no user with email " + text));
    }

    private static UUID asUuid(String text) {
        try {
            return text.length() == 36 ? UUID.fromString(text) : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
