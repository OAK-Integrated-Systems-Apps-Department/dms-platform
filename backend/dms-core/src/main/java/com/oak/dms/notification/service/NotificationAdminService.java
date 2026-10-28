package com.oak.dms.notification.service;

import com.oak.dms.notification.dto.DeliveryLogView;
import com.oak.dms.notification.dto.TenantSettingView;
import com.oak.dms.notification.enums.DeliveryStatus;
import com.oak.dms.notification.enums.NotificationChannel;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.UUID;

/** Tenant-admin notification settings and the delivery log (3.2, N12). */
public interface NotificationAdminService {

    List<TenantSettingView> settings();

    void updateSetting(String categoryCode, boolean emailEnabledDefault);

    Page<DeliveryLogView> deliveries(DeliveryStatus status, NotificationChannel channel, Pageable pageable);

    void retry(UUID deliveryId);
}
