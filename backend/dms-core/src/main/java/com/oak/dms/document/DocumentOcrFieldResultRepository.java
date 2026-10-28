package com.oak.dms.document;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** Read-only from dms-core's side -- see {@link DocumentOcrFieldResult}. */
public interface DocumentOcrFieldResultRepository extends JpaRepository<DocumentOcrFieldResult, UUID> {

    List<DocumentOcrFieldResult> findByDocumentIdAndTenantId(UUID documentId, UUID tenantId);
}
