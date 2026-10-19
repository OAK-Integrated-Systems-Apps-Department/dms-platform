package com.oak.dms.notification.enums;

/**
 * Category tier (3.2). In-app is always on for every tier. Email:
 * <ul>
 *   <li>{@code critical} - always on, cannot be turned off by user or tenant.</li>
 *   <li>{@code standard} - on by default, a user can turn it off.</li>
 *   <li>{@code optional} - off by default, a user can turn it on.</li>
 * </ul>
 * Stored as the lowercase name in {@code notification_categories.tier}.
 */
public enum NotificationTier {
    critical,
    standard,
    optional
}
