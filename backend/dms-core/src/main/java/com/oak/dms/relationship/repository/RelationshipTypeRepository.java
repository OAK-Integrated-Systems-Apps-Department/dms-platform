package com.oak.dms.relationship.repository;

import com.oak.dms.relationship.entity.RelationshipType;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RelationshipTypeRepository extends JpaRepository<RelationshipType, UUID> {

    Optional<RelationshipType> findByRelationshipTypeIdAndTenantIdAndDeletedFalse(UUID relationshipTypeId, UUID tenantId);

    Optional<RelationshipType> findByTenantIdAndCodeAndDeletedFalse(UUID tenantId, String code);

    List<RelationshipType> findByTenantIdAndDeletedFalseOrderByDisplayOrderAscLabelAsc(UUID tenantId);

    List<RelationshipType> findByTenantIdAndEnabledTrueAndDeletedFalseOrderByDisplayOrderAscLabelAsc(UUID tenantId);

    /** Types by id, deleted ones included - an old link may point at a type that has since been deleted. */
    List<RelationshipType> findByTenantIdAndRelationshipTypeIdIn(UUID tenantId, Collection<UUID> ids);

    boolean existsByTenantIdAndCodeAndDeletedFalse(UUID tenantId, String code);

    @Query(value = "select count(*) from relationship_types where tenant_id = :tenantId and is_system and not is_deleted",
            nativeQuery = true)
    long countSystemTypes(@Param("tenantId") UUID tenantId);

    /** Row lock for changing or deleting a type, so a link can't be created on it while it goes. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from RelationshipType t where t.relationshipTypeId = :id and t.tenantId = :tenantId and t.deleted = false")
    Optional<RelationshipType> lockActive(@Param("id") UUID id, @Param("tenantId") UUID tenantId);

    /** Shared lock taken by a link being created: waits for a type change in flight, blocks a delete until it commits. */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select t from RelationshipType t where t.relationshipTypeId = :id and t.tenantId = :tenantId and t.deleted = false")
    Optional<RelationshipType> shareActive(@Param("id") UUID id, @Param("tenantId") UUID tenantId);
}
