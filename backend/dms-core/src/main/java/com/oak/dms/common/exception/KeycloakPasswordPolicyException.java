package com.oak.dms.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Thrown when Keycloak's Admin API rejects a new password for violating
 * the realm's configured password policy (length, complexity, history/
 * reuse, etc.). Maps to 400 Bad Request - the reset token itself is left
 * valid so the person can simply try a different password without
 * starting the whole "forgot password" flow over again.
 */
public class KeycloakPasswordPolicyException extends ApiException {
    public KeycloakPasswordPolicyException() {
        super(HttpStatus.BAD_REQUEST, "AUTH_PASSWORD_POLICY_VIOLATION",
                "That password doesn't meet the requirements. Please choose a different one.");
    }
}
