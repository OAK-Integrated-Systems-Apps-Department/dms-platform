package com.oak.dms.workflow.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CancelWorkflowRequest(@NotBlank @Size(max = 2000) String reason) {
}
