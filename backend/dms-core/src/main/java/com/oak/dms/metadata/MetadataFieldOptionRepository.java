package com.oak.dms.metadata;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MetadataFieldOptionRepository extends JpaRepository<MetadataFieldOption, UUID> {

    Optional<MetadataFieldOption> findByOptionIdAndTenantIdAndDeletedFalse(UUID optionId, UUID tenantId);

    @Query("""
            select o from MetadataFieldOption o
            where o.fieldId = :fieldId and o.tenantId = :tenantId and o.deleted = false
            order by o.displayOrder asc
            """)
    List<MetadataFieldOption> findAllByFieldOrdered(@Param("fieldId") UUID fieldId, @Param("tenantId") UUID tenantId);

    @Query("""
            select o from MetadataFieldOption o
            where o.fieldId = :fieldId and o.tenantId = :tenantId and o.deleted = false
              and o.status = :status
            order by o.displayOrder asc
            """)
    List<MetadataFieldOption> findAllByFieldAndStatusOrdered(@Param("fieldId") UUID fieldId, @Param("tenantId") UUID tenantId,
                                                              @Param("status") MetadataFieldOption.OptionStatus status);

    default List<MetadataFieldOption> findAllActiveByFieldOrdered(UUID fieldId, UUID tenantId) {
        return findAllByFieldAndStatusOrdered(fieldId, tenantId, MetadataFieldOption.OptionStatus.active);
    }

    @Query("""
            select count(o) > 0 from MetadataFieldOption o
            where o.fieldId = :fieldId and o.tenantId = :tenantId
              and o.status = :status and o.deleted = false
            """)
    boolean existsActiveByField(@Param("fieldId") UUID fieldId, @Param("tenantId") UUID tenantId,
                                 @Param("status") MetadataFieldOption.OptionStatus status);
}
