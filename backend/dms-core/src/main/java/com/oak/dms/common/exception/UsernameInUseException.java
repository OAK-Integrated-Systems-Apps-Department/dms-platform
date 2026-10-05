package com.oak.dms.common.exception;

import org.springframework.http.HttpStatus;

/**
 * A new user can't have a username another account already has. Like {@link EmailInUseException},
 * one message whether that account is in the caller's tenant or another (one shared Keycloak
 * realm). Maps to 409, code USERNAME_IN_USE, shown under the username field.
 */
public class UsernameInUseException extends ApiException {
    public UsernameInUseException() {
        super(HttpStatus.CONFLICT, "USERNAME_IN_USE", "This username is already taken.");
    }
}
