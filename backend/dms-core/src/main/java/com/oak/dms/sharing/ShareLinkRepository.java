package com.oak.dms.sharing;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ShareLinkRepository extends JpaRepository<ShareLink, UUID> {

    Optional<ShareLink> findByTokenHash(String tokenHash);

    /** Row-locked, so concurrent opens count views and wrong passwords one at a time (the limits can't be raced). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from ShareLink l where l.tokenHash = :hash")
    Optional<ShareLink> lockByTokenHash(@Param("hash") String tokenHash);

    Optional<ShareLink> findByLinkIdAndTenantId(UUID linkId, UUID tenantId);

    List<ShareLink> findByTenantIdAndDocumentIdOrderByCreatedAtDesc(UUID tenantId, UUID documentId);

    List<ShareLink> findByTenantIdAndFolderIdOrderByCreatedAtDesc(UUID tenantId, UUID folderId);

    /** Every link in the organisation, newest first, narrowed by status ("all" for none). */
    @Query("""
            select l from ShareLink l
            where l.tenantId = :tenantId
              and (:status = 'all'
                or (:status = 'review' and l.needsReview = true and l.revokedAt is null)
                or (:status = 'revoked' and l.revokedAt is not null)
                or (:status = 'expired' and l.revokedAt is null and l.expiresAt <= :now)
                or (:status = 'active' and l.revokedAt is null and l.expiresAt > :now
                    and (l.maxViews is null or l.viewCount < l.maxViews)))
            order by l.createdAt desc
            """)
    Page<ShareLink> findForTenant(@Param("tenantId") UUID tenantId, @Param("status") String status,
                                  @Param("now") OffsetDateTime now, Pageable pageable);

    /** Links not yet revoked that point at one of these documents - the ones a reclassification must deal with. */
    @Query("select l from ShareLink l where l.tenantId = :tenantId and l.revokedAt is null "
            + "and l.documentId in :documentIds")
    List<ShareLink> findLiveByDocuments(@Param("tenantId") UUID tenantId,
                                        @Param("documentIds") Collection<UUID> documentIds);

    @Query("select l from ShareLink l where l.tenantId = :tenantId and l.revokedAt is null "
            + "and l.folderId in :folderIds")
    List<ShareLink> findLiveByFolders(@Param("tenantId") UUID tenantId,
                                      @Param("folderIds") Collection<UUID> folderIds);
}
