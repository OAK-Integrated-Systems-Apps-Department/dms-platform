package com.oak.dms.common.exception;

import com.oak.dms.security.auth.OrganisationStatusGuard;
import org.springframework.http.HttpStatus;

/** The user's organisation is suspended, closed or deactivated - see {@link OrganisationStatusGuard}. */
public class OrganisationInactiveException extends ApiException {
    public OrganisationInactiveException(String message) {
        super(HttpStatus.FORBIDDEN, OrganisationStatusGuard.CODE, message);
    }
}
