package com.oak.dms.document;

import com.oak.dms.document.dto.DocumentVersionResponse;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;
import java.util.UUID;

public interface DocumentService {

    DocumentVersionResponse uploadDocument(MultipartFile file, String documentTypeName);

    /** Relays ocr-worker's exact output JSON -- see OcrWorkerClient's javadoc. */
    Map<String, Object> runOcr(UUID versionId);
}
