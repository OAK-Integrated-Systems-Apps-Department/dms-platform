package com.oak.dms.relationship.repository;

import com.oak.dms.relationship.entity.DocumentRelationship;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DocumentRelationshipRepository extends JpaRepository<DocumentRelationship, UUID> {

    Optional<DocumentRelationship> findByRelationshipIdAndTenantIdAndRemovedAtIsNull(UUID relationshipId, UUID tenantId);

    /**
     * Every active link touching a document, newest first. Two index scans (source side, target
     * side) rather than an OR, so the planner uses ix_document_relationships_source/_target.
     */
    @Query(value = """
            select * from (
              select r.* from document_relationships r
              where r.tenant_id = :tenantId and r.source_document_id = :documentId and r.removed_at is null
              union all
              select r.* from document_relationships r
              where r.tenant_id = :tenantId and r.target_document_id = :documentId and r.removed_at is null
            ) x order by x.created_at desc, x.relationship_id
            """, nativeQuery = true)
    List<DocumentRelationship> findActiveTouching(@Param("tenantId") UUID tenantId, @Param("documentId") UUID documentId);

    boolean existsByTenantIdAndRelationshipTypeIdAndSourceDocumentIdAndTargetDocumentIdAndRemovedAtIsNull(
            UUID tenantId, UUID relationshipTypeId, UUID sourceDocumentId, UUID targetDocumentId);

    @Query("select count(r) from DocumentRelationship r where r.tenantId = :tenantId and r.relationshipTypeId = :typeId")
    long countAllByType(@Param("tenantId") UUID tenantId, @Param("typeId") UUID typeId);

    @Query("select r.relationshipTypeId, count(r) from DocumentRelationship r"
            + " where r.tenantId = :tenantId and r.removedAt is null group by r.relationshipTypeId")
    List<Object[]> activeCountsPerType(@Param("tenantId") UUID tenantId);

    @Query(value = """
            select d.document_id, count(*) from (
              select source_document_id as document_id from document_relationships
              where tenant_id = :tenantId and removed_at is null and source_document_id in (:ids)
              union all
              select target_document_id from document_relationships
              where tenant_id = :tenantId and removed_at is null and target_document_id in (:ids)
            ) d group by d.document_id
            """, nativeQuery = true)
    List<Object[]> activeCountsPerDocument(@Param("tenantId") UUID tenantId, @Param("ids") Collection<UUID> ids);

    /** The active link under a row lock - two people editing or removing the same link serialise. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from DocumentRelationship r where r.relationshipId = :id and r.tenantId = :tenantId and r.removedAt is null")
    Optional<DocumentRelationship> lockActive(@Param("id") UUID id, @Param("tenantId") UUID tenantId);

    List<DocumentRelationship> findByTenantIdAndRelationshipIdIn(UUID tenantId, Collection<UUID> ids);
}
