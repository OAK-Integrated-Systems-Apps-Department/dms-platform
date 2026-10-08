package com.oak.dms.storage.exception;

import com.oak.dms.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * Base type every {@link ObjectStorageService} adapter translates its
 * underlying SDK/{@code IOException} into, so {@code DocumentService} and
 * {@link com.oak.dms.common.exception.GlobalExceptionHandler} only ever see
 * this hierarchy - never an AWS SDK exception, a MinIO/SeaweedFS-specific
 * type, or a raw {@code java.nio.file} exception. Already handled generically
 * by {@code GlobalExceptionHandler}'s {@code ApiException.class} handler, so
 * no new handler code is needed for any subclass here.
 * <p>
 * Default status is 502 Bad Gateway: the storage backend itself failed or
 * was unreachable, not something wrong with the caller's request.
 */
public abstract class StorageException extends ApiException {

    protected StorageException(String message) {
        super(HttpStatus.BAD_GATEWAY, message);
    }

    protected StorageException(HttpStatus status, String message) {
        super(status, message);
    }
}
