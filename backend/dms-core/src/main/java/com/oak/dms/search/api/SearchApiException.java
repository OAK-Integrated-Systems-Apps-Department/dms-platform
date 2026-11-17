package com.oak.dms.search.api;

import com.oak.dms.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/** A search-module refusal with a machine-readable code (and optional structured details) in the standard error body. */
public class SearchApiException extends ApiException {

    public SearchApiException(HttpStatus status, String code, String message) {
        super(status, code, message);
    }

    public SearchApiException(HttpStatus status, String code, String message, Object details) {
        super(status, code, message, details);
    }
}
