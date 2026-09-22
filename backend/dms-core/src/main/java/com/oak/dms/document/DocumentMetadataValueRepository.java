package com.oak.dms.document;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface DocumentMetadataValueRepository extends JpaRepository<DocumentMetadataValue, UUID> {

    List<DocumentMetadataValue> findByDocumentIdAndTenantIdAndDeletedFalse(UUID documentId, UUID tenantId);

    List<DocumentMetadataValue> findByDocumentIdAndFieldIdAndTenantIdAndDeletedFalse(UUID documentId, UUID fieldId, UUID tenantId);

    /**
     * A real {@code DELETE ... WHERE} executed immediately, not a
     * derived delete-by-query (which loads matching entities and marks
     * them for removal at flush time) -- Hibernate's default flush order
     * runs all pending INSERTs before DELETEs, so re-saving a field in the
     * same transaction as this delete would otherwise hit
     * uq_document_metadata_values_doc_field_ordinal before the old rows
     * were actually gone.
     */
    @Modifying
    @Query("delete from DocumentMetadataValue v where v.documentId = :documentId and v.fieldId = :fieldId and v.tenantId = :tenantId")
    void deleteByDocumentIdAndFieldIdAndTenantId(@Param("documentId") UUID documentId, @Param("fieldId") UUID fieldId, @Param("tenantId") UUID tenantId);
}
