package com.oak.dms.document;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface DocumentRepository extends JpaRepository<Document, UUID> {

    Optional<Document> findByDocumentIdAndTenantIdAndDeletedFalse(UUID documentId, UUID tenantId);

    /**
     * Row lock on the document - the first lock of the workflow lock order
     * (documents -> workflow_instances -> stage instances -> tasks). Taken by
     * workflow submission and by version-change handling, so a submit and a
     * new version of the same document serialise.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Document d where d.documentId = :id and d.tenantId = :tenantId and d.deleted = false")
    Optional<Document> lockByDocumentIdAndTenantId(@Param("id") UUID documentId, @Param("tenantId") UUID tenantId);
}
