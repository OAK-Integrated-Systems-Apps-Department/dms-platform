package com.oak.dms.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Thrown when a password-reset token doesn't exist, has already been used,
 * or has expired. Maps to 400 Bad Request with a single generic message -
 * deliberately identical for all three cases (like
 * {@link InvalidCredentialsException} for login) so the response can never
 * be used to distinguish "never existed" from "already redeemed" from
 * "expired".
 */
public class InvalidResetTokenException extends ApiException {
    public InvalidResetTokenException() {
        super(HttpStatus.BAD_REQUEST, "AUTH_INVALID_RESET_TOKEN",
                "This password reset link is invalid or has expired. Please request a new one.");
    }
}
