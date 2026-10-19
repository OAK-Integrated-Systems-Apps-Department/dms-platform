package com.oak.dms.notification.dispatch;

import java.util.UUID;

/**
 * Published by the notification service when it creates at least one pending
 * email delivery. An AFTER_COMMIT nudge wakes the dispatcher so delivery is
 * near-real-time (N-NFR-002) without waiting for the next poll.
 */
public record NotificationCreatedEvent(UUID tenantId) {
}
