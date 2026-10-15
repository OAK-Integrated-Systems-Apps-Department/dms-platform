package com.oak.dms.notification.dto;

/**
 * One category on the admin Notification Settings page (3.2, N12). {@code emailEnabledDefault}
 * is the tenant default (its own value if set, else the global category default). Critical
 * categories are {@code locked} - a tenant admin cannot change them.
 */
public record TenantSettingView(
        String categoryCode,
        String module,
        String tier,
        String displayName,
        String description,
        boolean emailEnabledDefault,
        boolean locked) {
}
