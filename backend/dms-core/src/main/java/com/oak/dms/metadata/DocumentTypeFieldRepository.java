package com.oak.dms.metadata;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface DocumentTypeFieldRepository extends JpaRepository<DocumentTypeField, UUID> {

    @Query("""
            select m from DocumentTypeField m
            where m.documentTypeId = :documentTypeId and m.tenantId = :tenantId
              and m.schemaVersion = :schemaVersion and m.deleted = false
            order by m.displayOrder asc
            """)
    List<DocumentTypeField> findByDocumentTypeAndVersion(@Param("documentTypeId") UUID documentTypeId,
                                                           @Param("tenantId") UUID tenantId,
                                                           @Param("schemaVersion") int schemaVersion);

    @Query("""
            select count(m) > 0 from DocumentTypeField m
            where m.fieldId = :fieldId and m.tenantId = :tenantId and m.deleted = false
            """)
    boolean existsByField(@Param("fieldId") UUID fieldId, @Param("tenantId") UUID tenantId);

    @Query("""
            select count(m) > 0 from DocumentTypeField m
            where m.documentTypeId = :documentTypeId and m.fieldId = :fieldId and m.tenantId = :tenantId
              and m.schemaVersion = :schemaVersion and m.status = :status and m.deleted = false
            """)
    boolean existsByDocumentTypeFieldVersionAndStatus(@Param("documentTypeId") UUID documentTypeId,
                                                      @Param("fieldId") UUID fieldId,
                                                      @Param("tenantId") UUID tenantId,
                                                      @Param("schemaVersion") int schemaVersion,
                                                      @Param("status") DocumentTypeField.MappingStatus status);

    /**
     * Whether {@code fieldId} is part of the given (normally the current)
     * published schema version of the document type - the precondition for
     * editing that field's type-scoped OCR label hints.
     */
    default boolean existsActiveMapping(UUID documentTypeId, UUID fieldId, UUID tenantId, int schemaVersion) {
        return existsByDocumentTypeFieldVersionAndStatus(documentTypeId, fieldId, tenantId, schemaVersion,
                DocumentTypeField.MappingStatus.active);
    }

    @Query("""
            select count(distinct m.documentTypeId) from DocumentTypeField m
            where m.fieldId = :fieldId and m.tenantId = :tenantId and m.deleted = false
            """)
    long countDistinctDocumentTypesByField(@Param("fieldId") UUID fieldId, @Param("tenantId") UUID tenantId);
}
