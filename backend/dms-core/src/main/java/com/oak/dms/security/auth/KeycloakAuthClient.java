package com.oak.dms.security.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oak.dms.security.auth.dto.KeycloakErrorResponse;
import com.oak.dms.security.auth.dto.KeycloakTokenResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

/**
 * Talks to Keycloak's {@code /protocol/openid-connect/token} and
 * {@code /protocol/openid-connect/logout} endpoints directly over HTTP, as
 * the {@code oak-dms-web} public client - never through the Keycloak
 * Admin API/client used elsewhere in this module. This is the only class
 * in dms-core that ever sees a plaintext user password, and it never logs
 * one; every log line here is deliberately restricted to the username and
 * Keycloak's machine-readable error code.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KeycloakAuthClient {

    private final RestTemplate keycloakAuthRestTemplate;
    private final KeycloakAuthProperties properties;
    private final ObjectMapper objectMapper;

    /** Result of a call to Keycloak's token endpoint: either a token set or a classified failure. */
    public sealed interface GrantResult permits GrantResult.Success, GrantResult.Failure {
        record Success(KeycloakTokenResponse tokens) implements GrantResult {
        }

        record Failure(boolean likelyLockedOrDisabled, String keycloakError) implements GrantResult {
        }
    }

    /**
     * Resource Owner Password Credentials grant: exchanges a username,
     * password, and (if the account has OTP configured) a TOTP code for a
     * token pair. {@code totp} is omitted from the form entirely when
     * null/blank, rather than sent empty - Keycloak's OTP execution
     * treats a present-but-empty {@code totp} parameter differently from
     * an absent one on some versions.
     */
    public GrantResult passwordGrant(String username, String password, String totp) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "password");
        form.add("client_id", properties.getClientId());
        form.add("username", username);
        form.add("password", password);
        if (totp != null && !totp.isBlank()) {
            form.add("totp", totp);
        }
        return callTokenEndpoint(form, username);
    }

    /** Refresh-token grant, used to silently renew the access token without re-prompting for credentials. */
    public GrantResult refreshGrant(String refreshToken) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "refresh_token");
        form.add("client_id", properties.getClientId());
        form.add("refresh_token", refreshToken);
        return callTokenEndpoint(form, null);
    }

    /**
     * Revokes the session server-side in Keycloak so the refresh token
     * can't be replayed after logout. Best-effort: if Keycloak is
     * unreachable or the token is already invalid, we still proceed with
     * clearing the local cookies - a user who clicked "log out" should
     * never be stuck "logged in" just because the revoke call failed.
     */
    public void revokeRefreshToken(String refreshToken) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", properties.getClientId());
        form.add("refresh_token", refreshToken);
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            keycloakAuthRestTemplate.postForEntity(logoutUrl(), new HttpEntity<>(form, headers), Void.class);
        } catch (RestClientException e) {
            log.warn("Keycloak logout/revoke call failed (continuing to clear local session): {}", e.getMessage());
        }
    }

    private GrantResult callTokenEndpoint(MultiValueMap<String, String> form, String usernameForLogging) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        try {
            ResponseEntity<KeycloakTokenResponse> response = keycloakAuthRestTemplate.postForEntity(
                    tokenUrl(), new HttpEntity<>(form, headers), KeycloakTokenResponse.class);
            if (response.getBody() == null) {
                log.error("Keycloak token endpoint returned {} with an empty body", response.getStatusCode());
                return new GrantResult.Failure(false, "empty_response");
            }
            return new GrantResult.Success(response.getBody());
        } catch (HttpStatusCodeException e) {
            return handleGrantFailure(e, usernameForLogging);
        } catch (ResourceAccessException e) {
            log.error("Could not reach Keycloak at {}: {}", properties.getServerUrl(), e.getMessage());
            return new GrantResult.Failure(false, "keycloak_unreachable");
        }
    }

    private GrantResult handleGrantFailure(HttpStatusCodeException e, String usernameForLogging) {
        KeycloakErrorResponse body = parseErrorBody(e);
        String errorCode = body != null ? body.error() : "unknown_error";
        String description = body != null ? body.errorDescription() : "";

        // Keycloak's own signal for a brute-force-locked or admin-disabled
        // account. We log this distinctly for security monitoring, but -
        // see AuthService - the caller is still told the same generic
        // "invalid credentials" message either way, so this endpoint can't
        // be used to discover which accounts exist by seeing which ones
        // report a lockout instead of a plain wrong-password result.
        boolean lockedOrDisabled = description != null
                && (description.contains("Account is disabled")
                    || description.contains("Account disabled")
                    || description.contains("temporarily locked"));

        if (usernameForLogging != null) {
            log.info("Keycloak rejected login for '{}': status={} error={} locked={}",
                    usernameForLogging, e.getStatusCode().value(), errorCode, lockedOrDisabled);
        } else {
            log.info("Keycloak rejected token refresh: status={} error={}", e.getStatusCode().value(), errorCode);
        }
        return new GrantResult.Failure(lockedOrDisabled, errorCode);
    }

    private KeycloakErrorResponse parseErrorBody(HttpStatusCodeException e) {
        try {
            String responseBody = e.getResponseBodyAsString();
            if (responseBody == null || responseBody.isBlank()) {
                return null;
            }
            return objectMapper.readValue(responseBody, KeycloakErrorResponse.class);
        } catch (Exception parseFailure) {
            return null;
        }
    }

    private String tokenUrl() {
        return properties.getServerUrl() + "/realms/" + properties.getRealm()
                + "/protocol/openid-connect/token";
    }

    private String logoutUrl() {
        return properties.getServerUrl() + "/realms/" + properties.getRealm()
                + "/protocol/openid-connect/logout";
    }
}
