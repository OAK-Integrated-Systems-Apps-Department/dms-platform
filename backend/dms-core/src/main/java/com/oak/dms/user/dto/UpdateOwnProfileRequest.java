package com.oak.dms.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for {@code PUT /api/v1/users/me}: a user updating their own
 * profile. Deliberately narrower than {@link UpdateUserRequest} (which backs
 * the admin-facing {@code PUT /api/v1/users/{userId}}) - only first/last name
 * are here, so there's no field through which a caller could flip their own
 * {@code mfaEnabled}, {@code status}, tenant, or role assignments via this
 * endpoint.
 */
public record UpdateOwnProfileRequest(

        @NotBlank
        @Size(max = 255)
        String firstName,

        @Size(max = 255)
        String lastName,

        /** Job title (1.4) - shown on a placed signature alongside name/department/date; null clears it. */
        @Size(max = 150)
        String position
) {
}
