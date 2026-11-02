package com.oak.dms.ownership.helper;

import com.oak.dms.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/** An ownership request that can't be accepted; {@code details} carries data the caller can show (counts, flags). */
public class OwnershipException extends ApiException {

    public OwnershipException(HttpStatus status, String code, String message) {
        super(status, code, message);
    }

    public OwnershipException(HttpStatus status, String code, String message, Object details) {
        super(status, code, message, details);
    }

    public static OwnershipException scopeDenied() {
        return new OwnershipException(HttpStatus.FORBIDDEN, "OWNERSHIP_SCOPE_DENIED",
                "You can only transfer documents owned by people in a department you head.");
    }

    public static OwnershipException unchanged() {
        return new OwnershipException(HttpStatus.CONFLICT, "OWNERSHIP_UNCHANGED",
                "The document already belongs to that owner.");
    }

    public static OwnershipException invalidTarget(String message) {
        return new OwnershipException(HttpStatus.UNPROCESSABLE_CONTENT, "OWNERSHIP_TARGET_INVALID", message);
    }

    public static OwnershipException invalid(String code, String message) {
        return new OwnershipException(HttpStatus.UNPROCESSABLE_CONTENT, code, message);
    }

    public static OwnershipException conflict(String code, String message, Object details) {
        return new OwnershipException(HttpStatus.CONFLICT, code, message, details);
    }
}
