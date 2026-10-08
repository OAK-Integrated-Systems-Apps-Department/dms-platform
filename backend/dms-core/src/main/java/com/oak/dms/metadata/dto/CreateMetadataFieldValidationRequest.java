package com.oak.dms.metadata.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.oak.dms.metadata.entity.MetadataFieldValidation;

public record CreateMetadataFieldValidationRequest(

        @NotNull
        MetadataFieldValidation.RuleType ruleType,

        @Size(max = 500)
        String ruleValue,

        @Size(max = 500)
        String errorMessage,

        MetadataFieldValidation.Severity severity
) {
}
