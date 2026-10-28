package com.oak.dms.workflow.dto;

import jakarta.validation.constraints.Size;

/** The comment is optional on approval; stored as the task's decision_reason. */
public record ApproveTaskRequest(@Size(max = 2000) String comment) {
}
