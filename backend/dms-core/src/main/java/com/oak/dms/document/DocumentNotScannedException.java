package com.oak.dms.document;

import com.oak.dms.common.exception.ApiException;
import org.springframework.http.HttpStatus;

import java.util.UUID;

/**
 * A document version exists but has not passed malware scanning - in
 * practice, a legacy version uploaded before the scan gate existed (V39 left
 * those at {@code scan_status = pending}). Its bytes are never served. Maps
 * to 409.
 */
public class DocumentNotScannedException extends ApiException {
    public DocumentNotScannedException(UUID documentId) {
        super(HttpStatus.CONFLICT, "DOCUMENT_NOT_SCANNED",
                "Document " + documentId + " has not passed malware scanning and cannot be downloaded");
    }
}
