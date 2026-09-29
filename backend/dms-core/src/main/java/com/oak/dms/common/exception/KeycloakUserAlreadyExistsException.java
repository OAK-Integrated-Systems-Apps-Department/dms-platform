package com.oak.dms.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Thrown when Keycloak rejects a user-creation call because the email or
 * username is already in use. Keycloak enforces both realm-wide (see
 * {@code duplicateEmailsAllowed: false} in the realm config and Keycloak's
 * own built-in username uniqueness) - since every tenant shares one realm
 * here, this is effectively a global "one email, one username, one
 * account" rule across all clients, not a per-tenant one. The caller's own
 * tenant-scoped pre-checks (see {@code UserServiceImpl#createUser}) catch
 * the common case of a duplicate within the same tenant before this is
 * ever reached; this fires for the remaining cross-tenant collision, where
 * some OTHER tenant already registered the same email or username.
 * <p>
 * Deliberately doesn't say which of the two collided: Keycloak's own 409
 * response isn't parsed for that detail, and guessing wrong would be worse
 * than not saying - see this exception's caller for why the message stays
 * this generic on purpose (a distinguishable shape here would let a caller
 * fingerprint whether a given email/username belongs to a user in some
 * other tenant).
 */
public class KeycloakUserAlreadyExistsException extends ApiException {
    public KeycloakUserAlreadyExistsException(String email, String username) {
        super(HttpStatus.CONFLICT, "KEYCLOAK_USER_ALREADY_EXISTS",
                "A user with email '" + email + "' or username '" + username + "' already exists.");
    }
}
