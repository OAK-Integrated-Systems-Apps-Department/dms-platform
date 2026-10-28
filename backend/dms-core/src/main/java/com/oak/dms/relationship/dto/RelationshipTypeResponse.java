package com.oak.dms.relationship.dto;

import java.util.UUID;

/** One relationship type. {@code usageCount} is the number of active links using it. */
public record RelationshipTypeResponse(
        UUID relationshipTypeId,
        String code,
        String category,
        String label,
        String inverseLabel,
        String directionality,
        boolean preventsCycles,
        boolean system,
        boolean enabled,
        int displayOrder,
        long usageCount
) {
}
