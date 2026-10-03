package com.oak.dms.importexport.dto;

import java.util.List;
import java.util.UUID;

/**
 * Response of {@code POST /import-templates/preview}: the row checks a
 * filled-in template would get on createBatch, without saving anything.
 *
 * @param rows the rows with at least one error, in row order, with every
 *             problem found on them (warnings included)
 * @param resubmitOf    the import whose failed rows this template resubmits;
 *                      null for a normal template
 * @param reusableFiles rows that would reuse a file kept from that import
 *                      (computed only - nothing is moved)
 */
public record ImportTemplatePreview(
        UUID documentTypeId,
        int schemaVersion,
        int totalRows,
        int validRows,
        int invalidRows,
        List<ImportRowFailure> rows,
        UUID resubmitOf,
        int reusableFiles
) {
}
