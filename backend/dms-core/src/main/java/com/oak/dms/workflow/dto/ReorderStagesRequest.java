package com.oak.dms.workflow.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;
import java.util.UUID;

/** Every live stage of the template, in the new order (an exact permutation). */
public record ReorderStagesRequest(@NotEmpty List<UUID> stageIds) {
}
