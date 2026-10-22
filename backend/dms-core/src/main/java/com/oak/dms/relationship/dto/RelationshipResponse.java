package com.oak.dms.relationship.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/** A link as created or changed. Direction is source -> target; {@code inverseLabel} is how the target reads it. */
public record RelationshipResponse(
        UUID relationshipId,
        UUID relationshipTypeId,
        String typeCode,
        String typeLabel,
        String inverseLabel,
        UUID sourceDocumentId,
        UUID targetDocumentId,
        String origin,
        UUID batchId,
        String note,
        OffsetDateTime createdAt
) {
}
