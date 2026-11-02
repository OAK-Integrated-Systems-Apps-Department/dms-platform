package com.oak.dms.signature.exception;

import com.oak.dms.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * A digital-signature request could not proceed - the status varies by
 * reason (400 for a missing consent/OTP, 403 while impersonating, 409 for a
 * locked document or a document that no longer matches its stored hash),
 * so this exposes one factory per status rather than hardcoding one like
 * {@code WorkflowStateException} does.
 */
public class SignatureException extends ApiException {

    private SignatureException(HttpStatus status, String code, String message) {
        super(status, code, message);
    }

    public static SignatureException badRequest(String code, String message) {
        return new SignatureException(HttpStatus.BAD_REQUEST, code, message);
    }

    public static SignatureException forbidden(String code, String message) {
        return new SignatureException(HttpStatus.FORBIDDEN, code, message);
    }

    public static SignatureException conflict(String code, String message) {
        return new SignatureException(HttpStatus.CONFLICT, code, message);
    }

    public static SignatureException unavailable(String code, String message) {
        return new SignatureException(HttpStatus.SERVICE_UNAVAILABLE, code, message);
    }
}
