package com.oak.dms.security.auth.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Mirrors the JSON body Keycloak's token endpoint returns on a failed
 * grant, e.g. {@code {"error":"invalid_grant","error_description":"Invalid
 * user credentials"}}. {@code error_description} is inspected internally
 * (see {@link com.oak.dms.security.auth.AuthService}) to distinguish a
 * brute-force lockout from ordinary bad credentials, but is never echoed
 * back to the client verbatim - Keycloak's wording is an implementation
 * detail we don't want to leak or depend on the browser to render safely.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KeycloakErrorResponse(
        @JsonProperty("error") String error,
        @JsonProperty("error_description") String errorDescription
) {
}
