package com.oak.dms.metadata.dto;

import java.util.UUID;

public record MetadataFieldValidationResponse(
        UUID validationId,
        UUID fieldId,
        String ruleType,
        String ruleValue,
        String errorMessage,
        String severity
) {
}
