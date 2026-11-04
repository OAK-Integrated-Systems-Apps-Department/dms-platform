package com.oak.dms.compliance;

import com.oak.dms.notification.service.NotificationRequest;
import com.oak.dms.notification.service.NotificationService;
import com.oak.dms.security.keycloak.KeycloakUserService;
import com.oak.dms.user.User;
import com.oak.dms.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Tells people what a retention run did (6.1 follow-up) - one notification per kind per run, never one per document, so a
 * large run is one message:
 * <ul>
 *   <li>to the people who review retention (the {@code crm.review-retention} permission, and tenant administrators):
 *       documents newly flagged because their retention ended, and documents no rule covers;</li>
 *   <li>to each document owner: their documents that were archived.</li>
 * </ul>
 * Each goes through the notification system, so in-app always shows and email follows the usual category defaults and each
 * person's own preferences. A document is flagged once, so reviewers are told once - not every hour. Notification failures
 * are logged, never allowed to fail the retention run.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class RetentionNotifier {

    static final String REVIEW_DUE = "retention.review.due";
    static final String NO_RULE = "retention.no-rule";
    static final String ARCHIVED_OWNER = "retention.archived.owner";

    private final NotificationService notificationService;
    private final JdbcTemplate jdbc;
    private final UserRepository userRepository;
    private final KeycloakUserService keycloakUserService;

    /** Everything one run (or one catch-up) did that someone should hear about. */
    public static final class RunLog {
        public record Item(UUID documentId, String title, UUID ownerUserId) {
        }

        final List<Item> archived = new ArrayList<>();
        final List<Item> flagged = new ArrayList<>();
        final List<Item> withoutPolicy = new ArrayList<>();

        public void archived(UUID id, String title, UUID owner) {
            archived.add(new Item(id, title, owner));
        }

        public void flagged(UUID id, String title, UUID owner) {
            flagged.add(new Item(id, title, owner));
        }

        public void withoutPolicy(UUID id, String title, UUID owner) {
            withoutPolicy.add(new Item(id, title, owner));
        }

        boolean isEmpty() {
            return archived.isEmpty() && flagged.isEmpty() && withoutPolicy.isEmpty();
        }
    }

    public void notifyRun(UUID tenantId, RunLog run) {
        if (run.isEmpty()) {
            return;
        }
        try {
            if (!run.flagged.isEmpty() || !run.withoutPolicy.isEmpty()) {
                List<UUID> reviewers = reviewers(tenantId).stream().map(User::getUserId).toList();
                if (!reviewers.isEmpty()) {
                    if (!run.flagged.isEmpty()) {
                        int n = run.flagged.size();
                        send(tenantId, reviewers, REVIEW_DUE,
                                n + (n == 1 ? " document needs" : " documents need") + " a retention review",
                                (n == 1 ? "A document has" : n + " documents have") + " reached the end of "
                                        + (n == 1 ? "its" : "their") + " retention period. Nothing has been deleted: open "
                                        + "Records retention to keep " + (n == 1 ? "it" : "them") + " longer or send "
                                        + (n == 1 ? "it" : "them") + " to the recycle bin.",
                                "/admin/retention");
                    }
                    if (!run.withoutPolicy.isEmpty()) {
                        int n = run.withoutPolicy.size();
                        send(tenantId, reviewers, NO_RULE,
                                n + (n == 1 ? " document has" : " documents have") + " no retention rule",
                                "No retention rule covers " + (n == 1 ? "a document" : n + " documents") + ". Add a rule "
                                        + "(an organisation-wide one covers everything) under Records retention, then apply the rules.",
                                "/admin/retention");
                    }
                }
            }
            Map<UUID, List<RunLog.Item>> byOwner = new LinkedHashMap<>();
            for (RunLog.Item item : run.archived) {
                if (item.ownerUserId() != null) {
                    byOwner.computeIfAbsent(item.ownerUserId(), k -> new ArrayList<>()).add(item);
                }
            }
            byOwner.forEach((owner, items) -> notifyOwner(tenantId, owner, items));
        } catch (RuntimeException e) {
            log.warn("Could not send the retention notifications for organisation {}: {}", tenantId, e.getMessage());
        }
    }

    private void notifyOwner(UUID tenantId, UUID owner, List<RunLog.Item> items) {
        try {
            int n = items.size();
            NotificationRequest.NotificationRequestBuilder request = NotificationRequest.builder()
                    .tenantId(tenantId).recipientUserIds(List.of(owner)).categoryCode(ARCHIVED_OWNER).sourceModule("documents")
                    .objectType("document").dedupeKey(null);
            if (n == 1) {
                RunLog.Item only = items.get(0);
                request.title("\"" + only.title() + "\" was archived")
                        .body("Its retention rule moved it to the archive. It is hidden from your normal document lists but "
                                + "still there - ask someone who can see Inactive & Archive if you need it back.")
                        .linkUrl("/documents/" + only.documentId()).objectId(only.documentId())
                        .documentId(only.documentId()).neutralTitle("A document you own was archived")
                        .neutralBody("A retention rule moved a document you own to the archive.");
            } else {
                request.title(n + " of your documents were archived")
                        .body("Their retention rules moved them to the archive. They are hidden from your normal document lists "
                                + "but still there - ask someone who can see Inactive & Archive if you need one back.");
            }
            notificationService.notify(request.build());
        } catch (RuntimeException e) {
            log.warn("Could not tell {} their documents were archived: {}", owner, e.getMessage());
        }
    }

    private void send(UUID tenantId, List<UUID> recipients, String category, String title, String body, String link) {
        notificationService.notify(NotificationRequest.builder().tenantId(tenantId).recipientUserIds(recipients)
                .categoryCode(category).title(title).body(body).linkUrl(link).objectType("tenant").objectId(tenantId)
                .sourceModule("documents").dedupeKey(null).build());
    }

    /** Active users who may review retention - by role permission, or as tenant administrator. */
    List<User> reviewers(UUID tenantId) {
        Map<UUID, User> byId = new LinkedHashMap<>();
        List<UUID> permitted = jdbc.queryForList("""
                select distinct ur.user_id from user_roles ur
                join roles r on r.role_id = ur.role_id and not r.is_deleted and r.status = 'active'
                join role_permissions rp on rp.role_id = r.role_id
                join permissions p on p.permission_id = rp.permission_id
                where ur.tenant_id = ? and p.permission_code = ?
                """, UUID.class, tenantId, "crm.review-retention");
        userRepository.findAllById(permitted).forEach(u -> byId.put(u.getUserId(), u));
        try {
            for (UUID keycloakId : keycloakUserService.listRealmRoleMemberIds("TENANT_ADMIN")) {
                userRepository.findByKeycloakIdAndDeletedFalse(keycloakId)
                        .filter(u -> tenantId.equals(u.getTenantId()))
                        .ifPresent(u -> byId.put(u.getUserId(), u));
            }
        } catch (RuntimeException e) {
            log.warn("Could not list tenant administrators for the retention notification: {}", e.getMessage());
        }
        return byId.values().stream()
                .filter(u -> tenantId.equals(u.getTenantId()) && !u.isDeleted() && u.getStatus() == User.UserStatus.active)
                .toList();
    }
}
