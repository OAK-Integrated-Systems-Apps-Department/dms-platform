package com.oak.dms.relationship.dto;

import jakarta.validation.constraints.Size;

import java.util.UUID;

/** Only the fields sent change. The two documents are fixed - remove the link and make a new one to change them. */
public record UpdateRelationshipRequest(
        UUID relationshipTypeId,
        @Size(max = 1000) String note
) {
}
