package com.oak.dms.compliance;

import com.oak.dms.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/** A refused status change, retention rule or retention action - always carries a stable {@code code}. */
public class ComplianceException extends ApiException {

    private ComplianceException(HttpStatus status, String code, String message) {
        super(status, code, message);
    }

    public static ComplianceException badRequest(String code, String message) {
        return new ComplianceException(HttpStatus.BAD_REQUEST, code, message);
    }

    public static ComplianceException conflict(String code, String message) {
        return new ComplianceException(HttpStatus.CONFLICT, code, message);
    }
}
