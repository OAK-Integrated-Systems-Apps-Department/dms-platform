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

    /** The folder and every folder below it (live ones), for move-cycle checks and subtree exports. */
    @Query(value = """
            with recursive tree as (
                select folder_id from folders
                where folder_id = :folderId and tenant_id = :tenantId and not is_deleted
                union all
                select f.folder_id from folders f join tree t on f.parent_folder_id = t.folder_id
                where f.tenant_id = :tenantId and not f.is_deleted
            )
            select folder_id from tree
            """, nativeQuery = true)
    List<UUID> findSubtreeIds(@Param("tenantId") UUID tenantId, @Param("folderId") UUID folderId);

    /** Live documents filed in the folder. */
    @Query(value = "select count(*) from documents where tenant_id = :tenantId and folder_id = :folderId "
            + "and not is_deleted", nativeQuery = true)
    long countDocuments(@Param("tenantId") UUID tenantId, @Param("folderId") UUID folderId);

    /** Uploads still being scanned that will land in the folder. */
    @Query(value = "select count(*) from scan_results where tenant_id = :tenantId and folder_id = :folderId "
            + "and status in ('pending', 'infected') and not is_deleted", nativeQuery = true)
    long countPendingUploads(@Param("tenantId") UUID tenantId, @Param("folderId") UUID folderId);

    /** Live document counts per folder, as [folderId, count] rows. */
    @Query(value = "select folder_id, count(*) from documents where tenant_id = :tenantId and folder_id is not null "
            + "and not is_deleted group by folder_id", nativeQuery = true)
    List<Object[]> countDocumentsPerFolder(@Param("tenantId") UUID tenantId);
}
