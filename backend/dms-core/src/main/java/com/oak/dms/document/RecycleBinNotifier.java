package com.oak.dms.document;

import com.oak.dms.compliance.RetentionNotifier;
import com.oak.dms.notification.service.NotificationRequest;
import com.oak.dms.notification.service.NotificationService;
import com.oak.dms.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Tells people what happens to deleted documents (RBR-FR-005): the owner when a document is deleted, again before the
 * automatic purge, and once it has been purged; and the people who review compliance when a document under legal hold
 * outlives its recycle bin period. Each goes through the notification system, so in-app always shows and email follows
 * the category defaults and each person's preferences. A failure is logged and never fails the delete, restore or sweep.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class RecycleBinNotifier {

    static final String DELETED = "recycle-bin.deleted.owner";
    static final String PURGE_SOON = "recycle-bin.purge-soon.owner";
    static final String PURGED = "recycle-bin.purged.owner";
    static final String HOLD_REVIEW = "recycle-bin.hold-review";

    private final NotificationService notificationService;
    private final RetentionNotifier retentionNotifier;

    public void deleted(Document document) {
        send(document, DELETED, "\"" + document.getTitle() + "\" was moved to the recycle bin",
                "A document you own was deleted. You can restore it from the recycle bin until its retention period ends.",
                "A document you own was deleted", "A document you own was moved to the recycle bin.");
    }

    public void purgeSoon(Document document, long daysLeft) {
        send(document, PURGE_SOON, "\"" + document.getTitle() + "\" will be deleted for good soon",
                "It will be permanently removed from the recycle bin in " + daysLeft + (daysLeft == 1 ? " day" : " days")
                        + ". Restore it before then if you still need it.",
                "A deleted document you own will be removed soon",
                "A deleted document you own will be permanently removed from the recycle bin soon.");
    }

    public void purged(Document document, String why) {
        send(document, PURGED, "\"" + document.getTitle() + "\" was deleted for good",
                "It was permanently removed from the recycle bin (" + why + ") and can't be restored.",
                "A deleted document you own was removed", "A deleted document you own was permanently removed.");
    }

    /** One message per sweep run to the compliance reviewers - never one per document. */
    public void heldPastRetention(UUID tenantId, int count) {
        try {
            List<UUID> reviewers = retentionNotifier.reviewers(tenantId).stream().map(User::getUserId).toList();
            if (reviewers.isEmpty()) {
                return;
            }
            notificationService.notify(NotificationRequest.builder().tenantId(tenantId).recipientUserIds(reviewers)
                    .categoryCode(HOLD_REVIEW)
                    .title(count + (count == 1 ? " held document needs" : " held documents need") + " a compliance review")
                    .body((count == 1 ? "A deleted document is" : count + " deleted documents are") + " under legal hold "
                            + "and reached the end of the recycle bin period. Nothing was purged: it stays in the recycle "
                            + "bin until the hold is released.")
                    .linkUrl("/recycle-bin").objectType("tenant").objectId(tenantId).sourceModule("documents")
                    .dedupeKey(null).build());
        } catch (RuntimeException e) {
            log.warn("Could not send the legal-hold recycle bin notification for organisation {}: {}", tenantId,
                    e.getMessage());
        }
    }

    private void send(Document document, String category, String title, String body, String neutralTitle,
                      String neutralBody) {
        if (document.getOwnerUserId() == null) {
            return;
        }
        try {
            notificationService.notify(NotificationRequest.builder().tenantId(document.getTenantId())
                    .recipientUserIds(List.of(document.getOwnerUserId())).categoryCode(category).title(title).body(body)
                    .linkUrl("/recycle-bin").objectType("document").objectId(document.getDocumentId())
                    .sourceModule("documents").dedupeKey(null).neutralTitle(neutralTitle).neutralBody(neutralBody)
                    .build());
        } catch (RuntimeException e) {
            log.warn("Could not send the recycle bin notification {} for document {}: {}", category,
                    document.getDocumentId(), e.getMessage());
        }
    }
}
