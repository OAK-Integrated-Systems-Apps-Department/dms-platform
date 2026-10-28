package com.oak.dms.folder;

import com.oak.dms.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/** A folder change that would break the tree's rules (duplicate name, cycle, not empty), or isn't allowed. */
public class FolderException extends ApiException {

    public FolderException(HttpStatus status, String code, String message) {
        super(status, code, message);
    }

    static FolderException badRequest(String code, String message) {
        return new FolderException(HttpStatus.BAD_REQUEST, code, message);
    }

    static FolderException conflict(String code, String message) {
        return new FolderException(HttpStatus.CONFLICT, code, message);
    }

    static FolderException forbidden(String code, String message) {
        return new FolderException(HttpStatus.FORBIDDEN, code, message);
    }
}
