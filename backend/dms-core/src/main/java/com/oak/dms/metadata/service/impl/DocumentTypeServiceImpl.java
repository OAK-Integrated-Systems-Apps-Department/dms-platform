package com.oak.dms.metadata.service.impl;

import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.catalog.AuditObjectTypes;
import com.oak.dms.audit.enums.AuditOutcome;
import com.oak.dms.audit.service.AuditService;
import com.oak.dms.common.exception.DuplicateResourceException;
import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.metadata.dto.CreateDocumentTypeRequest;
import com.oak.dms.metadata.mapper.DocumentTypeMapper;
import com.oak.dms.metadata.dto.DocumentTypeResponse;
import com.oak.dms.metadata.dto.UpdateDocumentTypeRequest;
import com.oak.dms.metadata.entity.DocumentType;
import com.oak.dms.metadata.repository.DocumentTypeRepository;
import com.oak.dms.metadata.service.DocumentTypeService;
import com.oak.dms.metadata.helper.DisplayOrderNormalizer;
import com.oak.dms.security.auth.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class DocumentTypeServiceImpl implements DocumentTypeService {

    private final DocumentTypeRepository documentTypeRepository;
    private final DocumentTypeMapper documentTypeMapper;
    private final AuditService auditService;

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

        auditService.event(AuditActionCatalog.DOCUMENT_TYPE_CREATED)
                .objectType(AuditObjectTypes.DOCUMENT_TYPE)
                .objectId(saved.getDocumentTypeId())
                .resourceName(saved.getTypeCode())
                .objectVersion(saved.getCurrentSchemaVersion())
                .outcome(AuditOutcome.SUCCESS)
                .newValue(Map.of(
                        "typeCode", saved.getTypeCode(),
                        "typeName", saved.getTypeName(),
                        "description", saved.getDescription() == null ? "" : saved.getDescription(),
                        "requiresWatermark", saved.isRequiresWatermark(),
                        "coeditingEnabled", saved.isCoeditingEnabled()))
                .log();

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

        Map<String, Object> previousValue = Map.of(
                "typeName", documentType.getTypeName(),
                "description", documentType.getDescription() == null ? "" : documentType.getDescription(),
                "requiresWatermark", documentType.isRequiresWatermark(),
                "coeditingEnabled", documentType.isCoeditingEnabled());

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

        auditService.event(AuditActionCatalog.DOCUMENT_TYPE_UPDATED)
                .objectType(AuditObjectTypes.DOCUMENT_TYPE)
                .objectId(saved.getDocumentTypeId())
                .resourceName(saved.getTypeCode())
                .objectVersion(saved.getCurrentSchemaVersion())
                .outcome(AuditOutcome.SUCCESS)
                .previousValue(previousValue)
                .newValue(Map.of(
                        "typeName", saved.getTypeName(),
                        "description", saved.getDescription() == null ? "" : saved.getDescription(),
                        "requiresWatermark", saved.isRequiresWatermark(),
                        "coeditingEnabled", saved.isCoeditingEnabled()))
                .log();

        return documentTypeMapper.toResponse(saved);
    }

    @Override
    public DocumentTypeResponse activateDocumentType(UUID documentTypeId) {
        return setStatus(documentTypeId, DocumentType.DocumentTypeStatus.active,
                AuditActionCatalog.DOCUMENT_TYPE_ACTIVATED);
    }

    @Override
    public DocumentTypeResponse deactivateDocumentType(UUID documentTypeId) {
        return setStatus(documentTypeId, DocumentType.DocumentTypeStatus.inactive,
                AuditActionCatalog.DOCUMENT_TYPE_DEACTIVATED);
    }

    private DocumentTypeResponse setStatus(UUID documentTypeId, DocumentType.DocumentTypeStatus status, String auditAction) {
        DocumentType documentType = findOwnedByCallerTenant(documentTypeId);
        DocumentType.DocumentTypeStatus previousStatus = documentType.getStatus();
        documentType.setStatus(status);
        documentType.setUpdatedBy(TenantContext.getUserId());
        documentType.setUpdatedAt(OffsetDateTime.now());
        DocumentType saved = documentTypeRepository.save(documentType);
        log.info("Set document type {} status to {}", documentTypeId, status);

        auditService.event(auditAction)
                .objectType(AuditObjectTypes.DOCUMENT_TYPE)
                .objectId(saved.getDocumentTypeId())
                .resourceName(saved.getTypeCode())
                .objectVersion(saved.getCurrentSchemaVersion())
                .outcome(AuditOutcome.SUCCESS)
                .previousValue(Map.of("status", previousStatus))
                .newValue(Map.of("status", status))
                .log();

        return documentTypeMapper.toResponse(saved);
    }

    private DocumentType findOwnedByCallerTenant(UUID documentTypeId) {
        return documentTypeRepository
                .findByDocumentTypeIdAndTenantIdAndDeletedFalse(documentTypeId, TenantContext.getTenantId())
                .orElseThrow(() -> new ResourceNotFoundException("DocumentType", documentTypeId));
    }
}
