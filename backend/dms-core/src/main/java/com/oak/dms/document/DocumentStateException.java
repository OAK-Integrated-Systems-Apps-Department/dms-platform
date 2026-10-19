package com.oak.dms.document;

import com.oak.dms.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * The document is in a state that doesn't allow the request - e.g. it is in
 * an approval and so can't go to the recycle bin. Maps to 409 with a
 * DOCUMENT_* code.
 */
public class DocumentStateException extends ApiException {

    public DocumentStateException(String code, String message) {
        super(HttpStatus.CONFLICT, code, message);
    }
}
