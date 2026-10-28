package com.oak.dms.notification.dto;

import com.oak.dms.notification.entity.Notification;

import java.time.OffsetDateTime;
import java.util.UUID;

/** One inbox entry for the caller (3.2, N12). */
public record NotificationView(
        UUID notificationId,
        String categoryCode,
        String tier,
        String title,
        String body,
        String linkUrl,
        String objectType,
        UUID objectId,
        UUID actorUserId,
        String sourceModule,
        boolean read,
        boolean archived,
        OffsetDateTime createdAt,
        OffsetDateTime readAt) {

    public static NotificationView of(Notification n) {
        return new NotificationView(n.getNotificationId(), n.getCategoryCode(), n.getTier().name(), n.getTitle(),
                n.getBody(), n.getLinkUrl(), n.getObjectType(), n.getObjectId(), n.getActorUserId(),
                n.getSourceModule(), n.getReadAt() != null, n.getArchivedAt() != null, n.getCreatedAt(), n.getReadAt());
    }
}
