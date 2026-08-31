package com.oak.dms.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Thrown when Keycloak rejects a user-creation call because the email is
 * already in use. Keycloak enforces email uniqueness realm-wide (see
 * {@code duplicateEmailsAllowed: false} in the realm config) - since every
 * tenant shares one realm here, this is effectively a global "one email,
 * one account" rule across all clients, not a per-tenant one. That matches
 * real-world email addresses (they identify one person), so it's usually
 * not a problem in practice, but it does mean two different clients can't
 * both onboard an admin who happens to share an email address (a shared
 * team inbox, for example).
 */
public class KeycloakUserAlreadyExistsException extends ApiException {
    public KeycloakUserAlreadyExistsException(String email) {
        super(HttpStatus.CONFLICT, "KEYCLOAK_USER_ALREADY_EXISTS",
                "A user with email '" + email + "' already exists.");
    }
}
