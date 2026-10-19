package com.oak.dms.notification.service;

import com.oak.dms.notification.entity.NotificationCategory;
import com.oak.dms.notification.enums.NotificationChannel;
import com.oak.dms.notification.enums.NotificationTier;
import com.oak.dms.notification.repository.TenantNotificationSettingRepository;
import com.oak.dms.notification.repository.UserNotificationPreferenceRepository;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Resolves whether email is enabled for a (user, category) pair (3.2, N6).
 * Order: critical -> always on; else user preference; else tenant default;
 * else category default. In-app is always on and is not resolved here.
 */
@Component
public class PreferenceResolver {

    private final UserNotificationPreferenceRepository userPreferences;
    private final TenantNotificationSettingRepository tenantSettings;

    public PreferenceResolver(UserNotificationPreferenceRepository userPreferences,
                              TenantNotificationSettingRepository tenantSettings) {
        this.userPreferences = userPreferences;
        this.tenantSettings = tenantSettings;
    }

    /**
     * True if email should be attempted for this recipient and category.
     * Critical categories can never be turned off by a user or a tenant.
     */
    public boolean emailEnabled(UUID tenantId, UUID userId, NotificationCategory category) {
        if (category.getTier() == NotificationTier.critical) {
            return true;
        }
        return userPreferences
                .findByTenantIdAndUserIdAndCategoryCodeAndChannelAndDeletedFalse(
                        tenantId, userId, category.getCategoryCode(), NotificationChannel.email)
                .map(com.oak.dms.notification.entity.UserNotificationPreference::isEnabled)
                .orElseGet(() -> tenantSettings
                        .findByTenantIdAndCategoryCodeAndDeletedFalse(tenantId, category.getCategoryCode())
                        .map(com.oak.dms.notification.entity.TenantNotificationSetting::isEmailEnabledDefault)
                        .orElse(category.isDefaultEmailEnabled()));
    }
}
