package com.oak.dms.folder;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface FolderAccessRepository extends JpaRepository<FolderAccessEntry, UUID> {

    List<FolderAccessEntry> findByTenantIdAndFolderIdOrderByCreatedAtAsc(UUID tenantId, UUID folderId);

    /** Every entry naming this user or one of these roles - what the caller can get into. */
    @Query("""
            select e from FolderAccessEntry e
            where e.tenantId = :tenantId
              and ((e.principalType = com.oak.dms.folder.FolderAccessEntry.PrincipalType.user and e.principalId = :userId)
                or (e.principalType = com.oak.dms.folder.FolderAccessEntry.PrincipalType.role and e.principalId in :roleIds))
            """)
    List<FolderAccessEntry> findForPrincipals(@Param("tenantId") UUID tenantId, @Param("userId") UUID userId,
                                              @Param("roleIds") Collection<UUID> roleIds);

    @Modifying
    @Query("delete from FolderAccessEntry e where e.tenantId = :tenantId and e.folderId = :folderId")
    void deleteByFolder(@Param("tenantId") UUID tenantId, @Param("folderId") UUID folderId);
}
