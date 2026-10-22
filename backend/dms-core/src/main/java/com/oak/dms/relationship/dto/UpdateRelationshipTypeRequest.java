package com.oak.dms.relationship.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Only the fields sent are changed. A system type only takes label, inverseLabel, enabled and displayOrder. */
public record UpdateRelationshipTypeRequest(
        @Size(min = 1, max = 120) String label,
        @Size(min = 1, max = 120) String inverseLabel,
        Boolean enabled,
        Integer displayOrder,
        @Pattern(regexp = "directional|symmetric") String directionality,
        Boolean preventsCycles
) {
}
