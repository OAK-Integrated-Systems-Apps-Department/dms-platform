package com.oak.dms.common.exception;

import org.springframework.http.HttpStatus;

/**
 * A user's email can't be changed to one another account already has. The same message whether
 * that account is in the caller's tenant or another one (every tenant shares one Keycloak realm),
 * so this can't be used to find out who uses an email elsewhere - see UserServiceImpl#createUser.
 * Maps to 409, code EMAIL_IN_USE, which the Edit User form shows under the email field.
 */
public class EmailInUseException extends ApiException {
    public EmailInUseException() {
        super(HttpStatus.CONFLICT, "EMAIL_IN_USE", "This email is already used by another account.");
    }
}
