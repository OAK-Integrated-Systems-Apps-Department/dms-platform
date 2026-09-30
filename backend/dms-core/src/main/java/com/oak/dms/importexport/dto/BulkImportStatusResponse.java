package com.oak.dms.importexport.dto;

import java.util.List;
import java.util.UUID;

public record BulkImportStatusResponse(
        UUID batchId,
        String status,
        int totalRecords,
        int filesReceived,
        int successCount,
        int failureCount,
        List<String> missingFilenames
) {
}
