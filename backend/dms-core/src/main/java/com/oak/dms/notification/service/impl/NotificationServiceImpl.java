package com.oak.dms.notification.service.impl;

import com.oak.dms.notification.config.NotificationProperties;
import com.oak.dms.notification.entity.Notification;
import com.oak.dms.notification.entity.NotificationCategory;
import com.oak.dms.notification.entity.NotificationDelivery;
import com.oak.dms.notification.enums.DeliveryOutcome;
import com.oak.dms.notification.enums.DeliveryStatus;
import com.oak.dms.notification.enums.NotificationChannel;
import com.oak.dms.notification.dispatch.NotificationCreatedEvent;
import com.oak.dms.notification.helper.NotificationAccessResolver;
import com.oak.dms.notification.helper.NotificationAttemptLog;
import com.oak.dms.notification.helper.NotificationDeliveryAuditor;
import com.oak.dms.notification.repository.NotificationCategoryRepository;
import com.oak.dms.notification.repository.NotificationDeliveryRepository;
import com.oak.dms.notification.repository.NotificationRepository;
import com.oak.dms.notification.service.NotificationRequest;
import com.oak.dms.notification.service.NotificationService;
import com.oak.dms.notification.service.PreferenceResolver;
import com.oak.dms.user.User;
import com.oak.dms.user.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Transactional write of the inbox row and the delivery rows (3.2, N3). Runs
 * in the caller's transaction (decision 3) - call it from within the business
 * transaction (N8 uses BEFORE_COMMIT listeners). In-app is always created and
 * marked sent (the inbox row IS the delivery). Email is pending when the
 * resolved preference allows it and the recipient has an address; otherwise a
 * skipped email delivery records why.
 */
@Slf4j
@Service
public class NotificationServiceImpl implements NotificationService {

    private final NotificationRepository notifications;
    private final NotificationDeliveryRepository deliveries;
    private final NotificationCategoryRepository categories;
    private final UserRepository userRepository;
    private final PreferenceResolver preferenceResolver;
    private final NotificationAttemptLog attemptLog;
    private final NotificationDeliveryAuditor auditor;
    private final NotificationProperties properties;
    private final ApplicationEventPublisher events;
    private final NotificationAccessResolver accessResolver;

    public NotificationServiceImpl(NotificationRepository notifications, NotificationDeliveryRepository deliveries,
                                   NotificationCategoryRepository categories, UserRepository userRepository,
                                   PreferenceResolver preferenceResolver, NotificationAttemptLog attemptLog,
                                   NotificationDeliveryAuditor auditor, NotificationProperties properties,
                                   ApplicationEventPublisher events, NotificationAccessResolver accessResolver) {
        this.notifications = notifications;
        this.deliveries = deliveries;
        this.categories = categories;
        this.userRepository = userRepository;
        this.preferenceResolver = preferenceResolver;
        this.attemptLog = attemptLog;
        this.auditor = auditor;
        this.properties = properties;
        this.events = events;
        this.accessResolver = accessResolver;
    }

    @Override
    @Transactional
    public List<UUID> notify(NotificationRequest request) {
        if (request.getRecipientUserIds() == null || request.getRecipientUserIds().isEmpty()) {
            return List.of();
        }
        NotificationCategory category = categories.findById(request.getCategoryCode())
                .orElseThrow(() -> new IllegalStateException(
                        "Unknown notification category: " + request.getCategoryCode()));
        UUID tenantId = request.getTenantId();
        Map<UUID, User> recipients = userRepository
                .findAllByUserIdInAndTenantIdAndDeletedFalse(request.getRecipientUserIds(), tenantId).stream()
                .collect(Collectors.toMap(User::getUserId, Function.identity(), (a, b) -> a));

        String sourceModule = request.getSourceModule() != null ? request.getSourceModule() : category.getModule();
        // Decision 7: one access check for all recipients, only when the content names/links a document.
        Set<UUID> canOpen = request.getDocumentId() == null ? null
                : accessResolver.canOpen(tenantId, request.getDocumentId(), recipients.keySet(),
                        request.isRequireViewPermission());
        List<UUID> created = new ArrayList<>();
        boolean anyEmailPending = false;
        for (UUID recipientId : request.getRecipientUserIds()) {
            User user = recipients.get(recipientId);
            if (user == null) {
                continue; // not in this tenant, or deleted
            }
            String dedupeKey = request.getDedupeKey() != null ? request.getDedupeKey() : UUID.randomUUID().toString();
            if (request.getDedupeKey() != null
                    && notifications.existsByTenantIdAndRecipientUserIdAndDedupeKey(tenantId, recipientId, dedupeKey)) {
                continue; // idempotent
            }

            NotificationRequest.RecipientContent content = resolveContent(request, recipientId, canOpen);
            // A recipient who can't open the document doesn't get a reference to it either.
            boolean hidesDocument = canOpen != null && !canOpen.contains(recipientId)
                    && request.getDocumentId().equals(request.getObjectId());
            Notification notification = notifications.save(Notification.builder()
                    .tenantId(tenantId)
                    .recipientUserId(recipientId)
                    .categoryCode(category.getCategoryCode())
                    .tier(category.getTier())
                    .title(content.title())
                    .body(content.body())
                    .linkUrl(content.linkUrl())
                    .objectType(hidesDocument ? null : request.getObjectType())
                    .objectId(hidesDocument ? null : request.getObjectId())
                    .actorUserId(request.getActorUserId())
                    .sourceModule(sourceModule)
                    .dedupeKey(dedupeKey)
                    .build());

            writeInAppDelivery(notification, recipientId);
            anyEmailPending |= writeEmailDelivery(notification, category, user);
            created.add(notification.getNotificationId());
        }
        if (anyEmailPending) {
            // Wake the dispatcher after this transaction commits (N-NFR-002).
            events.publishEvent(new NotificationCreatedEvent(tenantId));
        }
        return created;
    }

