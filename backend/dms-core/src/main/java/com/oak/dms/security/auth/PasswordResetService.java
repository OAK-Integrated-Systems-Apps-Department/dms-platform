package com.oak.dms.security.auth;

import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.catalog.AuditObjectTypes;
import com.oak.dms.audit.enums.AuditOutcome;
import com.oak.dms.audit.service.AuditService;
import com.oak.dms.common.config.BrandingProperties;
import com.oak.dms.common.exception.InvalidResetTokenException;
import com.oak.dms.common.exception.KeycloakPasswordPolicyException;
import com.oak.dms.security.keycloak.KeycloakAdminProperties;
import com.oak.dms.security.keycloak.KeycloakUserService;
import com.oak.dms.user.User;
import com.oak.dms.user.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.WebApplicationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Self-service "forgot password" flow, entirely on dms-core's own domain:
 * request a reset at {@code /reset-password}, land on our own
 * {@code /update-password?token=...} page from the emailed link, submit a
 * new password there. Deliberately does not use Keycloak's
 * {@code executeActionsEmail(["UPDATE_PASSWORD"])} - that sends the
 * person to Keycloak's own hosted required-action page, which is exactly
 * the redirect this class exists to avoid. Keycloak remains the actual
 * credential store; only the token issuance/verification step moves into
 * dms-core.
 * <p>
 * {@link #issueForAdminReset} is the same underlying token/email
 * mechanism, used by {@code UserServiceImpl#resetPassword} when an
 * administrator triggers "Reset password" for a specific user from User
 * Management - so both entry points land the user on the same custom
 * {@code /update-password} page instead of one going through dms-core
 * and the other through Keycloak's UI.
 * <p>
 * {@link #issueForNewUser} is the same mechanism again, used by
 * {@code UserServiceImpl#createUser} right after a brand-new account is
 * provisioned. Newly-created Keycloak users get an unguessable temporary
 * password nobody is ever told (see
 * {@link com.oak.dms.security.keycloak.KeycloakUserService#createUser}),
 * so this is effectively how their account is activated - deliberately
 * not via Keycloak's own {@code executeActionsEmail(["VERIFY_EMAIL",
 * "UPDATE_PASSWORD"])}, which would send Keycloak's default-themed email
 * and land the person on Keycloak's own hosted page instead of dms-core's
 * branded one.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PasswordResetService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    /** 256 bits of entropy, URL-safe - long enough that guessing is infeasible even at scale. */
    private static final int TOKEN_BYTES = 32;

    private final UserRepository userRepository;
    private final KeycloakUserService keycloakUserService;
    private final Keycloak keycloakAdminClient;
    private final KeycloakAdminProperties keycloakAdminProperties;
    private final PasswordResetTokenRepository tokenRepository;
    private final JavaMailSender mailSender;
    private final BrandingProperties branding;
    private final AuditService auditService;

    @Value("${app.frontend-base-url}")
    private String frontendBaseUrl;

    @Value("${app.password-reset.token-ttl-minutes}")
    private long tokenTtlMinutes;

    @Value("${app.password-reset.mail-from}")
    private String mailFrom;

    /**
     * Always completes the same way regardless of whether {@code usernameOrEmail}
     * matches an account - same "never leak account existence" contract as
     * {@link AuthService#isMfaRequired}. Any failure (no match, Keycloak
     * lookup error, mail-send error) is logged and swallowed here rather
     * than surfaced to {@link AuthController}, which is what lets the
     * controller return an identical response either way.
     */
    @Transactional
    public void requestReset(String usernameOrEmail, HttpServletRequest request) {
        try {
            Optional<UserRepresentation> keycloakUser = findKeycloakUser(usernameOrEmail);
            if (keycloakUser.isEmpty()) {
                log.info("Password reset requested for an unrecognized username/email");
                return;
            }
            UUID keycloakId = UUID.fromString(keycloakUser.get().getId());
            Optional<User> dmsUser = userRepository.findByKeycloakIdAndDeletedFalse(keycloakId);
            if (dmsUser.isEmpty()) {
                // Exists in Keycloak but not (or no longer) as an active DMS
                // user - e.g. deactivated. Same silent no-op as "no match"
                // above, for the same enumeration-safety reason.
                log.info("Password reset requested for a Keycloak account with no active DMS user");
                return;
            }

            User user = dmsUser.get();
            issueTokenAndSendEmail(user, clientIp(request), EmailKind.SELF_SERVICE);

            // No TenantContext bound here - same reasoning as
            // #resetPassword's own PASSWORD_RESET_COMPLETED call below:
            // this endpoint is public, so tenant/actor come explicitly
            // from the now-resolved user rather than context defaults.
            // The two "no match" branches above return before this point
            // and are deliberately never audited - logging an event tied
            // to an unresolved/nonexistent account would be either
            // impossible (no tenant/actor to attach it to) or itself a
            // way to leak account existence via the audit trail, which is
            // exactly what this method's own doc comment says every
            // branch here must avoid.
            auditService.event(AuditActionCatalog.PASSWORD_RESET_REQUESTED)
                    .tenantId(user.getTenantId())
                    .actor(user.getUserId(), user.getDisplayName())
                    .objectType(AuditObjectTypes.USER)
                    .objectId(user.getUserId())
                    .resourceName(user.getUsername())
                    .outcome(AuditOutcome.SUCCESS)
                    .log();
        } catch (Exception e) {
            // Never let a lookup/send failure distinguish this response
            // from the success path, or surface as a 500 that would do
            // the same via timing/error-shape.
            log.warn("Password reset request failed (treated as a silent no-op): {}", e.getMessage());
        }
    }

    /**
     * Admin-triggered equivalent of {@link #requestReset}: called from
     * {@code UserServiceImpl#resetPassword} once the caller has already
     * resolved and tenant-verified the target {@link User}, so there's no
     * lookup step and - unlike {@code requestReset} - no need to hide
     * whether a match was found. A failure here is a real failure and is
     * allowed to propagate so the admin sees it (e.g. as a toast), rather
     * than being swallowed the way an unauthenticated caller's request
     * must be.
     */
    @Transactional
    public void issueForAdminReset(User user) {
        issueTokenAndSendEmail(user, null, EmailKind.ADMIN_RESET);
    }

    /**
     * Called from {@code UserServiceImpl#createUser} immediately after a
     * new account is provisioned - this is the invited user's only route
     * to setting a real password and signing in, so unlike
     * {@link #issueForAdminReset} a send failure here is left to propagate
     * (same reasoning as that method's own doc comment: there's a real
     * person on the other end of this call who needs to know it worked).
     */
    @Transactional
    public void issueForNewUser(User user) {
        issueTokenAndSendEmail(user, null, EmailKind.NEW_USER);
    }

    /**
     * Used by the frontend to show "link invalid/expired" before
     * rendering the form and, when valid, which of the two
     * {@code /update-password} interfaces to show - see
     * {@link PasswordResetToken.TokenKind}.
     */
    @Transactional(readOnly = true)
    public Optional<PasswordResetToken.TokenKind> validateToken(String rawToken) {
        return findValidToken(rawToken).map(PasswordResetToken::getKind);
    }

    /**
     * Redeems the token: sets the new password in Keycloak, marks this
     * token (and any other outstanding ones for the same user) used, so
     * a token can never be replayed even if the confirmation email was
     * somehow intercepted after use.
     *
     * @throws InvalidResetTokenException if the token is unknown, already used, or expired
     * @throws KeycloakPasswordPolicyException if Keycloak rejects the new password
     */
    @Transactional
    public void resetPassword(String rawToken, String newPassword) {
        PasswordResetToken token = findValidToken(rawToken).orElseThrow(InvalidResetTokenException::new);
        User user = userRepository.findById(token.getUserId()).orElseThrow(InvalidResetTokenException::new);

        try {
            keycloakUserService.setPassword(user.getKeycloakId(), newPassword);
        } catch (WebApplicationException e) {
            int status = e.getResponse() != null ? e.getResponse().getStatus() : -1;
            if (status == 400) {
                // Realm password policy rejected it (too short/weak/reused/etc.) -
                // the token itself stays valid so the person can just try again.
                throw new KeycloakPasswordPolicyException();
            }
            throw e;
        }

        recordPasswordChanged(user);

        token.setUsedAt(OffsetDateTime.now());
        tokenRepository.save(token);
        invalidateOutstandingTokens(user.getUserId());

        // No TenantContext bound here - this endpoint is public (the
        // person redeeming the token isn't authenticated yet), same
        // reasoning as AuthService#login's LOGIN_SUCCESS event - so
        // tenant/actor are passed explicitly from the now-resolved user
        // rather than relying on AuditService's context-based defaults.
        auditService.event(AuditActionCatalog.PASSWORD_RESET_COMPLETED)
                .tenantId(user.getTenantId())
                .actor(user.getUserId(), user.getDisplayName())
                .objectType(AuditObjectTypes.USER)
                .objectId(user.getUserId())
                .resourceName(user.getUsername())
                .outcome(AuditOutcome.SUCCESS)
                .log();

        log.info("Password reset completed via self-service flow for user {}", user.getUserId());
    }

    /**
     * Best-effort, mirroring {@code AuthService#recordLastLogin}'s own
     * reasoning: the Keycloak credential write in {@link #resetPassword}
     * has already succeeded by the time this is called, so a failure
     * persisting this timestamp locally shouldn't be reported back as a
     * failed password reset - the person's password really did change
     * either way.
     */
    private void recordPasswordChanged(User user) {
        try {
            user.setLastPasswordChangedAt(OffsetDateTime.now());
            userRepository.save(user);
        } catch (Exception e) {
            log.warn("Could not record last password change for user {}: {}", user.getUserId(), e.getMessage());
        }
    }

    private Optional<PasswordResetToken> findValidToken(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return Optional.empty();
        }
        return tokenRepository.findByTokenHash(hash(rawToken))
                .filter(t -> !t.isUsed())
                .filter(t -> !t.isExpired(OffsetDateTime.now()));
    }

    private void invalidateOutstandingTokens(UUID userId) {
        List<PasswordResetToken> outstanding = tokenRepository.findAllByUserIdAndUsedAtIsNull(userId);
        OffsetDateTime now = OffsetDateTime.now();
        outstanding.forEach(t -> t.setUsedAt(now));
        tokenRepository.saveAll(outstanding);
    }

    /**
     * Shared by {@link #requestReset}, {@link #issueForAdminReset}, and
     * {@link #issueForNewUser}: invalidates the user's other outstanding
     * tokens (so an
     * already-emailed link can never be redeemed once a newer reset has
     * been issued), stores a new one, and emails the link. {@code requestedIp}
     * is null for the admin-triggered path - there's no end-user request
     * to attribute it to, only the admin's own authenticated action,
     * which is already captured elsewhere in the audit trail.
     */
    private void issueTokenAndSendEmail(User user, String requestedIp, EmailKind kind) {
        String rawToken = generateToken();
        PasswordResetToken token = PasswordResetToken.builder()
                .userId(user.getUserId())
                .tokenHash(hash(rawToken))
                .expiresAt(OffsetDateTime.now().plusMinutes(tokenTtlMinutes))
                .requestedIp(requestedIp)
                .kind(toTokenKind(kind))
                .build();

        invalidateOutstandingTokens(user.getUserId());
        tokenRepository.save(token);

        sendEmail(user, rawToken, kind);
    }

    /**
     * Exact-match lookup against Keycloak's Admin API, same approach as
     * {@link AuthService#isMfaRequired} - {@code usernameOrEmail} is
     * matched against Keycloak's username, which
     * {@link KeycloakUserService#createUser} always sets to the account's
     * email, so a single exact-match search covers both "username" and
     * "email" from the person's point of view.
     */
    private Optional<UserRepresentation> findKeycloakUser(String usernameOrEmail) {
        List<UserRepresentation> matches = keycloakAdminClient.realm(keycloakAdminProperties.getRealm())
                .users().search(usernameOrEmail, true);
        return matches.stream().findFirst();
    }

    /**
     * Which of the three flows in this class triggered the email - only
     * changes the subject/body copy below. All three land the person on
     * the exact same {@code /update-password?token=...} page and go
     * through the exact same token issuance/redemption code. Persisted on
     * the token as the coarser {@link PasswordResetToken.TokenKind} (see
     * {@link #toTokenKind}) so {@code /update-password} can tell
     * "welcome, set your password" apart from "reset your password" once
     * the link is actually clicked - unlike this enum, which never
     * survives past the email send.
     */
    private enum EmailKind {
        SELF_SERVICE,
        ADMIN_RESET,
        NEW_USER
    }

    /**
     * {@code SELF_SERVICE} and {@code ADMIN_RESET} both collapse to
     * {@code PASSWORD_RESET} - end users experience the two identically,
     * so {@code /update-password} only needs to distinguish "setting a
     * password for the first time" from "everything else".
     */
    private static PasswordResetToken.TokenKind toTokenKind(EmailKind kind) {
        return kind == EmailKind.NEW_USER
                ? PasswordResetToken.TokenKind.NEW_USER
                : PasswordResetToken.TokenKind.PASSWORD_RESET;
    }

    private void sendEmail(User user, String rawToken, EmailKind kind) {
        String link = frontendBaseUrl + "/update-password?token=" + rawToken;

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(mailFrom);
        message.setTo(user.getEmail());

        String productName = branding.getProductName();

        if (kind == EmailKind.NEW_USER) {
            message.setSubject("Welcome to " + productName + " - set your password");
            message.setText(
                    "Hi " + user.getFirstName() + ",\n\n"
                            + "An " + productName + " account has been created for you. Click the link below to choose a "
                            + "password and finish setting up your account:\n\n"
                            + link + "\n\n"
                            + "This link expires in " + tokenTtlMinutes + " minutes. "
                            + "If you weren't expecting this, you can safely ignore this email.\n\n"
                            + "- " + productName);
        } else {
            message.setSubject("Reset your " + productName + " password");
            message.setText(
                    "Hi " + user.getFirstName() + ",\n\n"
                            + "We received a request to reset your " + productName + " password. Click the link below to choose a new one:\n\n"
                            + link + "\n\n"
                            + "This link expires in " + tokenTtlMinutes + " minutes. "
                            + "If you didn't request this, you can safely ignore this email.\n\n"
                            + "- " + productName);
        }

        mailSender.send(message);
    }

    private static String generateToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(rawToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is guaranteed available on every standard JVM.
            throw new IllegalStateException(e);
        }
    }

    /** Same first-hop X-Forwarded-For handling as {@link LoginAttemptLimiter}, for the audit trail only. */
    private static String clientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
