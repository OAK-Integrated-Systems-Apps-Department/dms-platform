package com.oak.dms.scanning.service.impl;

import com.oak.dms.common.exception.KeycloakIntegrationException;
import com.oak.dms.scanning.dto.ThreatQuarantinedEvent;
import com.oak.dms.scanning.entity.QuarantineRecord;
import com.oak.dms.scanning.entity.ScanResult;
import com.oak.dms.scanning.helper.ScanProperties;
import com.oak.dms.scanning.helper.ThreatNotificationMailer;
import com.oak.dms.scanning.repository.QuarantineRecordRepository;
import com.oak.dms.scanning.repository.ScanResultRepository;
import com.oak.dms.security.keycloak.KeycloakUserService;
import com.oak.dms.security.rbac.catalog.PermissionCodes;
import com.oak.dms.security.rbac.repository.RolePermissionRepository;
import com.oak.dms.user.User;
import com.oak.dms.user.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Emails the uploader and the relevant administrators when a threat is
 * quarantined (VMSBDI FR-003).
 * <ul>
 *   <li><b>After commit only</b> - {@link TransactionalEventListener} with
 *   {@code AFTER_COMMIT}: fires only once the quarantine is durable, and can
 *   never roll it back.</li>
 *   <li><b>Off the upload thread</b> - handed to Spring Boot's
 *   {@code applicationTaskExecutor}, so SMTP or Keycloak latency never delays
 *   the upload response or the retry scheduler.</li>
 *   <li><b>Never fails anything</b> - every error is caught and logged. A mail
 *   lost this way (or to a crash) is not retried: the quarantine record, its
 *   audit events and the review screen are the durable record.</li>
 * </ul>
 * "Relevant administrators" = the tenant's active users holding
 * {@code docm.quarantine.view} through a DB role, plus the tenant's
 * {@code TENANT_ADMIN} users (who hold every permission implicitly -
 * {@code AuthorizationService#hasFullAccess}), i.e. exactly who can act on
 * the quarantined file. A Keycloak failure only drops the second group.
 */
@Slf4j
@Component
public class ThreatNotificationListener {

    private static final String TENANT_ADMIN_ROLE = "TENANT_ADMIN";

    private final QuarantineRecordRepository quarantineRecordRepository;
    private final ScanResultRepository scanResultRepository;
    private final UserRepository userRepository;
    private final RolePermissionRepository rolePermissionRepository;
    private final KeycloakUserService keycloakUserService;
    private final ThreatNotificationMailer mailer;
    private final ScanProperties scanProperties;
    private final TaskExecutor taskExecutor;

    public ThreatNotificationListener(QuarantineRecordRepository quarantineRecordRepository,
                                      ScanResultRepository scanResultRepository,
                                      UserRepository userRepository,
                                      RolePermissionRepository rolePermissionRepository,
                                      KeycloakUserService keycloakUserService,
                                      ThreatNotificationMailer mailer,
                                      ScanProperties scanProperties,
                                      @Qualifier("applicationTaskExecutor") TaskExecutor taskExecutor) {
        this.quarantineRecordRepository = quarantineRecordRepository;
        this.scanResultRepository = scanResultRepository;
        this.userRepository = userRepository;
        this.rolePermissionRepository = rolePermissionRepository;
        this.keycloakUserService = keycloakUserService;
        this.mailer = mailer;
        this.scanProperties = scanProperties;
        this.taskExecutor = taskExecutor;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onThreatQuarantined(ThreatQuarantinedEvent event) {
        if (!scanProperties.getNotifications().isEnabled()) {
            return;
        }
        try {
            taskExecutor.execute(() -> notifySafely(event));
        } catch (RuntimeException e) {
            log.warn("Could not schedule threat notification for quarantine record {}: {}",
                    event.quarantineId(), e.getMessage());
        }
    }

    private void notifySafely(ThreatQuarantinedEvent event) {
        try {
            notify(event);
        } catch (RuntimeException e) {
            log.warn("Threat notification for quarantine record {} failed: {}", event.quarantineId(), e.getMessage());
        }
    }

    private void notify(ThreatQuarantinedEvent event) {
        QuarantineRecord record = quarantineRecordRepository.findById(event.quarantineId()).orElse(null);
        ScanResult scan = scanResultRepository.findById(event.scanResultId()).orElse(null);
        if (record == null || scan == null) {
            return;
        }
        User uploader = activeUser(scan.getUploadedBy(), event.tenantId());

        if (uploader != null && hasEmail(uploader)) {
            send(() -> mailer.sendToUploader(uploader, record), "uploader", uploader, record);
        }

        for (User admin : administrators(event.tenantId())) {
            if (uploader != null && admin.getUserId().equals(uploader.getUserId())) {
                continue; // already told, as the uploader
            }
            send(() -> mailer.sendToAdministrator(admin, record, scan, uploader), "administrator", admin, record);
        }
    }

    /** De-duplicated, in-tenant, active users with an email address. */
    private List<User> administrators(UUID tenantId) {
        Set<UUID> userIds = new LinkedHashSet<>(
                rolePermissionRepository.findUserIdsWithPermission(tenantId, PermissionCodes.DOCM_QUARANTINE_VIEW));
        Map<UUID, User> admins = new LinkedHashMap<>();
        userRepository.findAllById(userIds).forEach(user -> admins.put(user.getUserId(), user));

        try {
            for (UUID keycloakId : keycloakUserService.listRealmRoleMemberIds(TENANT_ADMIN_ROLE)) {
                userRepository.findByKeycloakIdAndDeletedFalse(keycloakId)
                        .filter(user -> tenantId.equals(user.getTenantId()))
                        .ifPresent(user -> admins.putIfAbsent(user.getUserId(), user));
            }
        } catch (KeycloakIntegrationException e) {
            log.warn("Could not list tenant admins from Keycloak for threat notification - notifying permission "
                    + "holders only: {}", e.getMessage());
        }

        return admins.values().stream()
                .filter(user -> tenantId.equals(user.getTenantId()))
                .filter(ThreatNotificationListener::isActive)
                .filter(ThreatNotificationListener::hasEmail)
                .toList();
    }

    private User activeUser(UUID userId, UUID tenantId) {
        if (userId == null) {
            return null;
        }
        return userRepository.findByUserIdAndTenantIdAndDeletedFalse(userId, tenantId)
                .filter(ThreatNotificationListener::isActive)
                .orElse(null);
    }

    private static void send(Runnable sender, String role, User to, QuarantineRecord record) {
        try {
            sender.run();
            log.info("Threat notification for quarantine record {} sent to {} {}", record.getQuarantineId(), role,
                    to.getUserId());
        } catch (RuntimeException e) {
            // One bad address or SMTP hiccup must not stop the other recipients.
            log.warn("Threat notification for quarantine record {} to {} {} failed: {}", record.getQuarantineId(),
                    role, to.getUserId(), e.getMessage());
        }
    }

    private static boolean isActive(User user) {
        return !user.isDeleted() && user.getStatus() == User.UserStatus.active;
    }

    private static boolean hasEmail(User user) {
        return user.getEmail() != null && !user.getEmail().isBlank();
    }
}
