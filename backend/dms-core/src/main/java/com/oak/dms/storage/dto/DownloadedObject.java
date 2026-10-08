package com.oak.dms.storage.dto;

import java.io.InputStream;

/**
 * The result of {@code ObjectStorageService.download} - the content stream
 * plus the metadata a caller needs to set response headers (content-length/
 * content-type) without a second round trip to the storage backend.
 * {@code content} must be closed by the caller.
 */
public record DownloadedObject(
        InputStream content,
        long sizeBytes,
        String contentType
) {
}
