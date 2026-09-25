package com.oak.dms.document;

import java.util.Map;
import java.util.UUID;

/**
 * Structured JSON extracted from a document's latest version by
 * ocr-worker's local LLM (document_type, fields, tables, sections -- see
 * backend/workers/ocr-worker/README.md "Structured JSON extraction (local
 * LLM)"). dms-core only enforces tenant ownership and relays the result;
 * ocr-worker owns producing and storing it.
 */
public interface DocumentStructuredDataService {

    /**
     * @throws com.oak.dms.common.exception.ResourceNotFoundException if the
     *         document doesn't exist in the caller's tenant, or ocr-worker has
     *         no structured result for its latest version yet
     */
    Map<String, Object> getStructuredData(UUID documentId);

    /**
     * Queues structured extraction again for the latest version's stored OCR
     * output and returns the {@code pending} result.
     *
     * @throws com.oak.dms.common.exception.StructuredExtractionUnavailableException
     *         if ocr-worker has it disabled or the version has no OCR output yet
     */
    Map<String, Object> requestStructuredExtraction(UUID documentId);
}
