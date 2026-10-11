package com.oak.dms.importexport.dto;

/**
 * What a bulk export of a selection would contain, before it is requested.
 *
 * @param totalBytes   combined size of the documents' current versions
 * @param withinLimits false if the selection is empty or over either limit,
 *                     so requesting it would be refused
 */
public record BulkExportPreviewResponse(long documentCount, long totalBytes, int maxDocuments, long maxBytes,
                                        boolean withinLimits) {
}
