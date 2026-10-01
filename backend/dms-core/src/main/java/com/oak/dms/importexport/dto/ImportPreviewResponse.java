package com.oak.dms.importexport.dto;

import java.util.List;
import java.util.UUID;

/**
 * A template checked without importing anything: which rows would fail and
 * why. Files aren't uploaded for a preview, so whether each row's file
 * exists is only checked when the import is started.
 *
 * @param problemRows only the rows with problems, at most {@code MAX_LISTED}
 */
public record ImportPreviewResponse(
        UUID documentTypeId,
        String documentTypeName,
        int schemaVersion,
        int totalRows,
        int validRows,
        int invalidRows,
        List<ProblemRow> problemRows
) {

    public static final int MAX_LISTED = 500;

    public record ProblemRow(int rowNumber, String fileName, List<String> errors) {
    }
}
