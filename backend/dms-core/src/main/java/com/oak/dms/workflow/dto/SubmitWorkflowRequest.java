package com.oak.dms.workflow.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record SubmitWorkflowRequest(@NotNull UUID documentId) {
}
