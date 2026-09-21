package com.oak.dms.storage.exception;

import org.springframework.http.HttpStatus;

/** Thrown when an object key doesn't exist at its storage location. Maps to 404. */
public class StorageObjectNotFoundException extends StorageException {
    public StorageObjectNotFoundException(String objectKey) {
        super(HttpStatus.NOT_FOUND, "Object not found: " + objectKey);
    }
}
