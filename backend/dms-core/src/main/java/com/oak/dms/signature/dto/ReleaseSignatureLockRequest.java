package com.oak.dms.signature.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Unlocking a signature-locked document always needs a mandatory reason (DSI-FR-004, HIGH audit). */
public record ReleaseSignatureLockRequest(@NotBlank @Size(max = 2000) String reason) {
}
