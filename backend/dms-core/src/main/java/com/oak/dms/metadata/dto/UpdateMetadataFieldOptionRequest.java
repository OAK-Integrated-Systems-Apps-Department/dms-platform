package com.oak.dms.metadata.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record UpdateMetadataFieldOptionRequest(

        @NotBlank
        @Size(max = 150)
        String optionValue,

        @NotBlank
        @Size(max = 150)
        String optionLabel,

        UUID parentOptionId,

        int displayOrder,

        @NotNull
        Status status
) {
    public enum Status {active, inactive}
}
