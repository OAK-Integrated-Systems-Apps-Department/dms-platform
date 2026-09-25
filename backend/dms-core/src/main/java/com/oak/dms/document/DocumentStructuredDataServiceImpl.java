package com.oak.dms.document;

import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.ocrworker.OcrWorkerClient;
import com.oak.dms.security.auth.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

/**
 * No @Transactional: the only database read is one findById, and the
 * ocr-worker call must not hold a connection open while it runs.
 */
@Service
@RequiredArgsConstructor
public class DocumentStructuredDataServiceImpl implements DocumentStructuredDataService {

    private final DocumentRepository documentRepository;
    private final OcrWorkerClient ocrWorkerClient;

    @Override
    public Map<String, Object> getStructuredData(UUID documentId) {
        Map<String, Object> result = ocrWorkerClient.getStructuredResult(latestVersionId(documentId));
        if (result == null) {
            throw new ResourceNotFoundException("structured_data", documentId);
        }
        return result;
    }

    @Override
    public Map<String, Object> requestStructuredExtraction(UUID documentId) {
        return ocrWorkerClient.requestStructuredExtraction(latestVersionId(documentId));
    }

    private UUID latestVersionId(UUID documentId) {
        UUID tenantId = TenantContext.getTenantId();
        // Another tenant's document is reported exactly like a missing one.
        Document document = documentRepository.findById(documentId)
                .filter(d -> d.getTenantId().equals(tenantId))
                .orElseThrow(() -> new ResourceNotFoundException("documents", documentId));
        return document.getLatestVersionId();
    }
}
