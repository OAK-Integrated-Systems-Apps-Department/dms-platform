package com.oak.dms.checkout;

import com.oak.dms.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/** A refused check-out, check-in, force-release or legal-hold action - always carries a stable {@code code}. */
public class CheckoutException extends ApiException {

    private CheckoutException(HttpStatus status, String code, String message) {
        super(status, code, message);
    }

    public static CheckoutException badRequest(String code, String message) {
        return new CheckoutException(HttpStatus.BAD_REQUEST, code, message);
    }

    public static CheckoutException conflict(String code, String message) {
        return new CheckoutException(HttpStatus.CONFLICT, code, message);
    }

    /** The editing engine is off or down (CICO-NFR-002): the caller falls back to check-out, download, re-upload. */
    public static CheckoutException unavailable(String code, String message) {
        return new CheckoutException(HttpStatus.SERVICE_UNAVAILABLE, code, message);
    }
}
