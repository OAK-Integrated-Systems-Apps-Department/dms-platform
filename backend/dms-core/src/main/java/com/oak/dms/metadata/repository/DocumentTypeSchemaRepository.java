package com.oak.dms.metadata.repository;

import com.oak.dms.metadata.entity.DocumentTypeSchema;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface DocumentTypeSchemaRepository extends JpaRepository<DocumentTypeSchema, UUID> {

    Optional<DocumentTypeSchema> findByDocumentTypeIdAndTenantIdAndSchemaVersion(UUID documentTypeId, UUID tenantId,
                                                                                  int schemaVersion);

    /**
     * Whether any version (active or archived) of any document type in the
     * tenant maps the field - a JSONB containment match served by the
     * {@code ix_dts_fields} GIN index.
     */
    @Query(value = """
            select exists (
                select 1 from document_type_schemas
                where tenant_id = :tenantId
                  and fields @> jsonb_build_array(jsonb_build_object('fieldId', cast(:fieldId as text))))
            """, nativeQuery = true)
    boolean existsByField(@Param("fieldId") UUID fieldId, @Param("tenantId") UUID tenantId);

    /** How many document types map the field in any version, active or archived. */
    @Query(value = """
            select count(distinct document_type_id) from document_type_schemas
            where tenant_id = :tenantId
              and fields @> jsonb_build_array(jsonb_build_object('fieldId', cast(:fieldId as text)))
            """, nativeQuery = true)
    long countDistinctDocumentTypesByField(@Param("fieldId") UUID fieldId, @Param("tenantId") UUID tenantId);
}
