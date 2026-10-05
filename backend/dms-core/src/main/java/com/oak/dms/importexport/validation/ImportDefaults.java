package com.oak.dms.importexport.validation;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * Works out the metadata every document of a bulk import gets: each field's
 * default value (see {@link ImportField#defaultValue()}), converted for its
 * data type by {@link ImportValueConverter}. Nobody types metadata into an
 * import, so these defaults are all of it.
 *
 * <p>A default that doesn't convert (say a user_ref default naming a user
 * who has since been deactivated) is left out with a warning, never failing
 * the row. Mandatory fields left without a usable default are reported, for
 * the caller to defer or reject depending on
 * {@code dms.import-export.enforce-mandatory-fields}.
 */
@Component
@RequiredArgsConstructor
public class ImportDefaults {

    static final String DEFAULT_VALUE_INVALID = "DEFAULT_VALUE_INVALID";

    private final ImportValueConverter converter;
    private final ImportUsers importUsers;

    /** One default that will be written: as configured, and as stored. */
    public record AppliedDefault(ImportField field, String rawValue, Object value) {
    }

    /**
     * @param applied          the defaults that converted, in display order
     * @param invalid          one {@value #DEFAULT_VALUE_INVALID} warning per
     *                         default that didn't
     * @param missingMandatory mandatory fields with no default, or whose
     *                         default didn't convert, in display order
     */
    public record Resolved(List<AppliedDefault> applied, List<ImportRowError> invalid,
                           List<ImportField> missingMandatory) {
    }

    /**
     * @param fields one schema version's fields (see {@link ImportSchemaFields}),
     *               hidden ones included - hidden is only a display flag
     */
    public Resolved resolve(Map<String, ImportField> fields, UUID tenantId) {
        Function<String, Optional<UUID>> activeUserByEmail = importUsers.activeUserByEmail(tenantId);
        List<AppliedDefault> applied = new ArrayList<>();
        List<ImportRowError> invalid = new ArrayList<>();
        List<ImportField> missingMandatory = new ArrayList<>();

        for (ImportField field : fields.values()) {
            ImportValueConverter.Result result = converter.convert(field, field.defaultValue(), activeUserByEmail);
            if (result.error() != null) {
                invalid.add(new ImportRowError(field.fieldKey(), DEFAULT_VALUE_INVALID,
                        "The default value for " + field.label() + " couldn't be used ("
                                + result.error().message() + ") - it was left empty", ImportRowError.WARNING));
            } else if (!result.isAbsent()) {
                applied.add(new AppliedDefault(field, field.defaultValue().trim(), result.value()));
                continue;
            }
            if (field.mandatory()) {
                missingMandatory.add(field);
            }
        }
        return new Resolved(List.copyOf(applied), List.copyOf(invalid), List.copyOf(missingMandatory));
    }

    /** "A", "A and B", "A, B and C" - the labels of {@code fields}, for messages. */
    public static String labels(List<ImportField> fields) {
        List<String> labels = fields.stream()
                .map(field -> field.label() != null ? field.label() : field.fieldKey())
                .toList();
        if (labels.size() <= 1) {
            return String.join("", labels);
        }
        return String.join(", ", labels.subList(0, labels.size() - 1)) + " and " + labels.get(labels.size() - 1);
    }
}
