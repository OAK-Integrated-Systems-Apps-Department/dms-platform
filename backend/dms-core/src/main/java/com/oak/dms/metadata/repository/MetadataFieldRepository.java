package com.oak.dms.metadata.repository;

import com.oak.dms.metadata.entity.MetadataField;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MetadataFieldRepository extends JpaRepository<MetadataField, UUID> {

    Optional<MetadataField> findByFieldIdAndTenantIdAndDeletedFalse(UUID fieldId, UUID tenantId);

    boolean existsByTenantIdAndFieldKeyAndDeletedFalse(UUID tenantId, String fieldKey);

    List<MetadataField> findAllByFieldIdInAndTenantIdAndDeletedFalse(List<UUID> fieldIds, UUID tenantId);

    @Query("""
            select f from MetadataField f
            where f.tenantId = :tenantId and f.deleted = false
              and (:status is null or f.status = :status)
              and (:dataType is null or f.dataType = :dataType)
              and (:search is null
                   or lower(f.fieldKey) like :search
                   or lower(f.label) like :search)
            """)
    Page<MetadataField> search(@Param("tenantId") UUID tenantId,
                                @Param("status") MetadataField.FieldStatus status,
                                @Param("dataType") String dataType,
                                @Param("search") String search,
                                Pageable pageable);
}
