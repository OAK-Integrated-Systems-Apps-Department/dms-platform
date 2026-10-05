package com.oak.dms.importexport.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** Body of {@code POST /bulk-imports}: the document type every uploaded file becomes. */
public record CreateBulkImportRequest(
        @NotNull UUID documentTypeId
) {
}