    private void writeInAppDelivery(Notification notification, UUID recipientId) {
        NotificationDelivery inApp = deliveries.save(NotificationDelivery.builder()
                .tenantId(notification.getTenantId())
                .notificationId(notification.getNotificationId())
                .channel(NotificationChannel.in_app)
                .status(DeliveryStatus.sent)
                .attemptCount(1)
                .maxAttempts(1)
                .nextAttemptAt(OffsetDateTime.now())
                .sentAt(OffsetDateTime.now())
                .updatedAt(OffsetDateTime.now())
                .build());
        attemptLog.append(inApp, recipientId, 1, DeliveryOutcome.sent, null, null, null);
    }

    /** Returns true when a pending email delivery was created (so the caller nudges the dispatcher). */
    private boolean writeEmailDelivery(Notification notification, NotificationCategory category, User user) {
        UUID tenantId = notification.getTenantId();
        UUID recipientId = user.getUserId();
        boolean hasEmail = user.getEmail() != null && !user.getEmail().isBlank();
        boolean emailEnabled = preferenceResolver.emailEnabled(tenantId, recipientId, category);

        if (emailEnabled && hasEmail) {
            deliveries.save(NotificationDelivery.builder()
                    .tenantId(tenantId)
                    .notificationId(notification.getNotificationId())
                    .channel(NotificationChannel.email)
                    .destination(user.getEmail())
                    .status(DeliveryStatus.pending)
                    .attemptCount(0)
                    .maxAttempts(Math.max(1, properties.getDispatcher().getMaxAttempts()))
                    .nextAttemptAt(OffsetDateTime.now())
                    .updatedAt(OffsetDateTime.now())
                    .build());
            return true;
        }

        String reason = !hasEmail ? "no_email" : "email_disabled";
        NotificationDelivery skipped = deliveries.save(NotificationDelivery.builder()
                .tenantId(tenantId)
                .notificationId(notification.getNotificationId())
                .channel(NotificationChannel.email)
                .destination(hasEmail ? user.getEmail() : null)
                .status(DeliveryStatus.skipped)
                .skipReason(reason)
                .attemptCount(0)
                .maxAttempts(Math.max(1, properties.getDispatcher().getMaxAttempts()))
                .nextAttemptAt(OffsetDateTime.now())
                .updatedAt(OffsetDateTime.now())
                .build());
        attemptLog.append(skipped, recipientId, 1, DeliveryOutcome.skipped, reason, null, null);
        if (!hasEmail) {
            // Alternate flow: no contact on record -> in-app only, flag the admin (N6/N7).
            auditor.recipientNoEmail(notification, user);
        }
        return false;
    }

    private NotificationRequest.RecipientContent resolveContent(NotificationRequest request, UUID recipientId,
                                                                Set<UUID> canOpen) {
        // Default content, made neutral when this recipient cannot open the document it names.
        NotificationRequest.RecipientContent base;
        if (canOpen == null || canOpen.contains(recipientId)) {
            base = new NotificationRequest.RecipientContent(request.getTitle(), request.getBody(),
                    request.getLinkUrl());
        } else {
            base = new NotificationRequest.RecipientContent(
                    request.getNeutralTitle() != null ? request.getNeutralTitle() : "A document needs your attention",
                    request.getNeutralBody() != null ? request.getNeutralBody()
                            : "You don't have access to this document.",
                    request.getNeutralLinkUrl());
        }
        NotificationRequest.RecipientContent override = request.getPerRecipientContent() != null
                ? request.getPerRecipientContent().apply(recipientId) : null;
        if (override == null) {
            return base;
        }
        return new NotificationRequest.RecipientContent(
                override.title() != null ? override.title() : base.title(),
                override.body() != null ? override.body() : base.body(),
                override.linkUrl() != null ? override.linkUrl() : base.linkUrl());
    }
}
