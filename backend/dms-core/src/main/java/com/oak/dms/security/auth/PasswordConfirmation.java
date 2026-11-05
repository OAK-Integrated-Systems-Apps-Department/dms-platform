package com.oak.dms.security.auth;

import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.catalog.AuditObjectTypes;
import com.oak.dms.audit.enums.AuditOutcome;
import com.oak.dms.audit.service.AuditService;
import com.oak.dms.common.exception.PasswordConfirmationException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Makes a sensitive action prove it is really the signed-in person asking: the caller's OWN password, sent in the
 * {@value #PASSWORD_HEADER} header (a header, so DELETE works the same as POST and PUT), is checked against Keycloak
 * before the action runs. A stolen or left-open session alone can't create, change or remove an organisation.
 * <p>
 * The check is Keycloak's own password grant - the same check as signing in, so its brute-force lockout applies and
 * the platform's IP rate limit is applied on top. The tokens that grant returns are revoked at once, so confirming
 * never leaves a session behind. The password is read, used and dropped: it is never logged, stored or put in an
 * audit record; a failed confirmation is audited without it.
 * <p>
 * An account with an authenticator app (OTP) must also send the current code in {@value #TOTP_HEADER}; without it the
 * answer is {@code PASSWORD_OTP_REQUIRED}, so the screen can ask for it.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PasswordConfirmation {

    public static final String PASSWORD_HEADER = "X-Confirm-Password";
    public static final String TOTP_HEADER = "X-Confirm-Totp";

    private final KeycloakAuthClient keycloakAuthClient;
    private final AuthService authService;
    private final LoginAttemptLimiter rateLimiter;
    private final AuditService auditService;

    /** The caller must confirm their password. */
    public void require(HttpServletRequest request) {
        String password = request.getHeader(PASSWORD_HEADER);
        if (password == null || password.isEmpty()) {
            throw new PasswordConfirmationException(PasswordConfirmationException.REQUIRED,
                    "Enter your password to confirm this.");
        }
        rateLimiter.checkAndRecord(request);
        String username = signedInUsername();
        if (username == null) {
            throw new PasswordConfirmationException(PasswordConfirmationException.UNAVAILABLE,
                    "Your password can't be checked for this session. Sign out and sign in again.");
        }
        String totp = request.getHeader(TOTP_HEADER);
        boolean needsCode = totp == null || totp.isBlank();
        if (needsCode && authService.isMfaRequired(username)) {
            throw new PasswordConfirmationException(PasswordConfirmationException.OTP_REQUIRED,
                    "Enter the code from your authenticator app as well.");
        }

        KeycloakAuthClient.GrantResult result = keycloakAuthClient.passwordGrant(username, password, totp);
        if (result instanceof KeycloakAuthClient.GrantResult.Success success) {
            // A confirmation is not a sign-in: end the session the grant just opened.
            String refresh = success.tokens().refreshToken();
            if (refresh != null) {
                keycloakAuthClient.revokeRefreshToken(refresh);
            }
            return;
        }
        KeycloakAuthClient.GrantResult.Failure failure = (KeycloakAuthClient.GrantResult.Failure) result;
        if ("keycloak_unreachable".equals(failure.keycloakError()) || "empty_response".equals(failure.keycloakError())) {
            throw new PasswordConfirmationException(PasswordConfirmationException.UNAVAILABLE,
                    "Your password can't be checked just now. Try again in a moment.");
        }
        audit(username);
        // The same words whether the password or the code was wrong - nothing to learn from the difference.
        throw new PasswordConfirmationException(PasswordConfirmationException.INCORRECT,
                "That password isn't right.");
    }

    /**
     * As {@link #require}, but only when the caller is a platform administrator - for endpoints that an organisation's
     * own administrator may also use on their own organisation, where this screen's rule is not asked of them.
     */
    public void requireIfPlatformAdmin(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        boolean platformAdmin = authentication != null && authentication.getAuthorities().stream()
                .anyMatch(a -> "ROLE_PLATFORM_ADMIN".equals(a.getAuthority()));
        if (platformAdmin) {
            require(request);
        }
    }

    private static String signedInUsername() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
            String name = jwt.getClaimAsString("preferred_username");
            return name == null || name.isBlank() ? null : name;
        }
        return null;
    }

    private void audit(String username) {
        try {
            auditService.event(AuditActionCatalog.PASSWORD_CONFIRMATION_FAILED)
                    .platformLevel()
                    .objectType(AuditObjectTypes.TENANT)
                    .outcome(AuditOutcome.DENIED)
                    .newValue(Map.of("username", username))
                    .log();
        } catch (RuntimeException e) {
            log.warn("Could not record a failed password confirmation: {}", e.getMessage());
        }
    }
}
