package com.oak.dms.importexport.dto;

/**
 * How many documents an export request matches, before it is started.
 *
 * @param maxDocuments the most one export may hold ({@code dms.export.max-documents})
 */
public record ExportPreviewResponse(long documentCount, int maxDocuments) {

    public boolean withinLimit() {
        return documentCount > 0 && documentCount <= maxDocuments;
    }
}
