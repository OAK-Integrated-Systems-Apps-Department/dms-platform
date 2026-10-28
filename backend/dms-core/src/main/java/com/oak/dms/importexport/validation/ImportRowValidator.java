package com.oak.dms.importexport.validation;

import com.oak.dms.importexport.entity.ImportItem;
import com.oak.dms.metadata.dto.ValidationError;
import com.oak.dms.metadata.service.MetadataValidationService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Checks one bulk import row before any record is created: that its file
 * arrived, that its filename is present and unique in the batch, that its
 * title fits, that every cell converts to its field's data type, and then
 * the document type's own metadata rules (mandatory fields, options,
 * per-field validations) at the batch's schema version.
 *
 * <p>Deterministic for a given row, batch and schema, so re-validating an
 * item (a redelivered batch) gives the same result.
 */
@Component
@RequiredArgsConstructor
public class ImportRowValidator {

    static final String FILE = "file";
    static final String FILENAME = "filename";
    static final String TITLE = "title";

    private final ImportValueConverter converter;
    private final MetadataValidationService metadataValidationService;
    private final ImportUsers importUsers;

    /** An item's {@code row_values} JSON: the raw title and field cells, keyed by field_key. */
    public record RowValues(String title, Map<String, String> fields) {
    }

    /**
     * Normalised filename (trimmed, lower-cased) to the row numbers using
     * it, for every filename that appears on more than one row. Blank
     * filenames are left out - those rows fail as missing instead.
     */
    public static Map<String, List<Integer>> duplicateRows(List<ImportItem> items) {
        Map<String, List<Integer>> rowsByName = new LinkedHashMap<>();
        for (ImportItem item : items) {
            String name = normalise(item.getFilenameReference());
            if (name != null) {
                rowsByName.computeIfAbsent(name, k -> new ArrayList<>()).add(item.getRowNumber());
            }
        }
        rowsByName.values().removeIf(rows -> rows.size() < 2);
        rowsByName.values().forEach(rows -> rows.sort(Integer::compare));
        return rowsByName;
    }

    /**
     * @param fields     the batch's schema version, keyed by field_key (see {@link ImportSchemaFields})
     * @param duplicates see {@link #duplicateRows}, computed once over the whole batch
     * @return every problem found, errors and warnings; the row fails if
     *         {@link #hasErrors} is true for it
     */
    public List<ImportRowError> validate(ImportItem item, RowValues values, Map<String, ImportField> fields,
                                         Map<String, List<Integer>> duplicates, UUID documentTypeId,
                                         int schemaVersion, UUID tenantId) {
        List<ImportRowError> errors = new ArrayList<>();

        if (item.getStagedObjectKey() == null && item.getIntakeBlockedReason() != null) {
            // A file was uploaded, and refused - say why, not just that there's no file.
            errors.add(ImportRowError.error(FILE, "FILE_BLOCKED", item.getIntakeBlockedReason()));
        } else if (item.getStagedObjectKey() == null) {
            errors.add(ImportRowError.error(FILE, "FILE_MISSING", "No file was uploaded for this row"));
        }

        String filename = item.getFilenameReference();
        String normalisedName = normalise(filename);
        if (normalisedName == null) {
            errors.add(ImportRowError.error(FILENAME, "FILENAME_MISSING", "Enter the file name for this row"));
        } else if (duplicates.containsKey(normalisedName)) {
            errors.add(ImportRowError.error(FILENAME, "DUPLICATE_FILENAME", "'" + filename.trim() + "' appears on rows "
                    + joinRows(duplicates.get(normalisedName)) + " - each file needs a unique name"));
        }

        String title = ImportTitles.resolve(values.title(), filename);
        if (title != null && title.length() > ImportTitles.MAX_LENGTH) {
            errors.add(ImportRowError.error(TITLE, "TITLE_TOO_LONG", "Title is " + title.length()
                    + " characters long - it can be at most " + ImportTitles.MAX_LENGTH));
        }

        Function<String, Optional<UUID>> activeUserByEmail = importUsers.activeUserByEmail(tenantId);

        Map<String, Object> converted = new LinkedHashMap<>();
        Set<String> unconvertible = new HashSet<>();
        Map<String, String> cells = values.fields() != null ? values.fields() : Map.of();
        for (Map.Entry<String, String> cell : cells.entrySet()) {
            ImportField field = fields.get(cell.getKey());
            if (field == null) {
                // Not in this schema version - nothing to store it against.
                continue;
            }
            ImportValueConverter.Result result = converter.convert(field, cell.getValue(), activeUserByEmail);
            if (result.error() != null) {
                errors.add(result.error());
                unconvertible.add(field.fieldKey());
            } else if (!result.isAbsent()) {
                converted.put(field.fieldKey(), result.value());
            }
        }

        // A field that didn't convert is absent from `converted`, so the
        // validator would also call it missing (if mandatory) - one error
        // per field is enough, and the conversion one says what's wrong.
        for (ValidationError error : metadataValidationService.validate(documentTypeId, schemaVersion, converted)) {
            if (!unconvertible.contains(error.fieldKey())) {
                errors.add(new ImportRowError(error.fieldKey(), error.errorCode(), error.message(), error.severity()));
            }
        }
        return errors;
    }

    public static boolean hasErrors(List<ImportRowError> errors) {
        return errors.stream().anyMatch(e -> ImportRowError.ERROR.equals(e.severity()));
    }

    private static String normalise(String filename) {
        if (filename == null || filename.isBlank()) {
            return null;
        }
        return filename.trim().toLowerCase(Locale.ROOT);
    }

    /** "3 and 7", "3, 5 and 7". */
    private static String joinRows(List<Integer> rows) {
        List<String> numbers = rows.stream().map(String::valueOf).toList();
        if (numbers.size() == 1) {
            return numbers.get(0);
        }
        return numbers.subList(0, numbers.size() - 1).stream().collect(Collectors.joining(", "))
                + " and " + numbers.get(numbers.size() - 1);
    }
}
