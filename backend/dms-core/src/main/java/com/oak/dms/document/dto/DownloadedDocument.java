package com.oak.dms.document.dto;

import java.io.InputStream;

/**
 * The result of {@code DocumentService#downloadDocument} - {@code content}
 * must be closed by the caller. {@code contentType}/{@code sizeBytes} come
 * from {@code DocumentVersion} (the authoritative record), not from
 * whatever best-effort value the storage layer itself reports.
 */
public record DownloadedDocument(
        InputStream content,
        long sizeBytes,
        String contentType,
        String filename
) {
}
