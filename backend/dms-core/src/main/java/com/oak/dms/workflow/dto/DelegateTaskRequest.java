package com.oak.dms.workflow.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/** Hand my task to someone else (Phase 2 S3). The note is shown to the delegate. */
public record DelegateTaskRequest(@NotNull UUID delegateUserId, @Size(max = 2000) String note) {
}
