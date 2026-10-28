package com.oak.dms.notification;

import com.oak.dms.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/** 409: e.g. trying to turn off email for a critical category (3.2, N12). */
public class NotificationConflictException extends ApiException {

    public NotificationConflictException(String code, String message) {
        super(HttpStatus.CONFLICT, code, message);
    }
}
