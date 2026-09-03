package com.oak.dms.security.auth.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Mirrors the JSON body Keycloak's {@code /protocol/openid-connect/token}
 * endpoint returns on a successful grant. Internal only - never
 * serialized back out to the browser. The raw {@code access_token} and
 * {@code refresh_token} strings only ever live for the duration of this
 * request; {@link com.oak.dms.security.auth.AuthCookieService} moves them
 * straight into httpOnly cookies and nothing downstream logs this record.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KeycloakTokenResponse(
        @JsonProperty("access_token") String accessToken,
        @JsonProperty("expires_in") long expiresInSeconds,
        @JsonProperty("refresh_token") String refreshToken,
        @JsonProperty("refresh_expires_in") long refreshExpiresInSeconds,
        @JsonProperty("token_type") String tokenType
) {
}
