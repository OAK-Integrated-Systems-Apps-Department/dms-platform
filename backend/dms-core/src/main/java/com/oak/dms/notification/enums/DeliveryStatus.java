package com.oak.dms.notification.enums;

/**
 * Lifecycle of a single {@code notification_deliveries} row (3.2). See the
 * delivery state machine in docs/notification-plan.md. Stored as the lowercase
 * name in {@code notification_deliveries.status}.
 */
public enum DeliveryStatus {
    /** Waiting to be claimed and sent (or retried after a backoff). */
    pending,
    /** Claimed by a dispatcher and leased (locked_until) while sending. */
    sending,
    /** Delivered. Terminal. */
    sent,
    /** Exhausted max_attempts. Terminal; audited; admin can retry. */
    failed,
    /** Not attempted (no address, or the channel is disabled by preference). Terminal. */
    skipped
}
