package com.oak.dms.storage.exception;

import org.springframework.http.HttpStatus;

/**
 * Thrown when a {@link StorageLocation} is misconfigured (missing bucket/
 * base-path, unresolvable {@code credentialRef}, unsupported provider, an
 * object key that would escape a filesystem location's base path, ...) -
 * a deployment/data problem, not the caller's fault. Maps to 500, unlike
 * every other {@link StorageException} subclass's 502/404.
 */
public class StorageConfigurationException extends StorageException {
    public StorageConfigurationException(String message) {
        super(HttpStatus.INTERNAL_SERVER_ERROR, message);
    }
}
