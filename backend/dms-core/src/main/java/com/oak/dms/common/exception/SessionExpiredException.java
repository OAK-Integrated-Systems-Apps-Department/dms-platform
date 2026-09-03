package com.oak.dms.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Thrown when there is no refresh-token cookie to refresh from, or Keycloak
 * rejects the refresh token (expired, revoked, or the session was ended
 * elsewhere - e.g. an admin forcing logout). Maps to 401 Unauthorized; the
 * frontend treats this the same as "not logged in" and sends the user back
 * to /login.
 */
public class SessionExpiredException extends ApiException {
    public SessionExpiredException() {
        super(HttpStatus.UNAUTHORIZED, "AUTH_SESSION_EXPIRED", "Your session has expired. Please sign in again.");
    }
}
