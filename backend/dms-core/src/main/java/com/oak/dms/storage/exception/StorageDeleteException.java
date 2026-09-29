package com.oak.dms.storage.exception;

/** Thrown when removing an object from its storage location fails. */
public class StorageDeleteException extends StorageException {
    public StorageDeleteException(String objectKey, Throwable cause) {
        super("Failed to delete object " + objectKey + ": " + cause.getMessage());
        initCause(cause);
    }
}
