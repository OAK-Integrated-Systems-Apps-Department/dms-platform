package com.oak.dms.notification.listener;

import com.oak.dms.folder.AccessGrantedEvent;
import com.oak.dms.notification.service.NotificationRequest;
import com.oak.dms.notification.service.NotificationService;
import com.oak.dms.security.rbac.repository.UserRoleRepository;
import com.oak.dms.user.User;
import com.oak.dms.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * "Shared with you" (3.2, N10): when people or roles are newly put on a
 * folder's or document's own access list, tell them (category
 * {@code document.shared}, standard tier). Runs BEFORE_COMMIT in the
 * transaction that saved the list, like every other notification trigger.
 * <p>
 * No access check is needed here: being on the list is exactly what lets the
 * recipient open the object, so naming it and linking to it cannot reveal
 * anything. The person who shared it is not told about their own action.
 */
@Component
@RequiredArgsConstructor
public class AccessGrantedNotificationListener {

    private final NotificationService notificationService;
    private final UserRoleRepository userRoleRepository;
    private final UserRepository userRepository;

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onAccessGranted(AccessGrantedEvent event) {
        Set<UUID> recipients = new LinkedHashSet<>(event.userIds());
        if (!event.roleIds().isEmpty()) {
            recipients.addAll(userRoleRepository.findActiveUserIdsByRoleIds(event.tenantId(), event.roleIds()));
        }
        recipients.remove(event.actorUserId());
        if (recipients.isEmpty()) {
            return;
        }
        boolean document = "document".equals(event.objectType());
        String actor = event.actorUserId() == null ? "Someone"
                : userRepository.findById(event.actorUserId()).map(User::getDisplayName).orElse("Someone");
        String noun = document ? "a document" : "a folder";
        notificationService.notify(NotificationRequest.builder()
                .tenantId(event.tenantId())
                .recipientUserIds(recipients)
                .categoryCode("document.shared")
                .title(actor + " shared " + noun + " with you: " + event.objectName())
                .body(actor + " gave you access to the " + (document ? "document" : "folder") + " \""
                        + event.objectName() + "\".")
                .linkUrl(document ? "/documents/" + event.objectId() : "/shared")
                .objectType(event.objectType())
                .objectId(event.objectId())
                .actorUserId(event.actorUserId())
                .sourceModule("documents")
                .build());
    }
}
