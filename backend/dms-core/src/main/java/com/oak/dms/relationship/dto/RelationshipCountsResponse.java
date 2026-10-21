package com.oak.dms.relationship.dto;

import java.util.Map;
import java.util.UUID;

/** Active links per document, for the documents the caller can open (the others are left out). */
public record RelationshipCountsResponse(Map<UUID, Long> counts) {
}
