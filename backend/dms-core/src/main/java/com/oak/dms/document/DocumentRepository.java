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


    // A document type's templates (V69), by name.
    List<Document> findByTenantIdAndTemplateTrueAndDeletedFalseOrderByTitleAsc(UUID tenantId);

    List<Document> findByTenantIdAndDocumentTypeIdAndTemplateTrueAndDeletedFalseOrderByTitleAsc(UUID tenantId,
                                                                                                UUID documentTypeId);

    // Access-filtered browsing (V53/V54), for callers who can't see everything. A document is
    // visible when it follows its folder and that folder isn't hidden, it's restricted and the
    // caller is on its list, or it is the subject of a workflow the caller takes part in (Phase 2,
    // DocumentAccessService#workflowReadable). :title '' matches every title.
    String FOLLOWS_FOLDER = "d.accessMode = com.oak.dms.folder.FolderAccessMode.inherit";
    String ON_ITS_LIST = "(d.accessMode = com.oak.dms.folder.FolderAccessMode.restricted and d.documentId in :granted)";
    String IN_MY_WORKFLOW = "d.documentId in :workflowReadable";
    String LIVE_TITLED = "d.tenantId = :tenantId and d.deleted = false and d.template = false "
            + "and lower(d.title) like lower(concat('%', :title, '%')) ";
    /** The document picker's optional narrowing - both null means "no filter". */
    String NARROWED = "and (:documentTypeId is null or d.documentTypeId = :documentTypeId) "
            + "and (:excludeDocumentId is null or d.documentId <> :excludeDocumentId) "
            // Find by owner (DOT-NFR-004): ANDed with, never instead of, the access predicate that follows.
            + "and (:ownerId is null or d.ownerUserId = :ownerId) "
            + "and (:ownerDepartmentId is null or d.ownerDepartmentId = :ownerDepartmentId) ";

    /** "All documents": the top level and every folder the caller can see. */
    @Query("select d from Document d where " + LIVE_TITLED + NARROWED + "and ((" + FOLLOWS_FOLDER
            + " and (d.folderId is null or d.folderId not in :hidden)) or " + ON_ITS_LIST + " or " + IN_MY_WORKFLOW + ")")
    Page<Document> findVisible(@Param("tenantId") UUID tenantId, @Param("title") String title,
                               @Param("hidden") Collection<UUID> hiddenFolderIds,
                               @Param("granted") Collection<UUID> grantedDocumentIds,
                               @Param("workflowReadable") Collection<UUID> workflowReadableDocumentIds,
                               @Param("documentTypeId") UUID documentTypeId,
                               @Param("excludeDocumentId") UUID excludeDocumentId,
            @Param("ownerId") UUID ownerId, @Param("ownerDepartmentId") UUID ownerDepartmentId, Pageable pageable);

    /** One folder the caller can see. */
    @Query("select d from Document d where " + LIVE_TITLED + NARROWED + "and d.folderId = :folderId and ((" + FOLLOWS_FOLDER
            + ") or " + ON_ITS_LIST + " or " + IN_MY_WORKFLOW + ")")
    Page<Document> findVisibleInFolder(@Param("tenantId") UUID tenantId, @Param("title") String title,
                                       @Param("folderId") UUID folderId,
                                       @Param("granted") Collection<UUID> grantedDocumentIds,
                                       @Param("workflowReadable") Collection<UUID> workflowReadableDocumentIds,
                                       @Param("documentTypeId") UUID documentTypeId,
                                       @Param("excludeDocumentId") UUID excludeDocumentId,
            @Param("ownerId") UUID ownerId, @Param("ownerDepartmentId") UUID ownerDepartmentId, Pageable pageable);

    /** The top level. */
    @Query("select d from Document d where " + LIVE_TITLED + NARROWED + "and d.folderId is null and ((" + FOLLOWS_FOLDER
            + ") or " + ON_ITS_LIST + " or " + IN_MY_WORKFLOW + ")")
    Page<Document> findVisibleAtTopLevel(@Param("tenantId") UUID tenantId, @Param("title") String title,
                                         @Param("granted") Collection<UUID> grantedDocumentIds,
                                         @Param("workflowReadable") Collection<UUID> workflowReadableDocumentIds,
                                         @Param("documentTypeId") UUID documentTypeId,
                                         @Param("excludeDocumentId") UUID excludeDocumentId,
            @Param("ownerId") UUID ownerId, @Param("ownerDepartmentId") UUID ownerDepartmentId, Pageable pageable);

    // The same three listings for a caller who sees everything (an admin, or a tenant with nothing
    // restricted): no access predicate to evaluate, only the browser's own filters.

    /** "All documents" for a caller who sees everything. */
    @Query("select d from Document d where " + LIVE_TITLED + NARROWED)
    Page<Document> findLive(@Param("tenantId") UUID tenantId, @Param("title") String title,
                            @Param("documentTypeId") UUID documentTypeId,
                            @Param("excludeDocumentId") UUID excludeDocumentId,
            @Param("ownerId") UUID ownerId, @Param("ownerDepartmentId") UUID ownerDepartmentId, Pageable pageable);

    @Query("select d from Document d where " + LIVE_TITLED + NARROWED + "and d.folderId = :folderId")
    Page<Document> findLiveInFolder(@Param("tenantId") UUID tenantId, @Param("title") String title,
                                    @Param("folderId") UUID folderId,
                                    @Param("documentTypeId") UUID documentTypeId,
                                    @Param("excludeDocumentId") UUID excludeDocumentId,
            @Param("ownerId") UUID ownerId, @Param("ownerDepartmentId") UUID ownerDepartmentId, Pageable pageable);

    @Query("select d from Document d where " + LIVE_TITLED + NARROWED + "and d.folderId is null")
    Page<Document> findLiveAtTopLevel(@Param("tenantId") UUID tenantId, @Param("title") String title,
                                      @Param("documentTypeId") UUID documentTypeId,
                                      @Param("excludeDocumentId") UUID excludeDocumentId,
            @Param("ownerId") UUID ownerId, @Param("ownerDepartmentId") UUID ownerDepartmentId, Pageable pageable);

    // Browsing by relationship (2.8): the same visibility rules as above, over every folder, with the
    // document required to have an active link of the type (any type if :relTypeId is null) to or from
    // :relatedTo (any document if null). :asSource keeps documents that are the source of such a link,
    // :asTarget those that are its target. The access predicate is ANDed, never replaced.
    String RELATED = "and exists (select 1 from com.oak.dms.relationship.entity.DocumentRelationship r "
            + "where r.tenantId = :tenantId and r.removedAt is null "
            + "and (:relTypeId is null or r.relationshipTypeId = :relTypeId) "
            + "and ((:asSource = true and r.sourceDocumentId = d.documentId "
            + "and (:relatedTo is null or r.targetDocumentId = :relatedTo)) "
            + "or (:asTarget = true and r.targetDocumentId = d.documentId "
            + "and (:relatedTo is null or r.sourceDocumentId = :relatedTo)))) ";

    @Query("select d from Document d where " + LIVE_TITLED + NARROWED + RELATED + "and ((" + FOLLOWS_FOLDER
            + " and (d.folderId is null or d.folderId not in :hidden)) or " + ON_ITS_LIST + " or " + IN_MY_WORKFLOW + ")")
    Page<Document> findVisibleRelated(@Param("tenantId") UUID tenantId, @Param("title") String title,
                                      @Param("hidden") Collection<UUID> hiddenFolderIds,
                                      @Param("granted") Collection<UUID> grantedDocumentIds,
                                      @Param("workflowReadable") Collection<UUID> workflowReadableDocumentIds,
                                      @Param("documentTypeId") UUID documentTypeId,
                                      @Param("excludeDocumentId") UUID excludeDocumentId,
            @Param("ownerId") UUID ownerId, @Param("ownerDepartmentId") UUID ownerDepartmentId,
                                      @Param("relTypeId") UUID relationshipTypeId, @Param("relatedTo") UUID relatedTo,
                                      @Param("asSource") boolean asSource, @Param("asTarget") boolean asTarget,
                                      Pageable pageable);

    /** {@link #findVisibleRelated} for a caller who sees everything. */
    @Query("select d from Document d where " + LIVE_TITLED + NARROWED + RELATED)
    Page<Document> findLiveRelated(@Param("tenantId") UUID tenantId, @Param("title") String title,
                                   @Param("documentTypeId") UUID documentTypeId,
                                   @Param("excludeDocumentId") UUID excludeDocumentId,
            @Param("ownerId") UUID ownerId, @Param("ownerDepartmentId") UUID ownerDepartmentId,
                                   @Param("relTypeId") UUID relationshipTypeId, @Param("relatedTo") UUID relatedTo,
                                   @Param("asSource") boolean asSource, @Param("asTarget") boolean asTarget,
                                   Pageable pageable);

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
     * Row lock on the document - the first lock of the workflow lock order
     * (documents -> workflow_instances -> stage instances -> tasks). Taken by
     * workflow submission and by version-change handling, so a submit and a
     * new version of the same document serialise.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Document d where d.documentId = :id and d.tenantId = :tenantId and d.deleted = false")
    Optional<Document> lockByDocumentIdAndTenantId(@Param("id") UUID documentId, @Param("tenantId") UUID tenantId);
}
