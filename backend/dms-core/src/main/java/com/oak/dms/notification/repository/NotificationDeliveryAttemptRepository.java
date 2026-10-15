package com.oak.dms.notification.repository;

import com.oak.dms.notification.entity.NotificationDeliveryAttempt;
import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Append-only: deliberately extends the narrow {@link Repository} marker, NOT
 * {@code JpaRepository}, so no update/delete/saveAll mutator is exposed - only
 * {@code save} (an INSERT) and read methods. The DB also blocks UPDATE/DELETE
 * with a trigger + REVOKE (V82). N-FR-005 / N-NFR-005.
 */
public interface NotificationDeliveryAttemptRepository extends Repository<NotificationDeliveryAttempt, UUID> {

    NotificationDeliveryAttempt save(NotificationDeliveryAttempt attempt);

    List<NotificationDeliveryAttempt> findByDeliveryIdOrderByAttemptNumberAsc(UUID deliveryId);

    List<NotificationDeliveryAttempt> findByTenantIdAndNotificationId(UUID tenantId, UUID notificationId);

    long countByDeliveryId(UUID deliveryId);
}
