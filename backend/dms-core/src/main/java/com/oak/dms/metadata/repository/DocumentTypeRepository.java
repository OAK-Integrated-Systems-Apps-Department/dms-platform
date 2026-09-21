package com.oak.dms.metadata.repository;

import com.oak.dms.metadata.entity.DocumentType;
import com.oak.dms.metadata.helper.DisplayOrderNormalizer;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DocumentTypeRepository extends JpaRepository<DocumentType, UUID> {

    Optional<DocumentType> findByDocumentTypeIdAndTenantIdAndDeletedFalse(UUID documentTypeId, UUID tenantId);

    boolean existsByTenantIdAndTypeCodeAndDeletedFalse(UUID tenantId, String typeCode);

    @Query("select t from DocumentType t where t.tenantId = :tenantId and t.deleted = false")
    Page<DocumentType> findAllByTenant(@Param("tenantId") UUID tenantId, Pageable pageable);

    /**
     * Unpaged, ordered sibling list used to re-stamp display_order across
     * the whole tenant when a document type is created or moved - see
     * {@link DisplayOrderNormalizer}.
     */
    @Query("select t from DocumentType t where t.tenantId = :tenantId and t.deleted = false order by t.displayOrder asc")
    List<DocumentType> findAllByTenantOrderedByDisplayOrder(@Param("tenantId") UUID tenantId);

    /**
     * Row-locked lookup used by the field-mapping publish flow
     * ({@code DocumentTypeFieldServiceImpl#replaceMapping}) so two
     * concurrent admins publishing a new schema version for the same
     * document type can't both compute {@code currentSchemaVersion + 1} and
     * collide - the second transaction blocks here until the first commits.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from DocumentType t where t.documentTypeId = :documentTypeId and t.tenantId = :tenantId and t.deleted = false")
    Optional<DocumentType> lockByDocumentTypeIdAndTenantId(@Param("documentTypeId") UUID documentTypeId,
                                                            @Param("tenantId") UUID tenantId);
}
