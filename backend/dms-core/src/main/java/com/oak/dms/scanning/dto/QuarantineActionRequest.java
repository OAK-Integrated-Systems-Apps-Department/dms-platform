package com.oak.dms.scanning.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body of every quarantine review action - the reason is mandatory and goes to the audit trail. */
public record QuarantineActionRequest(
        @NotBlank(message = "A reason is required")
        @Size(max = 1000, message = "Reason must be at most 1000 characters")
        String reason
) {
}
