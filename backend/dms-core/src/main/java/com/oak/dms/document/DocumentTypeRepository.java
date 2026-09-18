package com.oak.dms.document;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface DocumentTypeRepository extends JpaRepository<DocumentType, UUID> {

    Optional<DocumentType> findByTenantIdAndDocumentTypeNameAndDeletedFalse(UUID tenantId, String documentTypeName);
}
