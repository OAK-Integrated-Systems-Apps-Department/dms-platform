package com.oak.dms.notification.helper;

import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.catalog.AuditObjectTypes;
import com.oak.dms.audit.enums.AuditOutcome;
import com.oak.dms.audit.service.AuditService;
import com.oak.dms.notification.entity.Notification;
import com.oak.dms.notification.entity.NotificationDelivery;
import com.oak.dms.user.User;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Writes the few notification events that belong in the MAIN audit trail
 * (3.2, decision 6 / N7): final delivery failure, a recipient with no email,
 * tenant setting changes, user preference changes, and "retry now". The
 * per-attempt record goes to the append-only notification_delivery_attempts
 * table, not here. Notification events run on background/after-commit threads
 * with no TenantContext, so the tenant and a SYSTEM/actor are set explicitly.
 */
@Slf4j
@Component
public class NotificationDeliveryAuditor {

    private final AuditService auditService;

    public NotificationDeliveryAuditor(AuditService auditService) {
        this.auditService = auditService;
    }

    public void deliveryFailed(NotificationDelivery delivery, Notification notification, String lastError) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("notificationId", notification.getNotificationId());
        detail.put("recipientUserId", notification.getRecipientUserId());
        detail.put("categoryCode", notification.getCategoryCode());
        detail.put("channel", delivery.getChannel().name());
        detail.put("attempts", delivery.getAttemptCount());
        detail.put("lastError", lastError);
        auditService.event(AuditActionCatalog.NOTIFICATION_DELIVERY_FAILED)
                .tenantId(delivery.getTenantId())
                .systemActor()
                .objectType(AuditObjectTypes.NOTIFICATION_DELIVERY)
                .objectId(delivery.getDeliveryId())
                .outcome(AuditOutcome.FAILURE)
                .failureReason(lastError)
                .newValue(detail)
                .log();
    }

    public void recipientNoEmail(Notification notification, User user) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("notificationId", notification.getNotificationId());
        detail.put("recipientUserId", user.getUserId());
        detail.put("categoryCode", notification.getCategoryCode());
        auditService.event(AuditActionCatalog.NOTIFICATION_RECIPIENT_NO_EMAIL)
                .tenantId(notification.getTenantId())
                .systemActor()
                .objectType(AuditObjectTypes.NOTIFICATION_DELIVERY)
                .objectId(notification.getNotificationId())
                .outcome(AuditOutcome.SUCCESS)
                .newValue(detail)
                .log();
    }

    public void settingChanged(UUID tenantId, UUID actorId, String actorName, String categoryCode,
                               Boolean previous, boolean current) {
        auditService.event(AuditActionCatalog.NOTIFICATION_SETTING_CHANGED)
                .tenantId(tenantId)
                .actor(actorId, actorName)
                .objectType(AuditObjectTypes.NOTIFICATION_SETTING)
                .resourceName(categoryCode)
                .outcome(AuditOutcome.SUCCESS)
                .previousValue(Map.of("emailEnabledDefault", String.valueOf(previous)))
                .newValue(Map.of("emailEnabledDefault", current))
                .log();
    }

    public void preferenceChanged(UUID tenantId, UUID actorId, String actorName, String categoryCode,
                                  Boolean previous, boolean current) {
        auditService.event(AuditActionCatalog.NOTIFICATION_PREFERENCE_CHANGED)
                .tenantId(tenantId)
                .actor(actorId, actorName)
                .objectType(AuditObjectTypes.NOTIFICATION_SETTING)
                .resourceName(categoryCode)
                .outcome(AuditOutcome.SUCCESS)
                .previousValue(Map.of("emailEnabled", String.valueOf(previous)))
                .newValue(Map.of("emailEnabled", current))
                .log();
    }

    public void retried(NotificationDelivery delivery, UUID actorId, String actorName) {
        auditService.event(AuditActionCatalog.NOTIFICATION_DELIVERY_RETRIED)
                .tenantId(delivery.getTenantId())
                .actor(actorId, actorName)
                .objectType(AuditObjectTypes.NOTIFICATION_DELIVERY)
                .objectId(delivery.getDeliveryId())
                .outcome(AuditOutcome.SUCCESS)
                .newValue(Map.of("channel", delivery.getChannel().name()))
                .log();
    }
}
