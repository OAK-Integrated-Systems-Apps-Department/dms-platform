package com.oak.dms.comment.exception;

import com.oak.dms.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/** 422 - the comment can't be accepted as given (wrong thread, instance of another document, unknown mention). */
public class CommentValidationException extends ApiException {
    public CommentValidationException(String code, String message) {
        super(HttpStatus.UNPROCESSABLE_CONTENT, code, message);
    }
}
