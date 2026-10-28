package com.oak.dms.notification.repository;

import com.oak.dms.notification.entity.Notification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    /** Tenant-scoped, recipient-owned lookup; a miss (incl. cross-tenant) becomes a 404 in the service. */
    Optional<Notification> findByNotificationIdAndTenantIdAndRecipientUserId(UUID notificationId, UUID tenantId,
                                                                             UUID recipientUserId);

    boolean existsByTenantIdAndRecipientUserIdAndDedupeKey(UUID tenantId, UUID recipientUserId, String dedupeKey);

    @Query("""
            select n from Notification n
            where n.tenantId = :tenantId and n.recipientUserId = :userId and n.archivedAt is null
              and (:unreadOnly = false or n.readAt is null)
              and (:categoryCode is null or n.categoryCode = :categoryCode)
            """)
    Page<Notification> findInbox(@Param("tenantId") UUID tenantId, @Param("userId") UUID userId,
                                 @Param("unreadOnly") boolean unreadOnly,
                                 @Param("categoryCode") String categoryCode, Pageable pageable);

    @Query("""
            select count(n) from Notification n
            where n.tenantId = :tenantId and n.recipientUserId = :userId
              and n.readAt is null and n.archivedAt is null
            """)
    long countUnread(@Param("tenantId") UUID tenantId, @Param("userId") UUID userId);

    @Transactional
    @Modifying
    @Query("""
            update Notification n set n.readAt = :at
            where n.tenantId = :tenantId and n.recipientUserId = :userId and n.readAt is null and n.archivedAt is null
            """)
    int markAllRead(@Param("tenantId") UUID tenantId, @Param("userId") UUID userId, @Param("at") OffsetDateTime at);
}
