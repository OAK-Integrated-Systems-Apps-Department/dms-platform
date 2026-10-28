package com.oak.dms.notification.enums;

/**
 * A delivery channel (3.2, decision 2). Email and in-app only; SMS is out of
 * scope but the {@code NotificationChannelSender} interface keeps the door
 * open for another channel. Stored as the lowercase name in the
 * {@code channel} columns.
 */
public enum NotificationChannel {
    email,
    in_app
}
