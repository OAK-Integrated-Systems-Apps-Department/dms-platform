package com.oak.dms.notification.service.impl;

import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.notification.NotificationConflictException;
import com.oak.dms.notification.config.NotificationProperties;
import com.oak.dms.notification.dto.DeliveryLogView;
import com.oak.dms.notification.dto.TenantSettingView;
import com.oak.dms.notification.entity.NotificationCategory;
import com.oak.dms.notification.entity.NotificationDelivery;
import com.oak.dms.notification.entity.TenantNotificationSetting;
import com.oak.dms.notification.enums.DeliveryStatus;
import com.oak.dms.notification.enums.NotificationChannel;
import com.oak.dms.notification.enums.NotificationTier;
import com.oak.dms.notification.dispatch.NotificationCreatedEvent;
import com.oak.dms.notification.helper.NotificationDeliveryAuditor;
import com.oak.dms.notification.repository.NotificationCategoryRepository;
import com.oak.dms.notification.repository.NotificationDeliveryRepository;
import com.oak.dms.notification.repository.TenantNotificationSettingRepository;
import com.oak.dms.notification.service.NotificationAdminService;
import com.oak.dms.security.auth.TenantContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** Tenant-admin notification settings + delivery log + "retry now" (3.2, N12). */
@Service
public class NotificationAdminServiceImpl implements NotificationAdminService {

    private final NotificationCategoryRepository categories;
    private final TenantNotificationSettingRepository tenantSettings;
    private final NotificationDeliveryRepository deliveries;
    private final NotificationDeliveryAuditor auditor;
    private final NotificationProperties properties;
    private final ApplicationEventPublisher events;

    public NotificationAdminServiceImpl(NotificationCategoryRepository categories,
                                        TenantNotificationSettingRepository tenantSettings,
                                        NotificationDeliveryRepository deliveries,
                                        NotificationDeliveryAuditor auditor, NotificationProperties properties,
                                        ApplicationEventPublisher events) {
        this.categories = categories;
        this.tenantSettings = tenantSettings;
        this.deliveries = deliveries;
        this.auditor = auditor;
        this.properties = properties;
        this.events = events;
    }

    @Override
    @Transactional(readOnly = true)
    public List<TenantSettingView> settings() {
        UUID tenantId = TenantContext.getTenantId();
        Map<String, Boolean> overrides = tenantSettings.findByTenantIdAndDeletedFalse(tenantId).stream()
                .collect(Collectors.toMap(TenantNotificationSetting::getCategoryCode,
                        TenantNotificationSetting::isEmailEnabledDefault, (a, b) -> a));
        return categories.findByActiveTrueOrderByModuleAscCategoryCodeAsc().stream()
                .map(c -> new TenantSettingView(c.getCategoryCode(), c.getModule(), c.getTier().name(),
                        c.getDisplayName(), c.getDescription(),
                        c.getTier() == NotificationTier.critical
                                || overrides.getOrDefault(c.getCategoryCode(), c.isDefaultEmailEnabled()),
                        c.getTier() == NotificationTier.critical))
                .toList();
    }

    @Override
    @Transactional
    public void updateSetting(String categoryCode, boolean emailEnabledDefault) {
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();
        NotificationCategory category = categories.findById(categoryCode)
                .orElseThrow(() -> new ResourceNotFoundException("NotificationCategory", categoryCode));
        if (category.getTier() == NotificationTier.critical) {
            throw new NotificationConflictException("NOTIFICATION_CATEGORY_LOCKED",
                    "Critical notifications cannot be turned off");
        }
        TenantNotificationSetting setting = tenantSettings
                .findByTenantIdAndCategoryCodeAndDeletedFalse(tenantId, categoryCode)
                .orElseGet(() -> TenantNotificationSetting.builder()
                        .tenantId(tenantId).categoryCode(categoryCode).emailEnabledDefault(emailEnabledDefault)
                        .createdBy(userId).build());
        Boolean previous = setting.getSettingId() == null ? null : setting.isEmailEnabledDefault();
        setting.setEmailEnabledDefault(emailEnabledDefault);
        setting.setUpdatedBy(userId);
        setting.setUpdatedAt(OffsetDateTime.now());
        tenantSettings.save(setting);
        auditor.settingChanged(tenantId, userId, TenantContext.getDisplayNameOrNull(), categoryCode, previous,
                emailEnabledDefault);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<DeliveryLogView> deliveries(DeliveryStatus status, NotificationChannel channel, Pageable pageable) {
        return deliveries.findLog(TenantContext.getTenantId(), status, channel, pageable).map(DeliveryLogView::of);
    }

    @Override
    @Transactional
    public void retry(UUID deliveryId) {
        UUID tenantId = TenantContext.getTenantId();
        NotificationDelivery d = deliveries.findByDeliveryIdAndTenantId(deliveryId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("NotificationDelivery", deliveryId));
        if (d.getStatus() != DeliveryStatus.failed && d.getStatus() != DeliveryStatus.skipped) {
            throw new NotificationConflictException("NOTIFICATION_DELIVERY_NOT_RETRYABLE",
                    "Only a failed or skipped delivery can be retried");
        }
        d.setStatus(DeliveryStatus.pending);
        d.setSkipReason(null);
        d.setNextAttemptAt(OffsetDateTime.now());
        d.setLockedUntil(null);
        d.setMaxAttempts(d.getAttemptCount() + Math.max(1, properties.getDispatcher().getMaxAttempts()));
        d.setUpdatedAt(OffsetDateTime.now());
        deliveries.save(d);
        auditor.retried(d, TenantContext.getUserId(), TenantContext.getDisplayNameOrNull());
        events.publishEvent(new NotificationCreatedEvent(tenantId));
    }
}
