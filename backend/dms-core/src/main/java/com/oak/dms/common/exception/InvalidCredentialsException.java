package com.oak.dms.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Thrown when Keycloak rejects a username/password/OTP combination. Maps to
 * 401 Unauthorized with a single generic message - deliberately identical
 * whether the username doesn't exist, the password is wrong, or the OTP
 * code is wrong, so this exception can never be used to distinguish those
 * cases from the outside (user enumeration).
 */
public class InvalidCredentialsException extends ApiException {
    public InvalidCredentialsException() {
        super(HttpStatus.UNAUTHORIZED, "AUTH_INVALID_CREDENTIALS", "Invalid username or password.");
    }
}
