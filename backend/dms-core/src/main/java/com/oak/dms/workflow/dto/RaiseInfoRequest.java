package com.oak.dms.workflow.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Ask someone for a document or an answer, pausing my task (decisions 11-15). dueAt is informational. */
public record RaiseInfoRequest(@NotNull UUID requestedFromUserId, @NotBlank @Size(max = 2000) String message,
                               OffsetDateTime dueAt) {
}
