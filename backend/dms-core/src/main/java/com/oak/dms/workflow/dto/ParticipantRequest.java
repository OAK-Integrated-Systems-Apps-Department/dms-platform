package com.oak.dms.workflow.dto;

import com.oak.dms.workflow.enums.AssigneeType;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record ParticipantRequest(
        @NotNull AssigneeType assigneeType,
        @NotNull UUID assigneeId,
        Integer displayOrder
) {
}
