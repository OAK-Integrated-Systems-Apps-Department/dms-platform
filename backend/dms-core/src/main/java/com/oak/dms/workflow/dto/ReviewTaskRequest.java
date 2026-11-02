package com.oak.dms.workflow.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Opening an already-decided task for review (V120) - step-up re-authentication gates even just
 * viewing it again, not only retracting. {@code toString} redacts both fields.
 */
public record ReviewTaskRequest(@NotBlank String password, String totp) {
    @Override
    public String toString() {
        return "ReviewTaskRequest[password=REDACTED, totp=REDACTED]";
    }
}
