package com.oak.dms.security.auth;

import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.enums.AuditAuthMethod;
import com.oak.dms.audit.catalog.AuditObjectTypes;
import com.oak.dms.audit.enums.AuditOutcome;
import com.oak.dms.audit.service.AuditService;
import com.oak.dms.common.exception.InvalidCredentialsException;
import com.oak.dms.common.exception.SessionExpiredException;
import com.oak.dms.security.auth.dto.KeycloakTokenResponse;
import com.oak.dms.security.keycloak.KeycloakAdminProperties;
import com.oak.dms.user.User;
import com.oak.dms.user.UserRepository;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

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
    private final UserRepository userRepository;
    private final AuditService auditService;

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
        if (result instanceof KeycloakAuthClient.GrantResult.Failure failure) {
            // No actor/tenant is resolved here on purpose: a failed grant
            // must never be used to confirm whether `username` belongs to
            // a real account (see this method's own doc comment) - the
            // resourceName snapshot below is for internal investigation
            // only, never returned to the caller. actor_id/tenant_id both
            // fall through to AuditService's SYSTEM/null defaults.
            auditService.event(AuditActionCatalog.LOGIN_FAILURE)
                    .objectType(AuditObjectTypes.USER)
                    .resourceName(username)
                    .outcome(AuditOutcome.FAILURE)
                    .failureReason(failure.keycloakError())
                    .log();
            throw new InvalidCredentialsException();
        }
        KeycloakTokenResponse tokens = ((KeycloakAuthClient.GrantResult.Success) result).tokens();
        cookieService.writeSessionCookies(response, tokens, rememberMe);
        log.info("Successful login for user '{}'", username);
        User user = recordLastLogin(username);

        // TenantContext isn't bound yet at this point in the request (the
        // session cookies were only just written above) - actor/tenant
        // must be passed explicitly rather than relying on AuditService's
        // TenantContext-based defaults.
        auditService.event(AuditActionCatalog.LOGIN_SUCCESS)
                .tenantId(user != null ? user.getTenantId() : null)
                .actor(user != null ? user.getUserId() : null, user != null ? user.getDisplayName() : username)
                .objectType(AuditObjectTypes.USER)
                // No objectId: a login event has no single associated
                // object distinct from the actor itself (see the
                // object_id note in docs/audit_events_schema_spec.md -
                // "Null for events with no single object (e.g. login)").
                .resourceName(username)
                .outcome(AuditOutcome.SUCCESS)
                // Reflects the factor Keycloak actually verified: a totp
                // value only reaches this point (a successful grant) if
                // Keycloak accepted it, never merely because one was
                // submitted alongside a wrong/unnecessary code.
                .authMethod(totp != null && !totp.isBlank() ? AuditAuthMethod.MFA : AuditAuthMethod.PASSWORD)
                .log();
    }

    /**
     * Best-effort: resolves {@code username} back to its local {@link User}
     * row (same Admin API username/email search {@link #isMfaRequired} already
     * uses) and stamps {@code last_login_at}. Deliberately never allowed to
     * fail the login itself - a lookup hiccup here shouldn't turn an
     * already-successful Keycloak authentication into a 500 for the person
     * signing in. Only called from {@link #login}, not {@link #refresh} -
     * silently renewing an existing session isn't a new login.
     *
     * Returns the resolved (and now-updated) row, or null if it couldn't be
     * resolved - {@link #login} uses this same result to populate the
     * LOGIN_SUCCESS audit event's actor/tenant, rather than doing a second
     * lookup.
     */
    private User recordLastLogin(String username) {
        try {
            List<UserRepresentation> matches = keycloakAdminClient.realm(adminProperties.getRealm())
                    .users().search(username, true);
            if (matches.isEmpty()) {
                return null;
            }
            UUID keycloakId = UUID.fromString(matches.get(0).getId());
            return userRepository.findByKeycloakIdAndDeletedFalse(keycloakId).map(user -> {
                user.setLastLoginAt(OffsetDateTime.now());
                return userRepository.save(user);
            }).orElse(null);
        } catch (Exception e) {
            log.warn("Could not record last login for user '{}': {}", username, e.getMessage());
            return null;
        }
    }

    /**
     * Exchanges the refresh-token cookie for a fresh token pair, rewriting
     * both cookies. {@code rememberMe} state isn't itself stored anywhere
     * server-side, so a refresh always re-issues the refresh cookie as
     * persistent if one was already present as persistent - in practice,
     * the browser only sends a persistent refresh cookie back at all if
     * the original login was rememberMe=true, so mirroring "was a refresh
     * cookie present" is equivalent without needing separate state.
     *
     * Deliberately not audited: same reasoning as {@link #recordLastLogin}
     * not being called from here - silently renewing an already-valid
     * session isn't a security-relevant event on its own, and it would
     * dominate the audit trail with noise (every access-token expiry, for
     * every signed-in user, on a timer) with no actionable signal behind it.
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

        // TenantContext may or may not be bound here, depending on whether
        // the caller's access token was still valid at logout time -
        // AuditService's own TenantContext-unbound fallback (SYSTEM actor,
        // null tenant) handles the case where it isn't, so no explicit
        // actor/tenant override is needed either way.
        auditService.event(AuditActionCatalog.LOGOUT)
                .objectType(AuditObjectTypes.USER)
                .outcome(AuditOutcome.SUCCESS)
                .log();
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
