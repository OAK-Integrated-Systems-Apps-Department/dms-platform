package com.oak.dms.importexport.dto;

import com.oak.dms.importexport.enums.MigrationItemStatus;

import java.util.List;
import java.util.UUID;

/**
 * One template row's result.
 *
 * @param errors       "column: reason" lines, empty unless the row failed or was cancelled
 * @param awaitingScan the file was accepted and is still being scanned for malware
 */
public record MigrationItemResponse(
        UUID itemId,
        int rowNumber,
        String fileName,
        String title,
        MigrationItemStatus status,
        UUID documentId,
        List<String> errors,
        boolean awaitingScan
) {
}
