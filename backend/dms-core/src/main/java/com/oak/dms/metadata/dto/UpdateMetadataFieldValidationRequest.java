package com.oak.dms.metadata.dto;

import jakarta.validation.constraints.Size;

import com.oak.dms.metadata.MetadataFieldValidation;

public record UpdateMetadataFieldValidationRequest(

        @Size(max = 500)
        String ruleValue,

        @Size(max = 500)
        String errorMessage,

        MetadataFieldValidation.Severity severity
) {
}
