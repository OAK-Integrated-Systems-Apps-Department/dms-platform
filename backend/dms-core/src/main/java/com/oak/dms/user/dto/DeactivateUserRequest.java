package com.oak.dms.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code DELETE /api/v1/users/{userId}}. {@code reason} is
 * mandatory here (see AuditActionCatalog's USER_DEACTIVATED entry -
 * deactivation is one of the high-impact actions the audit event catalogue
 * requires a reason for) - free text, typed by the admin performing the
 * deactivation. Sanitized and length-capped again server-side by
 * AuditReasonSanitizer before it's stored on the audit event; the {@code max}
 * here just gives the caller a fast, field-level 400 instead of a silent
 * truncation.
 */
public record DeactivateUserRequest(

        @NotBlank
        @Size(max = 1000)
        String reason
) {
}
