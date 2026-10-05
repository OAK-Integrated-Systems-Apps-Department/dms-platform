package com.oak.dms.importexport.dto;

import java.util.UUID;

/**
 * @param schemaVersion the document type's schema version the batch was
 *                      created at - its defaults are the ones applied
 * @param maxFiles      how many files (accepted and rejected) the batch can take
 */
public record BulkImportCreatedResponse(
        UUID batchId,
        UUID documentTypeId,
        int schemaVersion,
        int maxFiles
) {
}
