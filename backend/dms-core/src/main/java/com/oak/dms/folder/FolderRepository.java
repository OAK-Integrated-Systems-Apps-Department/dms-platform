package com.oak.dms.folder;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FolderRepository extends JpaRepository<Folder, UUID> {

    Optional<Folder> findByFolderIdAndTenantIdAndDeletedFalse(UUID folderId, UUID tenantId);

    /** The whole live tree of a tenant - small enough to hand to the browser in one go. */
    List<Folder> findByTenantIdAndDeletedFalseOrderByFolderNameAsc(UUID tenantId);

    List<Folder> findByTenantIdAndParentFolderIdAndDeletedFalse(UUID tenantId, UUID parentFolderId);

    /** "Shared with me": restricted folders whose list names the user or one of their roles, not ones they created. */
    @Query("select f from Folder f where f.tenantId = :tenantId and f.deleted = false "
            + "and f.accessMode = com.oak.dms.folder.FolderAccessMode.restricted "
            + "and (f.createdBy is null or f.createdBy <> :userId) "
            + "and f.folderId in (select e.folderId from FolderAccessEntry e "
            + "where e.tenantId = :tenantId and ((e.principalType = com.oak.dms.folder.FolderAccessEntry.PrincipalType.user and e.principalId = :userId) "
            + "or (e.principalType = com.oak.dms.folder.FolderAccessEntry.PrincipalType.role and e.principalId in :roleIds))) order by f.folderName")
    List<Folder> findSharedWith(@Param("tenantId") UUID tenantId, @Param("userId") UUID userId,
                                @Param("roleIds") java.util.Collection<UUID> roleIds);

    List<Folder> findByTenantIdAndParentFolderIdIsNullAndDeletedFalse(UUID tenantId);

    boolean existsByTenantIdAndParentFolderIdAndDeletedFalse(UUID tenantId, UUID parentFolderId);

    /**
     * The folder and every folder below it (live ones), for move-cycle checks and subtree exports.
     * <p>
     * {@code UNION} (not {@code UNION ALL}) on purpose (DOC-14): the recursive step carries only the folder id, so a row
     * already in the result is discarded instead of being expanded again. On a valid tree that changes nothing; if the
     * data ever holds a cycle (the move race that DOC-14 closed could create one) the recursion still ends, with every
     * folder of the cycle listed once. Do not add a depth or path column here without also adding a cycle guard.
     */
    @Query(value = """
            with recursive tree as (
                select folder_id from folders
                where folder_id = :folderId and tenant_id = :tenantId and not is_deleted
                union
                select f.folder_id from folders f join tree t on f.parent_folder_id = t.folder_id
                where f.tenant_id = :tenantId and not f.is_deleted
            )
            select folder_id from tree
            """, nativeQuery = true)
    List<UUID> findSubtreeIds(@Param("tenantId") UUID tenantId, @Param("folderId") UUID folderId);

    /**
     * Takes PostgreSQL's transaction-scoped advisory lock for {@code key} (DOC-14), waiting if another transaction
     * holds it, and returns 1. The lock belongs to the <b>current transaction's connection</b> and is released by the
     * commit or rollback - there is nothing to unlock. It must therefore be called inside the transaction that then
     * validates and writes (see {@code FolderService#lockTree}); called outside one it would be released at once.
     */
    @Query(value = "select count(*) from (select pg_advisory_xact_lock(hashtextextended(:key, 0)) as locked) l",
            nativeQuery = true)
    long lockHierarchy(@Param("key") String key);

    /** Live documents filed in the folder. */
    @Query(value = "select count(*) from documents where tenant_id = :tenantId and folder_id = :folderId "
            + "and not is_deleted", nativeQuery = true)
    long countDocuments(@Param("tenantId") UUID tenantId, @Param("folderId") UUID folderId);

    /** Uploads still being scanned that will land in the folder. */
    @Query(value = "select count(*) from scan_results where tenant_id = :tenantId and folder_id = :folderId "
            + "and status in ('pending', 'infected') and not is_deleted", nativeQuery = true)
    long countPendingUploads(@Param("tenantId") UUID tenantId, @Param("folderId") UUID folderId);

    /** Active documents filed in the folder - what the folder shows (inactive and archived ones are hidden from its listing). */
    @Query(value = "select count(*) from documents where tenant_id = :tenantId and folder_id = :folderId "
            + "and not is_deleted and lifecycle_state = 'active'", nativeQuery = true)
    long countActiveDocuments(@Param("tenantId") UUID tenantId, @Param("folderId") UUID folderId);

    /**
     * Active document counts per folder, as [folderId, count] rows - the number on each folder tile, matching what opening
     * the folder lists (6.1: inactive and archived documents are hidden from the listing, so they are not counted either).
     */
    @Query(value = "select folder_id, count(*) from documents where tenant_id = :tenantId and folder_id is not null "
            + "and not is_deleted and lifecycle_state = 'active' group by folder_id", nativeQuery = true)
    List<Object[]> countDocumentsPerFolder(@Param("tenantId") UUID tenantId);
}
