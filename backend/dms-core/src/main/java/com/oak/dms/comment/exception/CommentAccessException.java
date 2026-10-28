package com.oak.dms.comment.exception;

import com.oak.dms.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/** 403 - e.g. editing someone else's comment. */
public class CommentAccessException extends ApiException {
    public CommentAccessException(String code, String message) {
        super(HttpStatus.FORBIDDEN, code, message);
    }
}
