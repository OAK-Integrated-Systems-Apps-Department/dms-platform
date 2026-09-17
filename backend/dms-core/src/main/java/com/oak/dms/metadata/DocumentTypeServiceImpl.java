package com.oak.dms.metadata;

import com.oak.dms.common.exception.DuplicateResourceException;
import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.metadata.dto.CreateDocumentTypeRequest;
import com.oak.dms.metadata.dto.DocumentTypeResponse;
import com.oak.dms.metadata.dto.UpdateDocumentTypeRequest;
import com.oak.dms.security.auth.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class DocumentTypeServiceImpl implements DocumentTypeService {

    private final DocumentTypeRepository documentTypeRepository;
    private final DocumentTypeMapper documentTypeMapper;

    @Override
    public DocumentTypeResponse createDocumentType(CreateDocumentTypeRequest request) {
        UUID tenantId = TenantContext.getTenantId();

        if (documentTypeRepository.existsByTenantIdAndTypeCodeAndDeletedFalse(tenantId, request.typeCode())) {
            throw new DuplicateResourceException("A document type already exists with code " + request.typeCode());
        }

        DocumentType documentType = DocumentType.builder()
                .tenantId(tenantId)
                .typeCode(request.typeCode())
                .typeName(request.typeName())
                .description(request.description())
                .currentSchemaVersion(1)
                .requiresWatermark(request.requiresWatermark())
                .coeditingEnabled(request.coeditingEnabled())
                .status(DocumentType.DocumentTypeStatus.active)
                .createdBy(TenantContext.getUserId())
                .build();

        List<DocumentType> siblings = documentTypeRepository.findAllByTenantOrderedByDisplayOrder(tenantId);
        List<DocumentType> touchedSiblings = DisplayOrderNormalizer.renumber(
                siblings, documentType, request.displayOrder(),
                DocumentType::getDisplayOrder, DocumentType::setDisplayOrder);

        DocumentType saved = documentTypeRepository.save(documentType);
        if (!touchedSiblings.isEmpty()) {
            documentTypeRepository.saveAll(touchedSiblings);
        }
        log.info("Created document type {} ({}) in tenant {}", saved.getDocumentTypeId(), saved.getTypeCode(), tenantId);
        return documentTypeMapper.toResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public DocumentTypeResponse getDocumentType(UUID documentTypeId) {
        return documentTypeMapper.toResponse(findOwnedByCallerTenant(documentTypeId));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<DocumentTypeResponse> listDocumentTypes(Pageable pageable) {
        return documentTypeRepository.findAllByTenant(TenantContext.getTenantId(), pageable)
                .map(documentTypeMapper::toResponse);
    }

    @Override
    public DocumentTypeResponse updateDocumentType(UUID documentTypeId, UpdateDocumentTypeRequest request) {
        DocumentType documentType = findOwnedByCallerTenant(documentTypeId);
        UUID tenantId = TenantContext.getTenantId();

        documentType.setTypeName(request.typeName());
        documentType.setDescription(request.description());
        documentType.setRequiresWatermark(request.requiresWatermark());
        documentType.setCoeditingEnabled(request.coeditingEnabled());
        documentType.setUpdatedBy(TenantContext.getUserId());
        documentType.setUpdatedAt(OffsetDateTime.now());

        List<DocumentType> siblings = documentTypeRepository.findAllByTenantOrderedByDisplayOrder(tenantId).stream()
                .filter(t -> !t.getDocumentTypeId().equals(documentTypeId))
                .toList();
        List<DocumentType> touchedSiblings = DisplayOrderNormalizer.renumber(
                siblings, documentType, request.displayOrder(),
                DocumentType::getDisplayOrder, DocumentType::setDisplayOrder);

        DocumentType saved = documentTypeRepository.save(documentType);
        if (!touchedSiblings.isEmpty()) {
            documentTypeRepository.saveAll(touchedSiblings);
        }
        log.info("Updated document type {}", saved.getDocumentTypeId());
        return documentTypeMapper.toResponse(saved);
    }

    @Override
    public DocumentTypeResponse activateDocumentType(UUID documentTypeId) {
        return setStatus(documentTypeId, DocumentType.DocumentTypeStatus.active);
    }

    @Override
    public DocumentTypeResponse deactivateDocumentType(UUID documentTypeId) {
        return setStatus(documentTypeId, DocumentType.DocumentTypeStatus.inactive);
    }

    private DocumentTypeResponse setStatus(UUID documentTypeId, DocumentType.DocumentTypeStatus status) {
        DocumentType documentType = findOwnedByCallerTenant(documentTypeId);
        documentType.setStatus(status);
        documentType.setUpdatedBy(TenantContext.getUserId());
        documentType.setUpdatedAt(OffsetDateTime.now());
        DocumentType saved = documentTypeRepository.save(documentType);
        log.info("Set document type {} status to {}", documentTypeId, status);
        return documentTypeMapper.toResponse(saved);
    }

    private DocumentType findOwnedByCallerTenant(UUID documentTypeId) {
        return documentTypeRepository
                .findByDocumentTypeIdAndTenantIdAndDeletedFalse(documentTypeId, TenantContext.getTenantId())
                .orElseThrow(() -> new ResourceNotFoundException("DocumentType", documentTypeId));
    }
}
