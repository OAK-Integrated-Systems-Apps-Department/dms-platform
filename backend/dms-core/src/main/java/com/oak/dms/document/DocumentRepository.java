package com.oak.dms.document;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface DocumentRepository extends JpaRepository<Document, UUID> {

    Optional<Document> findByDocumentIdAndTenantIdAndDeletedFalse(UUID documentId, UUID tenantId);
}
