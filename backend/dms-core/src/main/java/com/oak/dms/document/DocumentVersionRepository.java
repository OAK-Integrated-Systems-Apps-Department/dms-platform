package com.oak.dms.document;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface DocumentVersionRepository extends JpaRepository<DocumentVersion, UUID> {

    Optional<DocumentVersion> findByVersionIdAndDocumentIdAndTenantIdAndDeletedFalse(
            UUID versionId, UUID documentId, UUID tenantId);

    Optional<DocumentVersion> findTopByDocumentIdOrderByVersionNumberDesc(UUID documentId);
}
