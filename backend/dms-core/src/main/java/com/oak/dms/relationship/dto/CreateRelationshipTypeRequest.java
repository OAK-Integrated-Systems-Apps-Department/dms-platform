package com.oak.dms.relationship.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateRelationshipTypeRequest(
        @NotBlank @Size(max = 120) String label,
        /** How the link reads from the target; ignored (set to the label) for a symmetric type. */
        @Size(max = 120) String inverseLabel,
        @NotNull @Pattern(regexp = "directional|symmetric") String directionality,
        Boolean preventsCycles,
        @Size(max = 40) String category
) {
}
