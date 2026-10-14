package com.oak.dms.document;


import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DocumentRepository extends JpaRepository<Document, UUID> {

    Optional<Document> findByDocumentIdAndTenantIdAndDeletedFalse(UUID documentId, UUID tenantId);


    // Document browser - every variant is tenant-scoped and skips deleted documents and templates (V69).
    Page<Document> findByTenantIdAndDeletedFalseAndTemplateFalse(UUID tenantId, Pageable pageable);

    Page<Document> findByTenantIdAndDeletedFalseAndTemplateFalseAndTitleContainingIgnoreCase(UUID tenantId,
                                                                                             String title,
                                                                                             Pageable pageable);

    Page<Document> findByTenantIdAndFolderIdAndDeletedFalseAndTemplateFalse(UUID tenantId, UUID folderId,
                                                                            Pageable pageable);

    Page<Document> findByTenantIdAndFolderIdAndDeletedFalseAndTemplateFalseAndTitleContainingIgnoreCase(
            UUID tenantId, UUID folderId, String title, Pageable pageable);

    Page<Document> findByTenantIdAndFolderIdIsNullAndDeletedFalseAndTemplateFalse(UUID tenantId, Pageable pageable);

    Page<Document> findByTenantIdAndFolderIdIsNullAndDeletedFalseAndTemplateFalseAndTitleContainingIgnoreCase(
            UUID tenantId, String title, Pageable pageable);

    // A document type's templates (V69), by name.
    List<Document> findByTenantIdAndTemplateTrueAndDeletedFalseOrderByTitleAsc(UUID tenantId);

    List<Document> findByTenantIdAndDocumentTypeIdAndTemplateTrueAndDeletedFalseOrderByTitleAsc(UUID tenantId,
                                                                                                UUID documentTypeId);

    // Access-filtered browsing (V53/V54), for callers who can't see everything. A document is
    // visible when it follows its folder and that folder isn't hidden, or it's restricted and the
    // caller is on its list. :title '' matches every title.
    String FOLLOWS_FOLDER = "d.accessMode = com.oak.dms.folder.FolderAccessMode.inherit";
    String ON_ITS_LIST = "(d.accessMode = com.oak.dms.folder.FolderAccessMode.restricted and d.documentId in :granted)";
    String LIVE_TITLED = "d.tenantId = :tenantId and d.deleted = false and d.template = false "
            + "and lower(d.title) like lower(concat('%', :title, '%')) ";

    /** "All documents": the top level and every folder the caller can see. */
    @Query("select d from Document d where " + LIVE_TITLED + "and ((" + FOLLOWS_FOLDER
            + " and (d.folderId is null or d.folderId not in :hidden)) or " + ON_ITS_LIST + ")")
    Page<Document> findVisible(@Param("tenantId") UUID tenantId, @Param("title") String title,
                               @Param("hidden") Collection<UUID> hiddenFolderIds,
                               @Param("granted") Collection<UUID> grantedDocumentIds, Pageable pageable);

    /** One folder the caller can see. */
    @Query("select d from Document d where " + LIVE_TITLED + "and d.folderId = :folderId and (" + FOLLOWS_FOLDER
            + " or " + ON_ITS_LIST + ")")
    Page<Document> findVisibleInFolder(@Param("tenantId") UUID tenantId, @Param("title") String title,
                                       @Param("folderId") UUID folderId,
                                       @Param("granted") Collection<UUID> grantedDocumentIds, Pageable pageable);

    /** The top level. */
    @Query("select d from Document d where " + LIVE_TITLED + "and d.folderId is null and (" + FOLLOWS_FOLDER
            + " or " + ON_ITS_LIST + ")")
    Page<Document> findVisibleAtTopLevel(@Param("tenantId") UUID tenantId, @Param("title") String title,
                                         @Param("granted") Collection<UUID> grantedDocumentIds, Pageable pageable);

    /**
     * "Shared with me": restricted documents whose own list names the user or
     * one of their roles - not ones they own (those are theirs, not shared).
     */
    @Query("select d from Document d where " + LIVE_TITLED
            + "and d.accessMode = com.oak.dms.folder.FolderAccessMode.restricted "
            + "and (d.ownerUserId is null or d.ownerUserId <> :userId) "
            + "and d.documentId in (select e.documentId from com.oak.dms.folder.DocumentAccessEntry e "
            + "where e.tenantId = :tenantId and ((e.principalType = com.oak.dms.folder.FolderAccessEntry.PrincipalType.user and e.principalId = :userId) "
            + "or (e.principalType = com.oak.dms.folder.FolderAccessEntry.PrincipalType.role and e.principalId in :roleIds)))")
    Page<Document> findSharedWith(@Param("tenantId") UUID tenantId, @Param("title") String title,
                                  @Param("userId") UUID userId, @Param("roleIds") Collection<UUID> roleIds,
                                  Pageable pageable);

    boolean existsByTenantIdAndAccessModeAndDeletedFalse(UUID tenantId, com.oak.dms.folder.FolderAccessMode accessMode);

    /** Restricted documents still live or in the recycle bin (not purged) - their access lists still matter. */
    @Query("select count(d) > 0 from Document d where d.tenantId = :tenantId "
            + "and d.accessMode = com.oak.dms.folder.FolderAccessMode.restricted and d.purgedAt is null")
    boolean existsRestrictedNotPurged(@Param("tenantId") UUID tenantId);

    @Query("select d.documentId from Document d where d.tenantId = :tenantId and d.deleted = false "
            + "and d.accessMode = com.oak.dms.folder.FolderAccessMode.restricted")
    List<UUID> findRestrictedIds(@Param("tenantId") UUID tenantId);

    /** [documentId, folderId] of every restricted document. */
    @Query("select d.documentId, d.folderId from Document d where d.tenantId = :tenantId and d.deleted = false "
            + "and d.accessMode = com.oak.dms.folder.FolderAccessMode.restricted")
    List<Object[]> findRestrictedIdsWithFolder(@Param("tenantId") UUID tenantId);

    /** Ids of the documents filed in any of the folders, oldest first - a folder export. */
    @Query("select d.documentId from Document d where d.tenantId = :tenantId and d.deleted = false "
            + "and d.folderId in :folderIds and d.documentId not in :hiddenDocs order by d.createdAt, d.documentId")
    List<UUID> findIdsInFolders(@Param("tenantId") UUID tenantId, @Param("folderIds") Collection<UUID> folderIds,
                                @Param("hiddenDocs") Collection<UUID> hiddenDocumentIds, Pageable limit);

    @Query("select count(d) from Document d where d.tenantId = :tenantId and d.deleted = false "
            + "and d.folderId in :folderIds and d.documentId not in :hiddenDocs")
    long countInFolders(@Param("tenantId") UUID tenantId, @Param("folderIds") Collection<UUID> folderIds,
                        @Param("hiddenDocs") Collection<UUID> hiddenDocumentIds);

    /** Chosen documents that exist in the tenant - an export by id. */
    List<Document> findAllByDocumentIdInAndTenantIdAndDeletedFalse(Collection<UUID> documentIds, UUID tenantId);

    /** The recycle bin: deleted, not yet purged (V62), most recently deleted first. */
    @Query("select d from Document d where d.tenantId = :tenantId and d.deleted = true and d.purgedAt is null "
            + "and d.template = false order by d.deletedAt desc nulls last")
    List<Document> findRecycleBin(@Param("tenantId") UUID tenantId, org.springframework.data.domain.Pageable page);

    @Query("select d from Document d where d.documentId = :documentId and d.tenantId = :tenantId "
            + "and d.deleted = true and d.purgedAt is null and d.template = false")
    java.util.Optional<Document> findInRecycleBin(@Param("documentId") UUID documentId, @Param("tenantId") UUID tenantId);

    /** Ids of one type's documents created in [from, to), outside the hidden folders, oldest first - an export by filter. */
    @Query("select d.documentId from Document d where d.tenantId = :tenantId and d.deleted = false "
            + "and d.template = false "
            + "and d.documentTypeId = :documentTypeId and d.createdAt >= :from and d.createdAt < :to "
            + "and d.documentId not in :hiddenDocs and (d.accessMode = com.oak.dms.folder.FolderAccessMode.restricted "
            + "or d.folderId is null or d.folderId not in :hidden) "
            + "order by d.createdAt, d.documentId")
    List<UUID> findIdsByTypeCreatedBetween(@Param("tenantId") UUID tenantId,
                                           @Param("documentTypeId") UUID documentTypeId,
                                           @Param("from") OffsetDateTime from,
                                           @Param("to") OffsetDateTime to,
                                           @Param("hidden") Collection<UUID> hiddenFolderIds,
                                           @Param("hiddenDocs") Collection<UUID> hiddenDocumentIds,
                                           Pageable limit);

    @Query("select count(d) from Document d where d.tenantId = :tenantId and d.deleted = false "
            + "and d.template = false "
            + "and d.documentTypeId = :documentTypeId and d.createdAt >= :from and d.createdAt < :to "
            + "and d.documentId not in :hiddenDocs and (d.accessMode = com.oak.dms.folder.FolderAccessMode.restricted "
            + "or d.folderId is null or d.folderId not in :hidden)")
    long countByTypeCreatedBetween(@Param("tenantId") UUID tenantId,
                                   @Param("documentTypeId") UUID documentTypeId,
                                   @Param("from") OffsetDateTime from,
                                   @Param("to") OffsetDateTime to,
                                   @Param("hidden") Collection<UUID> hiddenFolderIds,
                                   @Param("hiddenDocs") Collection<UUID> hiddenDocumentIds);

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
