package com.oak.dms.relationship.listener;

import com.oak.dms.document.Document;
import com.oak.dms.document.DocumentRepository;
import com.oak.dms.notification.service.NotificationRequest;
import com.oak.dms.notification.service.NotificationService;
import com.oak.dms.relationship.entity.RelationshipType;
import com.oak.dms.relationship.event.RelationshipCreatedEvent;
import com.oak.dms.relationship.repository.RelationshipTypeRepository;
import com.oak.dms.user.User;
import com.oak.dms.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * "Document linked" (category {@code document.relationship-added}, optional tier): tells the owner of
 * a document when someone links to it. Runs BEFORE_COMMIT in the transaction that made the link.
 * <p>
 * Access-aware content: the notification names only the owner's own document, never the document that
 * was linked to it - the owner may not be able to open that one. If the owner can't open their own
 * document any more (access or the view permission removed) they get a neutral text with no title and
 * no link. A bulk request sends one notification per document linked to, not one per link.
 */
@Component
@RequiredArgsConstructor
public class RelationshipNotificationListener {

    static final String CATEGORY = "document.relationship-added";

    private final NotificationService notificationService;
    private final DocumentRepository documents;
    private final RelationshipTypeRepository types;
    private final UserRepository users;

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onRelationshipCreated(RelationshipCreatedEvent event) {
        Document notified = documents.findById(event.notifiedDocumentId())
                .filter(d -> d.getTenantId().equals(event.tenantId()) && !d.isDeleted()).orElse(null);
        if (notified == null || notified.getOwnerUserId() == null || notified.getOwnerUserId().equals(event.actorUserId())) {
            return;
        }
        String actor = event.actorUserId() == null ? "Someone"
                : users.findById(event.actorUserId()).map(User::getDisplayName).orElse("Someone");
        // How the link reads from the owner's document: the type's own label if it is the source, the inverse if the target.
        RelationshipType type = types.findByTenantIdAndRelationshipTypeIdIn(event.tenantId(),
                Set.of(event.relationshipTypeId())).stream().findFirst().orElse(null);
        boolean notifiedIsSource = event.notifiedDocumentId().equals(event.sourceDocumentId());
        String reads = type == null ? "" : " (" + (notifiedIsSource || type.isSymmetric() ? type.getLabel() : type.getInverseLabel()) + ")";
        String title = notified.getTitle();
        String dedupe = event.batchId() == null ? "relationship-added:" + event.relationshipId()
                : "relationship-added:batch:" + event.batchId() + ":" + event.notifiedDocumentId();
        notificationService.notify(NotificationRequest.builder()
                .tenantId(event.tenantId())
                .recipientUserIds(List.of(notified.getOwnerUserId()))
                .categoryCode(CATEGORY)
                .title(actor + " linked a document to \"" + title + "\"")
                .body(actor + " linked a document to your document \"" + title + "\"" + reads + ".")
                .linkUrl("/documents/" + notified.getDocumentId() + "#relationships")
                .objectType("document")
                .objectId(notified.getDocumentId())
                .actorUserId(event.actorUserId())
                .sourceModule("documents")
                .dedupeKey(dedupe)
                .documentId(notified.getDocumentId())
                .requireViewPermission(true)
                .neutralTitle("A document was linked to a document you own")
                .neutralBody("Someone linked a document to one of your documents. You can't open it, so its name isn't shown.")
                .build());
    }
}
