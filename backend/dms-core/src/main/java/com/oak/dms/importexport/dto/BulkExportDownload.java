package com.oak.dms.importexport.dto;

import java.io.InputStream;

/** A bulk export's ZIP, opened for download - {@code content} must be closed by the caller. */
public record BulkExportDownload(String filename, long sizeBytes, InputStream content) {
}
