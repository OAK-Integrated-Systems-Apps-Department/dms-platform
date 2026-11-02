package com.oak.dms.signature.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Revoking a user's signing key always needs a mandatory reason (HIGH audit). */
public record RevokeSigningKeyRequest(@NotBlank @Size(max = 2000) String reason) {
}
