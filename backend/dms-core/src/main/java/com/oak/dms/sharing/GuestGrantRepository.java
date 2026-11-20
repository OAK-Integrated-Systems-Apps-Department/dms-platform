package com.oak.dms.sharing;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GuestGrantRepository extends JpaRepository<GuestGrant, UUID> {

    Optional<GuestGrant> findByGrantIdAndTenantId(UUID grantId, UUID tenantId);

    List<GuestGrant> findByGuestIdAndRevokedAtIsNull(UUID guestId);

    List<GuestGrant> findByTenantIdAndDocumentIdAndRevokedAtIsNullOrderByCreatedAtDesc(UUID tenantId, UUID documentId);

    List<GuestGrant> findByTenantIdAndFolderIdAndRevokedAtIsNullOrderByCreatedAtDesc(UUID tenantId, UUID folderId);

    long countByGuestIdAndRevokedAtIsNull(UUID guestId);

    @Query("select g from GuestGrant g where g.tenantId = :tenantId and g.revokedAt is null and g.documentId in :ids")
    List<GuestGrant> findLiveByDocuments(@Param("tenantId") UUID tenantId, @Param("ids") Collection<UUID> ids);

    @Query("select g from GuestGrant g where g.tenantId = :tenantId and g.revokedAt is null and g.folderId in :ids")
    List<GuestGrant> findLiveByFolders(@Param("tenantId") UUID tenantId, @Param("ids") Collection<UUID> ids);
}
