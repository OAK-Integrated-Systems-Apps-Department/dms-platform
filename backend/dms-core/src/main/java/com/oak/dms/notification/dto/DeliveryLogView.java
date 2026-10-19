package com.oak.dms.notification.dto;

import com.oak.dms.notification.entity.NotificationDelivery;

import java.time.OffsetDateTime;
import java.util.UUID;

/** One row of the admin delivery log (3.2, N12). */
public record DeliveryLogView(
        UUID deliveryId,
        UUID notificationId,
        String channel,
        String destination,
        String status,
        String skipReason,
        int attemptCount,
        int maxAttempts,
        String lastError,
        OffsetDateTime nextAttemptAt,
        OffsetDateTime sentAt,
        OffsetDateTime createdAt) {

    public static DeliveryLogView of(NotificationDelivery d) {
        return new DeliveryLogView(d.getDeliveryId(), d.getNotificationId(), d.getChannel().name(),
                d.getDestination(), d.getStatus().name(), d.getSkipReason(), d.getAttemptCount(), d.getMaxAttempts(),
                d.getLastError(), d.getNextAttemptAt(), d.getSentAt(), d.getCreatedAt());
    }
}
