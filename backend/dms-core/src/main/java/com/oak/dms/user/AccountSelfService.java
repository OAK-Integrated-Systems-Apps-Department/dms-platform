package com.oak.dms.user;

import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.catalog.AuditObjectTypes;
import com.oak.dms.audit.helper.AuditEventBuilder;
import com.oak.dms.audit.enums.AuditOutcome;
import com.oak.dms.audit.service.AuditService;
import com.oak.dms.common.exception.ApiException;
import com.oak.dms.security.auth.KeycloakAuthClient;
import com.oak.dms.security.auth.PasswordChangedMailer;
import com.oak.dms.security.keycloak.KeycloakUserService;
import com.oak.dms.user.dto.ChangeOwnPasswordRequest;
import com.oak.dms.user.dto.UpdateOwnIdentityRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Lets the signed-in caller change their own name, email and password from
 * inside the DMS, instead of being sent to Keycloak's account console.
 * <p>
 * Works WITHOUT tenant context on purpose. A platform administrator belongs
 * to no tenant and so has no local user row at all, yet still has an account
 * to maintain; everything here is therefore keyed off the Keycloak id from
 * the caller's own token, and the local row is updated only as a mirror,
 * when one happens to exist.
 * <p>
 * Keycloak remains the system of record for identity. The local
 * {@code users} row is a copy kept for joins and display, so it is written
 * second and never on its own - if Keycloak rejects the change, nothing
 * local is touched.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountSelfService {

    private final KeycloakUserService keycloakUserService;
    private final KeycloakAuthClient keycloakAuthClient;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final PasswordChangedMailer passwordChangedMailer;

    /**
     * Updates the caller's own name and email.
     *
     * @param keycloakId the caller's {@code sub} claim - never a value from the request body
     */
    @Transactional
    public void updateIdentity(UUID keycloakId, UpdateOwnIdentityRequest request) {
        String email = request.email().trim();
        if (keycloakUserService.emailTakenByAnotherAccount(email, keycloakId)) {
            throw new EmailAlreadyUsedException();
        }

        String firstName = request.firstName().trim();
        String lastName = request.lastName() == null ? null : request.lastName().trim();

        // Keycloak first: it owns the identity, and a failure here must
        // leave the local copy untouched rather than the two disagreeing.
        keycloakUserService.updateProfile(keycloakId, firstName, lastName, email);

        Optional<User> local = userRepository.findByKeycloakIdAndDeletedFalse(keycloakId);
        local.ifPresent(user -> {
            user.setFirstName(firstName);
            user.setLastName(lastName);
            user.setEmail(email);
            user.setDisplayName(displayName(firstName, lastName, email));
            user.setUpdatedAt(OffsetDateTime.now());
            userRepository.save(user);
        });

        audit(AuditActionCatalog.USER_UPDATED, local.orElse(null), keycloakId,
                Map.of("fields", "firstName,lastName,email"));
    }

    /**
     * Changes the caller's own password, after proving they know the current
     * one.
     *
     * @param username the caller's {@code preferred_username} claim - what Keycloak's token endpoint expects
     */
    public void changePassword(UUID keycloakId, String username, ChangeOwnPasswordRequest request) {
        if (request.currentPassword().equals(request.newPassword())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "PASSWORD_UNCHANGED",
                    "Your new password must be different from your current one.") {
            };
        }

        // The whole point of this method: Keycloak's admin API would happily
        // set a new password without the old one, so this request is what
        // stops a stolen session from becoming a permanent account takeover.
        KeycloakAuthClient.GrantResult verification =
                keycloakAuthClient.passwordGrant(username, request.currentPassword(), request.totp());
        if (verification instanceof KeycloakAuthClient.GrantResult.Failure failure) {
            log.info("Password change refused for '{}': {}", username, failure.keycloakError());
            throw new CurrentPasswordIncorrectException(failure.likelyLockedOrDisabled());
        }

        keycloakUserService.setPassword(keycloakId, request.newPassword());

        Optional<User> local = userRepository.findByKeycloakIdAndDeletedFalse(keycloakId);
        audit(AuditActionCatalog.PASSWORD_CHANGED, local.orElse(null), keycloakId, Map.of());
        log.info("Password changed by the account holder for '{}'", username);
        notifyPasswordChanged(local.orElse(null), username);
    }

    /**
     * Security notice to the account holder. A platform administrator has no
     * local row, so their address comes from Keycloak instead.
     */
    private void notifyPasswordChanged(User local, String username) {
        if (local != null) {
            passwordChangedMailer.send(local.getEmail(), local.getFirstName());
            return;
        }
        try {
            keycloakUserService.findByUsernameOrEmail(username).ifPresent(kc ->
                    passwordChangedMailer.send(kc.getEmail(), kc.getFirstName()));
        } catch (Exception e) {
            log.warn("Could not look up '{}' for the password-changed email: {}", username, e.getMessage());
        }
    }

    /**
     * Records the event against the local user when there is one. A platform
     * administrator has neither tenant nor local user id, so those columns
     * stay null (they are nullable) and the Keycloak id identifies the actor
     * instead - a null-everything event would be worse than a partial one.
     */
    private void audit(String action, User local, UUID keycloakId, Map<String, Object> extra) {
        try {
            Map<String, Object> details = new java.util.HashMap<>(extra);
            details.put("keycloakId", keycloakId.toString());
            // Marks it as the holder changing their own account, not an
            // administrator changing someone else's - the same action code
            // covers both, and the difference matters when reading the trail.
            details.put("selfService", true);

            AuditEventBuilder builder = auditService.event(action)
                    .objectType(AuditObjectTypes.USER)
                    // Required, NOT NULL in audit_events. Leaving it unset
                    // fails at COMMIT rather than here, which is past the
                    // catch below - so the Keycloak change would already have
                    // happened and the caller would still see an error.
                    .outcome(AuditOutcome.SUCCESS)
                    .newValue(details);
            if (local != null) {
                builder.tenantId(local.getTenantId())
                        .actor(local.getUserId(), local.getDisplayName())
                        .objectId(local.getUserId());
            } else {
                // A platform administrator acts as no tenant and has no local
                // user id - the same case PlatformAuditEventController already
                // queries as tenant_id IS NULL.
                builder.platformLevel();
            }
            auditService.persist(builder);
        } catch (RuntimeException e) {
            // The account change itself already succeeded in Keycloak; failing
            // the request now would tell the caller it did not work when it did.
            log.warn("Could not audit {} for keycloak id {}", action, keycloakId, e);
        }
    }

    private static String displayName(String firstName, String lastName, String email) {
        String full = (firstName + " " + (lastName == null ? "" : lastName)).trim();
        return full.isBlank() ? email : full;
    }

    /** 409: the email belongs to a different account. */
    public static class EmailAlreadyUsedException extends ApiException {
        public EmailAlreadyUsedException() {
            super(HttpStatus.CONFLICT, "EMAIL_ALREADY_USED",
                    "That email address is already used by another account.");
        }
    }

    /**
     * 403, not 401: a 401 would be read by the frontend as an expired
     * session and bounce the caller to the login page, losing what they
     * typed - when in fact they are signed in perfectly well and simply
     * mistyped their current password.
     */
    public static class CurrentPasswordIncorrectException extends ApiException {
        public CurrentPasswordIncorrectException(boolean lockedOrDisabled) {
            super(HttpStatus.FORBIDDEN, "CURRENT_PASSWORD_INCORRECT", lockedOrDisabled
                    ? "Your account is temporarily locked. Try again later."
                    : "Your current password is not correct.");
        }
    }
}
