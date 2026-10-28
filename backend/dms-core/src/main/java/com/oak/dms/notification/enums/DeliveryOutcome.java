package com.oak.dms.notification.enums;

/**
 * The outcome recorded for one row in the append-only
 * {@code notification_delivery_attempts} table (3.2, N-NFR-005). Stored as the
 * lowercase name in {@code notification_delivery_attempts.outcome}.
 */
public enum DeliveryOutcome {
    sent,
    failed,
    skipped
}
