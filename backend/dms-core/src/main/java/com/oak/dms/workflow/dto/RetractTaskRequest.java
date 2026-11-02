package com.oak.dms.workflow.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Retracting your own already-recorded approval (V120) - a reason is mandatory, same as rejecting, and
 * step-up re-authentication is required exactly like signing: this is the same weight of action as
 * recording the decision was in the first place. {@code toString} is overridden to redact
 * {@link #password}/{@link #totp} - never log or persist this record as-is.
 */
public record RetractTaskRequest(
        @NotBlank @Size(max = 2000) String reason,
        @NotBlank String password,
        String totp
) {
    @Override
    public String toString() {
        return "RetractTaskRequest[reason=%s, password=REDACTED, totp=REDACTED]".formatted(reason);
    }
}
