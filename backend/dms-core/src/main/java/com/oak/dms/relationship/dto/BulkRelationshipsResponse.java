package com.oak.dms.relationship.dto;

import java.util.List;
import java.util.UUID;

public record BulkRelationshipsResponse(UUID batchId, List<RelationshipResponse> created) {
}
