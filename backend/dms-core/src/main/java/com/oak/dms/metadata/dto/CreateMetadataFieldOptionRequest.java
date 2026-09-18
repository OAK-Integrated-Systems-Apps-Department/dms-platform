package com.oak.dms.metadata.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record CreateMetadataFieldOptionRequest(

        @NotBlank
        @Size(max = 150)
        String optionValue,

        @NotBlank
        @Size(max = 150)
        String optionLabel,

        /** Nullable - set only for a cascading child option. */
        UUID parentOptionId,

        int displayOrder
) {
}
