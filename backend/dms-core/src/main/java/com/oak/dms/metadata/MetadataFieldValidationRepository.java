package com.oak.dms.metadata;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MetadataFieldValidationRepository extends JpaRepository<MetadataFieldValidation, UUID> {

    Optional<MetadataFieldValidation> findByValidationIdAndTenantIdAndDeletedFalse(UUID validationId, UUID tenantId);

    @Query("""
            select v from MetadataFieldValidation v
            where v.fieldId = :fieldId and v.tenantId = :tenantId and v.deleted = false
            """)
    List<MetadataFieldValidation> findAllByField(@Param("fieldId") UUID fieldId, @Param("tenantId") UUID tenantId);

    @Query("""
            select v from MetadataFieldValidation v
            where v.fieldId in :fieldIds and v.tenantId = :tenantId and v.deleted = false
            """)
    List<MetadataFieldValidation> findAllByFieldIn(@Param("fieldIds") List<UUID> fieldIds, @Param("tenantId") UUID tenantId);
}
