package com.oak.dms.storage.exception;

/** Thrown when writing an object to its storage location fails. Maps to 502 (see {@link StorageException}). */
public class StorageUploadException extends StorageException {
    public StorageUploadException(String objectKey, Throwable cause) {
        super("Failed to upload object " + objectKey + ": " + cause.getMessage());
        initCause(cause);
    }
}
