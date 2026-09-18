package com.oak.dms.security.auth.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** Request body for {@code POST /api/v1/impersonation/start}. */
public record StartImpersonationRequest(

        @NotNull
        UUID targetUserId
) {
}
