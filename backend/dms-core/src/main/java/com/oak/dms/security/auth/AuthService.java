package com.oak.dms.security.auth;

import com.oak.dms.common.exception.InvalidCredentialsException;
import com.oak.dms.common.exception.SessionExpiredException;
import com.oak.dms.security.auth.dto.KeycloakTokenResponse;
import com.oak.dms.security.keycloak.KeycloakAdminProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Business logic behind {@link AuthController}. Two Keycloak surfaces are
 * used here on purpose: the Admin API client (via {@link Keycloak}) only
 * ever to <em>look up</em> whether a username has OTP configured, never to
 * touch credentials; and {@link KeycloakAuthClient} for the actual
 * password/refresh grants, which is the only place a plaintext password
 * or a live token ever flows through.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final KeycloakAuthClient keycloakAuthClient;
    private final AuthCookieService cookieService;
    private final Keycloak keycloakAdminClient;
    private final KeycloakAdminProperties adminProperties;

    /**
     * Whether {@code username} needs an OTP code to sign in. Returns false
     * - not an error, not a distinguishable state - both when no such
     * user exists and when the user exists but has no OTP credential, so
     * this can never be used to enumerate valid usernames. Any failure
     * talking to the Admin API is treated the same way, matching the
     * frontend's own "fail silently, assume MFA isn't required" handling
     * of this endpoint - a transient Keycloak Admin API hiccup should
     * degrade to "ask for the password and let the real login attempt be
     * the source of truth," not block the sign-in page outright.
     */
    public boolean isMfaRequired(String username) {
        try {
            List<UserRepresentation> matches = keycloakAdminClient.realm(adminProperties.getRealm())
                    .users().search(username, true);
            if (matches.isEmpty()) {
                return false;
            }
            String userId = matches.get(0).getId();
            List<CredentialRepresentation> credentials = keycloakAdminClient.realm(adminProperties.getRealm())
                    .users().get(userId).credentials();
            return credentials.stream().anyMatch(c -> "otp".equals(c.getType()));
        } catch (Exception e) {
            log.warn("MFA-required lookup failed for a username, treating as not required: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Attempts the direct-grant login and, on success, writes the session
     * cookies onto {@code response}. On failure, always throws the same
     * {@link InvalidCredentialsException} regardless of the underlying
     * Keycloak reason (unknown user, wrong password, wrong/missing OTP, or
     * a brute-force lockout) - see {@link KeycloakAuthClient} for where
     * that reason is still logged internally for security monitoring.
     */
    public void login(String username, String password, String totp, boolean rememberMe,
                       HttpServletResponse response) {
        KeycloakAuthClient.GrantResult result = keycloakAuthClient.passwordGrant(username, password, totp);
        if (result instanceof KeycloakAuthClient.GrantResult.Failure) {
            throw new InvalidCredentialsException();
        }
        KeycloakTokenResponse tokens = ((KeycloakAuthClient.GrantResult.Success) result).tokens();
        cookieService.writeSessionCookies(response, tokens, rememberMe);
        log.info("Successful login for user '{}'", username);
    }

    /**
     * Exchanges the refresh-token cookie for a fresh token pair, rewriting
     * both cookies. {@code rememberMe} state isn't itself stored anywhere
     * server-side, so a refresh always re-issues the refresh cookie as
     * persistent if one was already present as persistent - in practice,
     * the browser only sends a persistent refresh cookie back at all if
     * the original login was rememberMe=true, so mirroring "was a refresh
     * cookie present" is equivalent without needing separate state.
     */
    public void refresh(HttpServletRequest request, HttpServletResponse response) {
        String refreshToken = readCookie(request, AuthCookieService.REFRESH_TOKEN_COOKIE);
        if (refreshToken == null) {
            throw new SessionExpiredException();
        }
        KeycloakAuthClient.GrantResult result = keycloakAuthClient.refreshGrant(refreshToken);
        if (result instanceof KeycloakAuthClient.GrantResult.Failure) {
            cookieService.clearSessionCookies(response);
            throw new SessionExpiredException();
        }
        KeycloakTokenResponse tokens = ((KeycloakAuthClient.GrantResult.Success) result).tokens();
        cookieService.writeSessionCookies(response, tokens, true);
    }

    /** Revokes the refresh token in Keycloak (best-effort) and always clears the local cookies. */
    public void logout(HttpServletRequest request, HttpServletResponse response) {
        String refreshToken = readCookie(request, AuthCookieService.REFRESH_TOKEN_COOKIE);
        if (refreshToken != null) {
            keycloakAuthClient.revokeRefreshToken(refreshToken);
        }
        cookieService.clearSessionCookies(response);
    }

    private String readCookie(HttpServletRequest request, String name) {
        if (request.getCookies() == null) {
            return null;
        }
        for (Cookie cookie : request.getCookies()) {
            if (name.equals(cookie.getName()) && cookie.getValue() != null && !cookie.getValue().isBlank()) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
