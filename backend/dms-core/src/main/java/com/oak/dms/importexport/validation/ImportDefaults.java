package com.oak.dms.importexport.validation;

import com.oak.dms.metadata.dto.ValidationError;
import com.oak.dms.metadata.entity.MetadataField;
import com.oak.dms.metadata.entity.MetadataFieldOption;
import com.oak.dms.metadata.repository.MetadataFieldOptionRepository;
import com.oak.dms.metadata.service.MetadataValidationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Works out the metadata every document of a bulk import gets: each field's
 * default value (see {@link ImportField#defaultValue()}), converted for its
 * data type by {@link ImportValueConverter}, matched to an active option for
 * dropdown and multi_select fields, then validated by
 * {@link MetadataValidationService} like any other metadata. Nobody types
 * metadata into an import, so these defaults are all of it.
 *
 * <p>A default that doesn't convert, match or validate (say a dropdown
 * default "Long-term" where the option's code is {@code long_term} and its
 * label something else) is left out with a warning, never failing the row.
 * Mandatory fields left without a usable default are reported, for the
 * caller to defer or reject depending on
 * {@code dms.import-export.enforce-mandatory-fields}. Retired fields are
 * skipped silently, as the metadata validator skips them.
 */
@Component
@RequiredArgsConstructor
public class ImportDefaults {

    static final String DEFAULT_VALUE_INVALID = "DEFAULT_VALUE_INVALID";

    /** The validator's code for a mandatory field with no value - handled here as missingMandatory instead. */
    private static final String VALIDATOR_MANDATORY_MISSING = "MANDATORY_FIELD_MISSING";

    private final ImportValueConverter converter;
    private final ImportUsers importUsers;
    private final MetadataValidationService metadataValidationService;
    private final MetadataFieldOptionRepository metadataFieldOptionRepository;

    /** One default that will be written: as configured, and as stored. */
    public record AppliedDefault(ImportField field, String rawValue, Object value) {
    }

    /** One default that won't be written, and why ({@code reason} is a sentence fragment, no full stop). */
    public record InvalidDefault(ImportField field, String rawValue, String reason) {
    }

    /**
     * @param applied          the usable defaults, in display order
     * @param invalidDefaults  the defaults that didn't convert, match or
     *                         validate, in display order
     * @param missingMandatory mandatory fields with no default, or whose
     *                         default isn't usable, in display order
     */
    public record Resolved(List<AppliedDefault> applied, List<InvalidDefault> invalidDefaults,
                           List<ImportField> missingMandatory) {

        /** One {@value #DEFAULT_VALUE_INVALID} warning per invalid default. */
        public List<ImportRowError> invalid() {
            return invalidDefaults.stream()
                    .map(invalid -> new ImportRowError(invalid.field().fieldKey(), DEFAULT_VALUE_INVALID,
                            "The default value for '" + labelOf(invalid.field()) + "' couldn't be used: "
                                    + invalid.reason() + " - it was left empty", ImportRowError.WARNING))
                    .toList();
        }
    }

    /**
     * Callers bind {@code TenantContext} to {@code tenantId} - the metadata
     * validator reads it.
     *
     * @param fields one schema version's fields (see {@link ImportSchemaFields}),
     *               hidden ones included - hidden is only a display flag
     */
    public Resolved resolve(Map<String, ImportField> fields, UUID tenantId, UUID documentTypeId,
                            int schemaVersion) {
        Function<String, Optional<UUID>> activeUserByEmail = importUsers.activeUserByEmail(tenantId);
        Map<UUID, List<MetadataFieldOption>> optionsByField = activeOptions(fields, tenantId);

        // Keyed by field_key, in display order.
        Map<String, AppliedDefault> usable = new LinkedHashMap<>();
        Map<String, InvalidDefault> invalid = new HashMap<>();

        for (ImportField field : fields.values()) {
            if (!field.active()) {
                continue;
            }
            ImportValueConverter.Result result = converter.convert(field, field.defaultValue(), activeUserByEmail);
            if (result.isAbsent()) {
                continue;
            }
            String rawValue = field.defaultValue().trim();
            if (result.error() != null) {
                invalid.put(field.fieldKey(), new InvalidDefault(field, rawValue, result.error().message()));
                continue;
            }
            Object value = result.value();
            if (MetadataField.OPTION_BACKED_DATA_TYPES.contains(field.dataType())) {
                OptionMatch match = matchOptions(value, optionsByField.getOrDefault(field.fieldId(), List.of()));
                if (match.problem() != null) {
                    invalid.put(field.fieldKey(), new InvalidDefault(field, rawValue, match.problem()));
                    continue;
                }
                value = match.value();
            }
            usable.put(field.fieldKey(), new AppliedDefault(field, rawValue, value));
        }

        validate(usable, invalid, documentTypeId, schemaVersion);

        List<InvalidDefault> invalidInOrder = new ArrayList<>();
        List<ImportField> missingMandatory = new ArrayList<>();
        for (ImportField field : fields.values()) {
            if (!field.active()) {
                continue;
            }
            InvalidDefault invalidDefault = invalid.get(field.fieldKey());
            if (invalidDefault != null) {
                invalidInOrder.add(invalidDefault);
            }
            if (field.mandatory() && !usable.containsKey(field.fieldKey())) {
                missingMandatory.add(field);
            }
        }
        return new Resolved(List.copyOf(usable.values()), List.copyOf(invalidInOrder), List.copyOf(missingMandatory));
    }

    /**
     * Runs the metadata validator over the usable defaults and moves every
     * one it reports an error for from {@code usable} to {@code invalid}.
     * Its mandatory-missing errors and its warnings don't apply to defaults.
     */
    private void validate(Map<String, AppliedDefault> usable, Map<String, InvalidDefault> invalid,
                          UUID documentTypeId, int schemaVersion) {
        if (usable.isEmpty()) {
            return;
        }
        Map<String, Object> values = new HashMap<>();
        usable.forEach((fieldKey, applied) -> values.put(fieldKey, applied.value()));

        for (ValidationError error : metadataValidationService.validate(documentTypeId, schemaVersion, values)) {
            if (!ImportRowError.ERROR.equalsIgnoreCase(error.severity())
                    || VALIDATOR_MANDATORY_MISSING.equals(error.errorCode())) {
                continue;
            }
            // The first error for a field is its reason; later ones find it gone.
            AppliedDefault applied = usable.remove(error.fieldKey());
            if (applied != null) {
                invalid.put(error.fieldKey(), new InvalidDefault(applied.field(), applied.rawValue(),
                        withoutFullStop(error.message())));
            }
        }
    }

    private Map<UUID, List<MetadataFieldOption>> activeOptions(Map<String, ImportField> fields, UUID tenantId) {
        List<UUID> fieldIds = fields.values().stream()
                .filter(field -> field.active() && MetadataField.OPTION_BACKED_DATA_TYPES.contains(field.dataType())
                        && field.defaultValue() != null && !field.defaultValue().isBlank())
                .map(ImportField::fieldId)
                .distinct()
                .toList();
        if (fieldIds.isEmpty()) {
            return Map.of();
        }
        return metadataFieldOptionRepository.findAllActiveByFieldsOrdered(fieldIds, tenantId).stream()
                .collect(Collectors.groupingBy(MetadataFieldOption::getFieldId));
    }

    /** {@code value} with each item replaced by its option's code, or why it can't be. */
    private record OptionMatch(Object value, String problem) {
    }

    /**
     * Matches a converted dropdown value (a string) or multi_select value (a
     * list of strings) to active options, item by item. An item matches an
     * option whose code it equals ignoring case, with spaces and hyphens
     * read as underscores ("Long-term" is {@code long_term}), or whose label
     * it equals ignoring case. Nothing partial or fuzzy.
     */
    private static OptionMatch matchOptions(Object value, List<MetadataFieldOption> options) {
        if (value instanceof List<?> items) {
            Set<String> codes = new LinkedHashSet<>();
            for (Object item : items) {
                OptionMatch match = matchOption(String.valueOf(item), options);
                if (match.problem() != null) {
                    return match;
                }
                codes.add((String) match.value());
            }
            return new OptionMatch(List.copyOf(codes), null);
        }
        return matchOption(String.valueOf(value), options);
    }

    private static OptionMatch matchOption(String item, List<MetadataFieldOption> options) {
        String text = item.trim();
        String asCode = asCode(text);
        List<MetadataFieldOption> matches = options.stream()
                .filter(option -> (option.getOptionValue() != null && asCode(option.getOptionValue()).equals(asCode))
                        || (option.getOptionLabel() != null && option.getOptionLabel().trim().equalsIgnoreCase(text)))
                .toList();
        if (matches.size() > 1) {
            return new OptionMatch(null, "'" + text + "' matches more than one option");
        }
        if (matches.isEmpty()) {
            return new OptionMatch(null, "'" + text + "' isn't one of the field's active options");
        }
        return new OptionMatch(matches.get(0).getOptionValue(), null);
    }

    private static String asCode(String value) {
        return value.trim().toLowerCase(Locale.ROOT).replace(' ', '_').replace('-', '_');
    }

    private static String withoutFullStop(String message) {
        if (message == null) {
            return "it isn't valid";
        }
        String trimmed = message.trim();
        return trimmed.endsWith(".") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    private static String labelOf(ImportField field) {
        return field.label() != null ? field.label() : field.fieldKey();
    }

    /** "A", "A and B", "A, B and C" - the labels of {@code fields}, for messages. */
    public static String labels(List<ImportField> fields) {
        List<String> labels = fields.stream()
                .map(ImportDefaults::labelOf)
                .toList();
        if (labels.size() <= 1) {
            return String.join("", labels);
        }
        return String.join(", ", labels.subList(0, labels.size() - 1)) + " and " + labels.get(labels.size() - 1);
    }
}
