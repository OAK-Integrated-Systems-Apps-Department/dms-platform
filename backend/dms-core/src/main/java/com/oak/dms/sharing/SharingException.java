package com.oak.dms.sharing;

import com.oak.dms.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/** A sharing request that isn't allowed or doesn't make sense; the code tells the UI which. */
public class SharingException extends ApiException {

    public SharingException(HttpStatus status, String code, String message) {
        super(status, code, message);
    }

    static SharingException badRequest(String code, String message) {
        return new SharingException(HttpStatus.BAD_REQUEST, code, message);
    }

    static SharingException forbidden(String code, String message) {
        return new SharingException(HttpStatus.FORBIDDEN, code, message);
    }

    static SharingException conflict(String code, String message) {
        return new SharingException(HttpStatus.CONFLICT, code, message);
    }
}
