package com.oak.dms.common.exception;

import org.springframework.http.HttpStatus;

/**
 * A sensitive action needed the caller to confirm their own password and it wasn't given, or wasn't right.
 * Always 403, never 401: the browser treats every 401 as an expired session and signs the user out, but a wrong
 * password here is only a refused action - the session is fine and the user simply tries again.
 */
public class PasswordConfirmationException extends ApiException {

    public static final String REQUIRED = "PASSWORD_REQUIRED";
    public static final String INCORRECT = "PASSWORD_INCORRECT";
    public static final String OTP_REQUIRED = "PASSWORD_OTP_REQUIRED";
    public static final String UNAVAILABLE = "PASSWORD_CHECK_UNAVAILABLE";

    public PasswordConfirmationException(String code, String message) {
        super(HttpStatus.FORBIDDEN, code, message);
    }
}
