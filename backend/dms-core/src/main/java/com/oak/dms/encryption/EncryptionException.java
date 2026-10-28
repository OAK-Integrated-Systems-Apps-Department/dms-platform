package com.oak.dms.encryption;

import com.oak.dms.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/** A key-management request that can't be done right now (a job already running, encryption off). */
public class EncryptionException extends ApiException {

    public EncryptionException(HttpStatus status, String code, String message) {
        super(status, code, message);
    }
}
