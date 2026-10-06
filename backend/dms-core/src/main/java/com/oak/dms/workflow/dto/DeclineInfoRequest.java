package com.oak.dms.workflow.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Declining a request needs a reason (decision 15). */
public record DeclineInfoRequest(@NotBlank @Size(max = 2000) String reason) {
}
