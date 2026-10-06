package com.oak.dms.document;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface DocumentRepository extends JpaRepository<Document, UUID> {

    Optional<Document> findByDocumentIdAndTenantIdAndDeletedFalse(UUID documentId, UUID tenantId);

    /**
     * The tenant's live documents, newest first. search is a lower-cased
     * {@code %term%} pattern matched against the title (null = no filter);
     * documentTypeId and excludeDocumentId are optional.
     */
    @Query(value = """
            select d from Document d
            where d.tenantId = :tenantId and d.deleted = false
              and (:search is null or lower(d.title) like :search)
              and (:documentTypeId is null or d.documentTypeId = :documentTypeId)
              and (:excludeDocumentId is null or d.documentId <> :excludeDocumentId)
            order by d.createdAt desc, d.documentId
            """,
            countQuery = """
            select count(d) from Document d
            where d.tenantId = :tenantId and d.deleted = false
              and (:search is null or lower(d.title) like :search)
              and (:documentTypeId is null or d.documentTypeId = :documentTypeId)
              and (:excludeDocumentId is null or d.documentId <> :excludeDocumentId)
            """)
    Page<Document> search(@Param("tenantId") UUID tenantId, @Param("search") String search,
                          @Param("documentTypeId") UUID documentTypeId,
                          @Param("excludeDocumentId") UUID excludeDocumentId, Pageable pageable);

    /**
     * Row lock on the document - the first lock of the workflow lock order
     * (documents -> workflow_instances -> stage instances -> tasks). Taken by
     * workflow submission and by version-change handling, so a submit and a
     * new version of the same document serialise.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Document d where d.documentId = :id and d.tenantId = :tenantId and d.deleted = false")
    Optional<Document> lockByDocumentIdAndTenantId(@Param("id") UUID documentId, @Param("tenantId") UUID tenantId);
}
