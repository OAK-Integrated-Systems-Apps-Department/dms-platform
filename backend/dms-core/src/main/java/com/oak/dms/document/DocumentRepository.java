package com.oak.dms.document;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DocumentRepository extends JpaRepository<Document, UUID> {

    Optional<Document> findByDocumentIdAndTenantIdAndDeletedFalse(UUID documentId, UUID tenantId);

    /** Chosen documents that exist in the tenant - an export by id. */
    List<Document> findAllByDocumentIdInAndTenantIdAndDeletedFalse(Collection<UUID> documentIds, UUID tenantId);

    /** Ids of one type's documents created in [from, to), oldest first - an export by filter. */
    @Query("select d.documentId from Document d where d.tenantId = :tenantId and d.deleted = false "
            + "and d.documentTypeId = :documentTypeId and d.createdAt >= :from and d.createdAt < :to "
            + "order by d.createdAt, d.documentId")
    List<UUID> findIdsByTypeCreatedBetween(@Param("tenantId") UUID tenantId,
                                           @Param("documentTypeId") UUID documentTypeId,
                                           @Param("from") OffsetDateTime from,
                                           @Param("to") OffsetDateTime to,
                                           Pageable limit);

    @Query("select count(d) from Document d where d.tenantId = :tenantId and d.deleted = false "
            + "and d.documentTypeId = :documentTypeId and d.createdAt >= :from and d.createdAt < :to")
    long countByTypeCreatedBetween(@Param("tenantId") UUID tenantId,
                                   @Param("documentTypeId") UUID documentTypeId,
                                   @Param("from") OffsetDateTime from,
                                   @Param("to") OffsetDateTime to);
}
