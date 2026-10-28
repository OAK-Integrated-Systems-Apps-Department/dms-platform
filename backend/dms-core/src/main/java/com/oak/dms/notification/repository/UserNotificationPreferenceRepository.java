package com.oak.dms.notification.repository;

import com.oak.dms.notification.entity.UserNotificationPreference;
import com.oak.dms.notification.enums.NotificationChannel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserNotificationPreferenceRepository extends JpaRepository<UserNotificationPreference, UUID> {

    Optional<UserNotificationPreference> findByTenantIdAndUserIdAndCategoryCodeAndChannelAndDeletedFalse(
            UUID tenantId, UUID userId, String categoryCode, NotificationChannel channel);

    List<UserNotificationPreference> findByTenantIdAndUserIdAndDeletedFalse(UUID tenantId, UUID userId);
}
