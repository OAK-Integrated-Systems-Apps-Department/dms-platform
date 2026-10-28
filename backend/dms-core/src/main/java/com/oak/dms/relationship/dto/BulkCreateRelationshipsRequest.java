package com.oak.dms.relationship.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/** One type, several documents: exactly one of the two lists holds a single document (the anchor). Up to 200 links. */
public record BulkCreateRelationshipsRequest(
        @NotNull UUID relationshipTypeId,
        @NotEmpty @Size(max = 200) List<UUID> sourceDocumentIds,
        @NotEmpty @Size(max = 200) List<UUID> targetDocumentIds,
        @Size(max = 1000) String note
) {
}
