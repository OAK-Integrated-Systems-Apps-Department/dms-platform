package com.oak.dms.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Thrown when a call to the Keycloak Admin REST API fails. Maps to
 * 502 Bad Gateway, since the failure is in a downstream system dms-core
 * depends on, not in the caller's request.
 */
public class KeycloakIntegrationException extends ApiException {
    public KeycloakIntegrationException(String message) {
        super(HttpStatus.BAD_GATEWAY, message);
    }

    public KeycloakIntegrationException(String message, Throwable cause) {
        super(HttpStatus.BAD_GATEWAY, message);
        initCause(cause);
    }
}
