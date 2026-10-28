package com.oak.dms.folder;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface DocumentAccessRepository extends JpaRepository<DocumentAccessEntry, UUID> {

    List<DocumentAccessEntry> findByTenantIdAndDocumentIdOrderByCreatedAtAsc(UUID tenantId, UUID documentId);

    /** Every entry naming this user or one of these roles - which restricted documents the caller can open. */
    @Query("""
            select e from DocumentAccessEntry e
            where e.tenantId = :tenantId
              and ((e.principalType = com.oak.dms.folder.FolderAccessEntry.PrincipalType.user and e.principalId = :userId)
                or (e.principalType = com.oak.dms.folder.FolderAccessEntry.PrincipalType.role and e.principalId in :roleIds))
            """)
    List<DocumentAccessEntry> findForPrincipals(@Param("tenantId") UUID tenantId, @Param("userId") UUID userId,
                                                @Param("roleIds") Collection<UUID> roleIds);

    @Modifying
    @Query("delete from DocumentAccessEntry e where e.tenantId = :tenantId and e.documentId = :documentId")
    void deleteByDocument(@Param("tenantId") UUID tenantId, @Param("documentId") UUID documentId);
}
