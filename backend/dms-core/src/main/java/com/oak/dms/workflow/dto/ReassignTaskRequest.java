package com.oak.dms.workflow.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/** An admin moves any open task to another user; the reason is mandatory and audited. */
public record ReassignTaskRequest(@NotNull UUID userId, @NotBlank @Size(max = 2000) String reason) {
}
