package com.oak.dms.signature.dto;

import com.oak.dms.signature.enums.SignatureMeaning;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * The signing ceremony: explicit {@link #consent} plus step-up
 * re-authentication (password, and {@code totp} when the signer has MFA
 * enabled) alongside the plain approval fields. {@link #toString} is
 * overridden to redact {@link #password}/{@link #totp} - never log or
 * persist this record as-is.
 */
public record SignAndApproveRequest(
        @Size(max = 2000) String comment,
        @NotNull SignatureMeaning meaning,
        @AssertTrue(message = "Explicit consent to sign is required") boolean consent,
        @NotBlank String password,
        String totp
) {
    @Override
    public String toString() {
        return "SignAndApproveRequest[comment=%s, meaning=%s, consent=%s, password=REDACTED, totp=REDACTED]"
                .formatted(comment, meaning, consent);
    }
}
