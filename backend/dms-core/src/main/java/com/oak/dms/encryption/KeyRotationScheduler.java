package com.oak.dms.encryption;

import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.catalog.AuditObjectTypes;
import com.oak.dms.audit.enums.AuditOutcome;
import com.oak.dms.audit.service.AuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The master-key rotation schedule (DE-FR-005). Every
 * {@code dms.encryption.rotation.check-interval}, for each active tenant with
 * encrypted objects whose key is due: with auto-rotate on, the key is rotated
 * and its data keys re-wrapped onto the new version; otherwise an
 * ENCRYPTION_KEY_ROTATION_DUE audit event is the reminder (again every
 * {@code remind-every} until someone rotates), and the key screen shows the key
 * as due.
 * <p>
 * Runs on one instance at a time: the check holds a Postgres advisory lock,
 * and an instance that can't take it skips this round. Reminders are claimed
 * in the database too, so each is sent once.
 * <p>
 * A reminder is an audit event, the "due" banner on the key screen, and an
 * email to the people who can rotate it ({@link KeyRotationReminderMailer}).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KeyRotationScheduler {

    /** Arbitrary, fixed: "dms-key-rotation-check". */
    static final long LOCK_KEY = 0x646d_735f_6b72_6f74L;

    private final KeyRotationPolicyStore policies;
    private final EncryptionAdminService admin;
    private final EncryptionProperties properties;
    private final AuditService auditService;
    private final JdbcTemplate jdbc;
    private final KeyRotationReminderMailer mailer;

    @Scheduled(fixedDelayString = "${dms.encryption.rotation.check-interval:PT6H}", initialDelayString = "PT2M")
    public void checkSchedule() {
        // A session lock on a connection of its own, not a transaction: the work below (rotation,
        // audit events) commits as it goes, on other connections.
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            if (!lock(connection, "select pg_try_advisory_lock(?)")) {
                return null; // another instance is checking right now
            }
            try {
                for (UUID tenantId : policies.tenantsToCheck()) {
                    try {
                        check(tenantId);
                    } catch (RuntimeException e) {
                        log.warn("Key rotation check for tenant {} failed: {}", tenantId, e.getMessage());
                    }
                }
            } finally {
                lock(connection, "select pg_advisory_unlock(?)");
            }
            return null;
        });
    }

    private static boolean lock(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, LOCK_KEY);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getBoolean(1);
            }
        }
    }

    /** One tenant: rotate it, remind about it, or nothing. Package-visible for tests. */
    void check(UUID tenantId) {
        KeyRotationPolicyStore.RotationPolicy policy = policies.get(tenantId);
        if (!policy.due()) {
            return;
        }
        if (policy.autoRotate()) {
            KeyEncryptionService.KeyInfo key = admin.rotate(tenantId, "schedule");
            boolean rewrapping = admin.startRewrapFor(tenantId);
            log.info("Rotated tenant {}'s master key to version {} on schedule (re-wrap {})", tenantId,
                    key.latestVersion(), rewrapping ? "started" : "already running");
            return;
        }
        if (policies.claimReminder(tenantId, properties.getRotation().getRemindEvery())) {
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("intervalDays", policy.intervalDays());
            details.put("keySince", String.valueOf(policy.keySince()));
            details.put("dueAt", String.valueOf(policy.nextDueAt()));
            auditService.event(AuditActionCatalog.ENCRYPTION_KEY_ROTATION_DUE)
                    .tenantId(tenantId)
                    .systemActor()
                    .objectType(AuditObjectTypes.ENCRYPTION_KEY)
                    .resourceName(KeyEncryptionService.scopeOf(tenantId))
                    .outcome(AuditOutcome.SUCCESS)
                    .newValue(details)
                    .log();
            int emailed = mailer.send(tenantId, policy.nextDueAt(), policy.intervalDays());
            log.info("Key rotation for tenant {} is due - reminder emailed to {} people", tenantId, emailed);
        }
    }
}
