package com.oak.dms.importexport.dto;

import java.util.List;
import java.util.UUID;

/**
 * Response of {@code POST /import-templates/preview}: the row checks a
 * filled-in template would get on createBatch, without saving anything.
 *
 * @param rows the rows with at least one error, in row order, with every
 *             problem found on them (warnings included)
 */
public record ImportTemplatePreview(
        UUID documentTypeId,
        int schemaVersion,
        int totalRows,
        int validRows,
        int invalidRows,
        List<ImportRowFailure> rows
) {
}
