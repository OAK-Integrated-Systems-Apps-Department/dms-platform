package com.oak.dms.relationship.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public record RelationshipCountsRequest(@NotEmpty @Size(max = 200) List<UUID> documentIds) {
}
