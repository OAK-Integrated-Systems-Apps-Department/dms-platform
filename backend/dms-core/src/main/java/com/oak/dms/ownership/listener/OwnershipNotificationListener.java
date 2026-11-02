package com.oak.dms.ownership.listener;

import com.oak.dms.notification.service.NotificationRequest;
import com.oak.dms.notification.service.NotificationRequest.RecipientContent;
import com.oak.dms.notification.service.NotificationService;
import com.oak.dms.ownership.event.OwnershipTransferredEvent;
import com.oak.dms.security.auth.UserPermissions;
import com.oak.dms.user.User;
import com.oak.dms.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Tells people about ownership changes (DOT-FR-006, D10). Runs BEFORE_COMMIT, like the other listeners, so the
 * notifications commit with the transfer.
 * <ul>
 *   <li>{@code document.ownership.received} (standard) - the new owner;</li>
 *   <li>{@code document.ownership.released} (optional) - the previous owner(s), if their account is still active;</li>
 *   <li>{@code document.ownership.holding} (standard) - the holding owner and the tenant admins when documents were
 *       parked pending reassignment.</li>
 * </ul>
 * A single transfer names the document - but only to a recipient who can open it. A bulk or offboarding run sends
 * one summary per recipient with a count and a link to their owned documents, never one message per document and
 * never a title. The person who did it is never told.
 */
@Component
@RequiredArgsConstructor
public class OwnershipNotificationListener {

    static final String RECEIVED = "document.ownership.received";
    static final String RELEASED = "document.ownership.released";
    static final String HOLDING = "document.ownership.holding";

    private final NotificationService notificationService;
    private final UserRepository users;
    private final UserPermissions userPermissions;

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onTransferred(OwnershipTransferredEvent event) {
        if (event.transferredCount() <= 0) {
            return;
        }
        String actor = event.actorUserId() == null ? "Someone"
                : users.findById(event.actorUserId()).map(User::getDisplayName).orElse("Someone");
        if (event.singleDocumentId() != null) {
            single(event, actor);
        } else {
            batch(event, actor);
        }
    }

    private void single(OwnershipTransferredEvent event, String actor) {
        UUID documentId = event.singleDocumentId();
        String title = event.singleDocumentTitle();
        String link = "/documents/" + documentId;
        if (!event.newOwnerUserId().equals(event.actorUserId())) {
            notificationService.notify(NotificationRequest.builder()
                    .tenantId(event.tenantId())
                    .recipientUserIds(List.of(event.newOwnerUserId()))
                    .categoryCode(RECEIVED)
                    .title("\"" + title + "\" was transferred to you")
                    .body(actor + " made you the owner of \"" + title + "\".")
                    .linkUrl(link)
                    .objectType("document")
                    .objectId(documentId)
                    .actorUserId(event.actorUserId())
                    .sourceModule("documents")
                    .dedupeKey(key(event, event.newOwnerUserId(), RECEIVED))
                    .documentId(documentId)
                    .requireViewPermission(true)
                    .neutralTitle("A document was transferred to you")
                    .neutralBody(actor + " made you the owner of a document. You can't open it yet, so its name isn't shown.")
                    .build());
        }
        Set<UUID> previous = new LinkedHashSet<>(event.previousOwnerCounts().keySet());
        previous.remove(event.actorUserId());
        previous.remove(event.newOwnerUserId());
        if (!previous.isEmpty()) {
            notificationService.notify(NotificationRequest.builder()
                    .tenantId(event.tenantId())
                    .recipientUserIds(previous)
                    .categoryCode(RELEASED)
                    .title("\"" + title + "\" is no longer yours")
                    .body(actor + " transferred the ownership of \"" + title + "\" to someone else.")
                    .linkUrl(link)
                    .objectType("document")
                    .objectId(documentId)
                    .actorUserId(event.actorUserId())
                    .sourceModule("documents")
                    .dedupeKey("ownership:" + event.batchId() + ":released")
                    .documentId(documentId)
                    .requireViewPermission(true)
                    .neutralTitle("A document you owned was transferred")
                    .neutralBody("Someone transferred the ownership of one of your documents. You can't open it any more, so its name isn't shown.")
                    .build());
        }
    }

    private void batch(OwnershipTransferredEvent event, String actor) {
        int count = event.transferredCount();
        String documents = count == 1 ? "1 document" : count + " documents";
        UUID newOwner = event.newOwnerUserId();
        String ownedLink = "/documents?ownerId=" + newOwner;

        if (event.holding()) {
            Set<UUID> recipients = new LinkedHashSet<>(userPermissions.tenantAdminIds(event.tenantId()));
            recipients.add(newOwner);
            recipients.remove(event.actorUserId());
            if (!recipients.isEmpty()) {
                notificationService.notify(NotificationRequest.builder()
                        .tenantId(event.tenantId())
                        .recipientUserIds(recipients)
                        .categoryCode(HOLDING)
                        .title(documents + " waiting for a new owner")
                        .body(actor + " placed " + documents + " with a holding owner. They need to be reassigned to someone.")
                        .linkUrl(ownedLink)
                        .objectType("ownership_batch")
                        .objectId(event.batchId())
                        .actorUserId(event.actorUserId())
                        .sourceModule("documents")
                        .dedupeKey("ownership:" + event.batchId() + ":holding")
                        .perRecipientContent(recipient -> recipient.equals(newOwner)
                                ? new RecipientContent(null, null, ownedLink)
                                : new RecipientContent(null, null, "/admin/ownership?tab=pending"))
                        .build());
            }
        } else if (!newOwner.equals(event.actorUserId())) {
            notificationService.notify(NotificationRequest.builder()
                    .tenantId(event.tenantId())
                    .recipientUserIds(List.of(newOwner))
                    .categoryCode(RECEIVED)
                    .title(documents + " transferred to you")
                    .body(actor + " transferred " + documents + " to you.")
                    .linkUrl(ownedLink)
                    .objectType("ownership_batch")
                    .objectId(event.batchId())
                    .actorUserId(event.actorUserId())
                    .sourceModule("documents")
                    .dedupeKey(key(event, newOwner, RECEIVED))
                    .build());
        }

        Map<UUID, Integer> previous = event.previousOwnerCounts();
        Set<UUID> released = new LinkedHashSet<>(previous.keySet());
        released.remove(event.actorUserId());
        released.remove(newOwner);
        if (!released.isEmpty()) {
            notificationService.notify(NotificationRequest.builder()
                    .tenantId(event.tenantId())
                    .recipientUserIds(released)
                    .categoryCode(RELEASED)
                    .title("Your documents were transferred")
                    .body(actor + " transferred some of your documents to someone else.")
                    .objectType("ownership_batch")
                    .objectId(event.batchId())
                    .actorUserId(event.actorUserId())
                    .sourceModule("documents")
                    .dedupeKey("ownership:" + event.batchId() + ":released")
                    .perRecipientContent(recipient -> {
                        int n = previous.getOrDefault(recipient, 0);
                        String text = n == 1 ? "1 of your documents was" : n + " of your documents were";
                        return new RecipientContent(text + " transferred", actor + " transferred " + text.replace(" was", "").replace(" were", "")
                                + " to someone else.", null);
                    })
                    .build());
        }
    }

    private static String key(OwnershipTransferredEvent event, UUID recipient, String category) {
        return "ownership:" + event.batchId() + ":" + recipient + ":" + category;
    }
}
