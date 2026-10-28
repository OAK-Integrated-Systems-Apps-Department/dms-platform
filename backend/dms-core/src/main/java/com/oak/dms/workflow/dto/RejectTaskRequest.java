package com.oak.dms.workflow.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** WA-FR-003: a rejection reason is mandatory - a missing or blank one is a 400. */
public record RejectTaskRequest(@NotBlank @Size(max = 2000) String reason) {
}
