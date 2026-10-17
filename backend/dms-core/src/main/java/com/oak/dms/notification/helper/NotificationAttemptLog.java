package com.oak.dms.notification.helper;

import com.oak.dms.notification.entity.NotificationDelivery;
import com.oak.dms.notification.entity.NotificationDeliveryAttempt;
import com.oak.dms.notification.enums.DeliveryOutcome;
import com.oak.dms.notification.repository.NotificationDeliveryAttemptRepository;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Appends one row to the immutable {@code notification_delivery_attempts} table
 * for every send attempt (3.2, N-FR-005 / N-NFR-005). The table blocks
 * UPDATE/DELETE (trigger + REVOKE, V103), so this is insert-only.
 */
@Component
public class NotificationAttemptLog {

    private final NotificationDeliveryAttemptRepository attempts;

    public NotificationAttemptLog(NotificationDeliveryAttemptRepository attempts) {
        this.attempts = attempts;
    }

    public void append(NotificationDelivery delivery, UUID recipientUserId, int attemptNumber,
                       DeliveryOutcome outcome, String errorCode, String errorMessage, Integer durationMs) {
        attempts.save(NotificationDeliveryAttempt.builder()
                .tenantId(delivery.getTenantId())
                .deliveryId(delivery.getDeliveryId())
                .notificationId(delivery.getNotificationId())
                .recipientUserId(recipientUserId)
                .attemptNumber(attemptNumber)
                .channel(delivery.getChannel())
                .destination(delivery.getDestination())
                .outcome(outcome)
                .errorCode(truncate(errorCode, 80))
                .errorMessage(truncate(errorMessage, 2000))
                .durationMs(durationMs)
                .build());
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
