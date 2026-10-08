package com.oak.dms.storage.exception;

/** Thrown when reading an object from its storage location fails (for a reason other than the object simply not existing - see {@link StorageObjectNotFoundException}). */
public class StorageDownloadException extends StorageException {
    public StorageDownloadException(String objectKey, Throwable cause) {
        super("Failed to download object " + objectKey + ": " + cause.getMessage());
        initCause(cause);
    }
}
