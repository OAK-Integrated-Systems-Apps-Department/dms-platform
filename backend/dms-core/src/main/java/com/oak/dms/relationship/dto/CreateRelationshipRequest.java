package com.oak.dms.relationship.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/** Link {@code sourceDocumentId} to {@code targetDocumentId}: the source "has" the type's label, the target the inverse. */
public record CreateRelationshipRequest(
        @NotNull UUID relationshipTypeId,
        @NotNull UUID sourceDocumentId,
        @NotNull UUID targetDocumentId,
        @Size(max = 1000) String note
) {
}
