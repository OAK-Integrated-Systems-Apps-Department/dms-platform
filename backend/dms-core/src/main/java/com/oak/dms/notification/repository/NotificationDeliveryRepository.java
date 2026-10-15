package com.oak.dms.notification.repository;

import com.oak.dms.notification.entity.NotificationDelivery;
import com.oak.dms.notification.enums.DeliveryStatus;
import com.oak.dms.notification.enums.NotificationChannel;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface NotificationDeliveryRepository extends JpaRepository<NotificationDelivery, UUID> {

    /** Tenant-scoped lookup for admin "retry now"; a miss (incl. cross-tenant) becomes a 404. */
    Optional<NotificationDelivery> findByDeliveryIdAndTenantId(UUID deliveryId, UUID tenantId);

    List<NotificationDelivery> findByNotificationId(UUID notificationId);

    List<NotificationDelivery> findByTenantIdAndNotificationId(UUID tenantId, UUID notificationId);

    /** Admin delivery log with optional filters, newest first. */
    @Query("""
            select d from NotificationDelivery d
            where d.tenantId = :tenantId
              and (:status is null or d.status = :status)
              and (:channel is null or d.channel = :channel)
            """)
    Page<NotificationDelivery> findLog(@Param("tenantId") UUID tenantId,
                                       @Param("status") DeliveryStatus status,
                                       @Param("channel") NotificationChannel channel,
                                       Pageable pageable);
}
