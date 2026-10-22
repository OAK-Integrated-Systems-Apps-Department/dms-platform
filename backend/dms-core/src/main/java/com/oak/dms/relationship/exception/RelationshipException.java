package com.oak.dms.relationship.exception;

import com.oak.dms.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/** A relationship request that can't be accepted; {@code details} carries data the caller can show (a loop's chain, bulk errors). */
public class RelationshipException extends ApiException {

    public RelationshipException(HttpStatus status, String code, String message) {
        super(status, code, message);
    }

    public RelationshipException(HttpStatus status, String code, String message, Object details) {
        super(status, code, message, details);
    }

    public static RelationshipException conflict(String code, String message) {
        return new RelationshipException(HttpStatus.CONFLICT, code, message);
    }

    public static RelationshipException invalid(String code, String message) {
        return new RelationshipException(HttpStatus.UNPROCESSABLE_CONTENT, code, message);
    }
}
