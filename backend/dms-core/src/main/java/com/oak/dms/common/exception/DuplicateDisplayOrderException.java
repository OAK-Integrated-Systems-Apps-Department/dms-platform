package com.oak.dms.common.exception;

import org.springframework.http.HttpStatus;

/**
 * Thrown when a request carries two or more identical {@code displayOrder}
 * values within the same batch (e.g. a document-type field mapping publish),
 * rather than the server silently renumbering around the conflict - the
 * caller should know their payload was wrong. Maps to 400 Bad Request.
 */
public class DuplicateDisplayOrderException extends ApiException {
    public DuplicateDisplayOrderException(String message) {
        super(HttpStatus.BAD_REQUEST, "DUPLICATE_DISPLAY_ORDER", message);
    }
}
