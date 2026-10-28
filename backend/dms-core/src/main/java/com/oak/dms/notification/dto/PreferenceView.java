package com.oak.dms.notification.dto;

/**
 * One category on the caller's notification-preferences page (3.2, N12).
 * {@code emailEnabled} is the resolved effective value; {@code locked} is true
 * for critical categories (in-app and email always on, not changeable).
 */
public record PreferenceView(
        String categoryCode,
        String module,
        String tier,
        String displayName,
        String description,
        boolean emailEnabled,
        boolean locked) {
}
