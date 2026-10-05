package com.oak.dms.importexport.validation;

import com.oak.dms.importexport.entity.ImportItem;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Checks one bulk import item before its document is created. Each item is
 * one uploaded file and its metadata is the document type's defaults (see
 * {@link ImportDefaults}), so there is little left to check: that its file
 * was accepted at upload, that the title taken from its filename fits, and
 * what the defaults mean for it.
 * <ul>
 *   <li>A default that didn't convert: a {@code DEFAULT_VALUE_INVALID}
 *       warning; the field is left empty.</li>
 *   <li>Mandatory fields with no usable default: with
 *       {@code enforce-mandatory-fields} off, one
 *       {@value #MANDATORY_FIELD_DEFERRED} warning naming them, so documents
 *       that need backfilling can be found later; with it on, a
 *       {@value #MANDATORY_FIELD_MISSING} error each. Batch creation
 *       already refuses such a type when enforcing; this catches a default
 *       that changed after the batch was created.</li>
 * </ul>
 *
 * <p>Deterministic for a given item and defaults, so re-validating an item
 * (a redelivered batch) gives the same result.
 */
@Component
public class ImportRowValidator {

    static final String FILE = "file";
    static final String TITLE = "title";
    static final String MANDATORY_FIELD_DEFERRED = "MANDATORY_FIELD_DEFERRED";
    static final String MANDATORY_FIELD_MISSING = "MANDATORY_FIELD_MISSING";

    /**
     * @param defaults         the batch's defaults, resolved once for the batch
     * @param enforceMandatory {@code dms.import-export.enforce-mandatory-fields}
     * @return every problem found, errors and warnings; the item fails if
     *         {@link #hasErrors} is true for it
     */
    public List<ImportRowError> validate(ImportItem item, ImportDefaults.Resolved defaults,
                                         boolean enforceMandatory) {
        List<ImportRowError> errors = new ArrayList<>();
        if (item.getStagedObjectKey() == null) {
            // A refused upload keeps its reason on the item; say that rather
            // than "no file".
            errors.add(item.getUploadRejectionMessage() != null
                    ? ImportRowError.error(FILE, "FILE_REJECTED", item.getUploadRejectionMessage())
                    : ImportRowError.error(FILE, "FILE_MISSING", "The uploaded file could not be found"));
        }

        String title = ImportTitles.fromFilename(item.getFilenameReference());
        if (title != null && title.length() > ImportTitles.MAX_LENGTH) {
            errors.add(ImportRowError.error(TITLE, "TITLE_TOO_LONG", "The title taken from the file name is "
                    + title.length() + " characters long - it can be at most " + ImportTitles.MAX_LENGTH
                    + ". Rename the file and upload it again."));
        }

        errors.addAll(defaults.invalid());

        List<ImportField> missing = defaults.missingMandatory();
        if (!missing.isEmpty()) {
            if (enforceMandatory) {
                for (ImportField field : missing) {
                    errors.add(ImportRowError.error(field.fieldKey(), MANDATORY_FIELD_MISSING,
                            field.label() + " is required and has no usable default value"));
                }
            } else {
                errors.add(new ImportRowError(null, MANDATORY_FIELD_DEFERRED,
                        "Required fields left empty, to be filled in later: " + ImportDefaults.labels(missing),
                        ImportRowError.WARNING));
            }
        }
        return errors;
    }

    public static boolean hasErrors(List<ImportRowError> errors) {
        return errors.stream().anyMatch(e -> ImportRowError.ERROR.equals(e.severity()));
    }
}
