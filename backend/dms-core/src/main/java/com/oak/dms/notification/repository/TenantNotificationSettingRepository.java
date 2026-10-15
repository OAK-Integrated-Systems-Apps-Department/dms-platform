package com.oak.dms.notification.repository;

import com.oak.dms.notification.entity.TenantNotificationSetting;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TenantNotificationSettingRepository extends JpaRepository<TenantNotificationSetting, UUID> {

    Optional<TenantNotificationSetting> findByTenantIdAndCategoryCodeAndDeletedFalse(UUID tenantId, String categoryCode);

    List<TenantNotificationSetting> findByTenantIdAndDeletedFalse(UUID tenantId);
}
