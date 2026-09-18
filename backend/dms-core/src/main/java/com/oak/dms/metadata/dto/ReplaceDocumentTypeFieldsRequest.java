package com.oak.dms.metadata.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record ReplaceDocumentTypeFieldsRequest(

        @NotNull
        @Valid
        List<DocumentTypeFieldMappingItem> fields
) {
}
