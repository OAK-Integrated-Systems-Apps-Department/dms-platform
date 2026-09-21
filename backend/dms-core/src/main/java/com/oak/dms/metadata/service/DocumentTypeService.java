package com.oak.dms.metadata.service;

import com.oak.dms.metadata.dto.CreateDocumentTypeRequest;
import com.oak.dms.metadata.dto.DocumentTypeResponse;
import com.oak.dms.metadata.dto.UpdateDocumentTypeRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface DocumentTypeService {

    DocumentTypeResponse createDocumentType(CreateDocumentTypeRequest request);

    DocumentTypeResponse getDocumentType(UUID documentTypeId);

    Page<DocumentTypeResponse> listDocumentTypes(Pageable pageable);

    DocumentTypeResponse updateDocumentType(UUID documentTypeId, UpdateDocumentTypeRequest request);

    DocumentTypeResponse activateDocumentType(UUID documentTypeId);

    DocumentTypeResponse deactivateDocumentType(UUID documentTypeId);
}
