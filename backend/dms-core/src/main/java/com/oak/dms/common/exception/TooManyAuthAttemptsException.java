package com.oak.dms.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Thrown by {@link com.oak.dms.security.auth.LoginAttemptLimiter} when a
 * single client IP has made too many authentication requests in the
 * current window. This is a coarse, app-level defense in depth alongside
 * Keycloak's own per-username brute-force protection (which does nothing
 * to stop one IP from trying many different usernames). Maps to 429 Too
 * Many Requests.
 */
public class TooManyAuthAttemptsException extends ApiException {
    public TooManyAuthAttemptsException() {
        super(HttpStatus.TOO_MANY_REQUESTS, "AUTH_RATE_LIMITED",
                "Too many attempts. Please wait a moment and try again.");
    }
}
