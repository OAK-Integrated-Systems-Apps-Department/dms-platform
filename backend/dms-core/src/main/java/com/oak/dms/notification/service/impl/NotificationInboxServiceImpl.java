package com.oak.dms.notification.service.impl;

import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.notification.NotificationConflictException;
import com.oak.dms.notification.dto.NotificationView;
import com.oak.dms.notification.dto.PreferenceView;
import com.oak.dms.notification.entity.Notification;
import com.oak.dms.notification.entity.NotificationCategory;
import com.oak.dms.notification.entity.UserNotificationPreference;
import com.oak.dms.notification.enums.NotificationChannel;
import com.oak.dms.notification.enums.NotificationTier;
import com.oak.dms.notification.helper.NotificationDeliveryAuditor;
import com.oak.dms.notification.repository.NotificationCategoryRepository;
import com.oak.dms.notification.repository.NotificationRepository;
import com.oak.dms.notification.repository.UserNotificationPreferenceRepository;
import com.oak.dms.notification.service.NotificationInboxService;
import com.oak.dms.notification.service.PreferenceResolver;
import com.oak.dms.security.auth.TenantContext;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Self-service inbox + email preferences for the authenticated caller (3.2, N12). */
@Service
public class NotificationInboxServiceImpl implements NotificationInboxService {

    private final NotificationRepository notifications;
    private final NotificationCategoryRepository categories;
    private final UserNotificationPreferenceRepository preferences;
    private final PreferenceResolver preferenceResolver;
    private final NotificationDeliveryAuditor auditor;

    public NotificationInboxServiceImpl(NotificationRepository notifications,
                                        NotificationCategoryRepository categories,
                                        UserNotificationPreferenceRepository preferences,
                                        PreferenceResolver preferenceResolver, NotificationDeliveryAuditor auditor) {
        this.notifications = notifications;
        this.categories = categories;
        this.preferences = preferences;
        this.preferenceResolver = preferenceResolver;
        this.auditor = auditor;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<NotificationView> inbox(boolean unreadOnly, String categoryCode, Pageable pageable) {
        return notifications.findInbox(TenantContext.getTenantId(), TenantContext.getUserId(), unreadOnly,
                categoryCode, pageable).map(NotificationView::of);
    }

    @Override
    @Transactional(readOnly = true)
    public long unreadCount() {
        return notifications.countUnread(TenantContext.getTenantId(), TenantContext.getUserId());
    }

    @Override
    @Transactional
    public void markRead(UUID notificationId) {
        Notification n = owned(notificationId);
        if (n.getReadAt() == null) {
            n.setReadAt(OffsetDateTime.now());
            notifications.save(n);
        }
    }

    @Override
    @Transactional
    public int markAllRead() {
        return notifications.markAllRead(TenantContext.getTenantId(), TenantContext.getUserId(), OffsetDateTime.now());
    }

    @Override
    @Transactional
    public void archive(UUID notificationId) {
        Notification n = owned(notificationId);
        if (n.getArchivedAt() == null) {
            n.setArchivedAt(OffsetDateTime.now());
            if (n.getReadAt() == null) {
                n.setReadAt(OffsetDateTime.now());
            }
            notifications.save(n);
        }
    }

    private Notification owned(UUID notificationId) {
        return notifications.findByNotificationIdAndTenantIdAndRecipientUserId(notificationId,
                        TenantContext.getTenantId(), TenantContext.getUserId())
                .orElseThrow(() -> new ResourceNotFoundException("Notification", notificationId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<PreferenceView> preferences() {
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();
        return categories.findByActiveTrueOrderByModuleAscCategoryCodeAsc().stream()
                .map(c -> new PreferenceView(c.getCategoryCode(), c.getModule(), c.getTier().name(),
                        c.getDisplayName(), c.getDescription(),
                        preferenceResolver.emailEnabled(tenantId, userId, c),
                        c.getTier() == NotificationTier.critical))
                .toList();
    }

    @Override
    @Transactional
    public void updatePreference(String categoryCode, boolean emailEnabled) {
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();
        NotificationCategory category = categories.findById(categoryCode)
                .orElseThrow(() -> new ResourceNotFoundException("NotificationCategory", categoryCode));
        if (category.getTier() == NotificationTier.critical) {
            throw new NotificationConflictException("NOTIFICATION_CATEGORY_LOCKED",
                    "Critical notifications cannot be turned off");
        }
        UserNotificationPreference pref = preferences
                .findByTenantIdAndUserIdAndCategoryCodeAndChannelAndDeletedFalse(tenantId, userId, categoryCode,
                        NotificationChannel.email)
                .orElseGet(() -> UserNotificationPreference.builder()
                        .tenantId(tenantId).userId(userId).categoryCode(categoryCode)
                        .channel(NotificationChannel.email).enabled(emailEnabled).createdBy(userId).build());
        Boolean previous = pref.getPreferenceId() == null ? null : pref.isEnabled();
        pref.setEnabled(emailEnabled);
        pref.setUpdatedBy(userId);
        pref.setUpdatedAt(OffsetDateTime.now());
        preferences.save(pref);
        auditor.preferenceChanged(tenantId, userId, TenantContext.getDisplayNameOrNull(), categoryCode, previous,
                emailEnabled);
    }
}
