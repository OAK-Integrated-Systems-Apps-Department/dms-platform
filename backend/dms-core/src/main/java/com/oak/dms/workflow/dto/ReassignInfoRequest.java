package com.oak.dms.workflow.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/** The initiator or a tenant admin points an open request at someone else. */
public record ReassignInfoRequest(@NotNull UUID requestedFromUserId, @Size(max = 2000) String reason) {
}
