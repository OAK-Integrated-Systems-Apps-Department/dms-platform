package com.oak.dms.compliance;

import com.oak.dms.security.auth.OrganisationStatusGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The recurring retention job (CRM-NFR-002): every organisation that has at least one enabled retention rule and has not
 * switched the automation off is processed on each run, so no document misses its transition. One server at a time (a
 * Postgres session lock, like the recycle-bin sweep); a failure is logged and retried on the next run. An organisation
 * with no rules at all is left completely alone - nothing is flagged until someone has set retention up.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class RetentionJob {

    static final long LOCK_KEY = 0x646d_735f_7265_7465L;

    private final JdbcTemplate jdbc;
    private final RetentionEngine engine;
    private final TenantRetentionSettingsRepository settingsRepository;
    private final OrganisationStatusGuard organisationStatus;

    public record RunSummary(java.time.OffsetDateTime at, int organisations, int archived, int flagged, int withoutPolicy,
                             int versionsRemoved, int versionsDestroyed, int failed) {
    }

    @Scheduled(fixedDelayString = "${dms.retention.interval:PT1H}", initialDelayString = "${dms.retention.initial-delay:PT3M}")
    public void scheduled() {
        runAll();
    }

    /** All organisations, or nothing at all when another server is already doing it. */
    public RunSummary runAll() {
        return jdbc.execute((ConnectionCallback<RunSummary>) connection -> {
            if (!lock(connection, "select pg_try_advisory_lock(?)")) {
                return null;
            }
            try {
                Set<UUID> off = Set.copyOf(settingsRepository.tenantsWithAutomationOff());
                List<UUID> tenants = jdbc.queryForList("""
                        select distinct tenant_id from retention_policies where not is_deleted and enabled
                        """, UUID.class);
                int organisations = 0;
                int[] total = new int[6];
                for (UUID tenantId : tenants) {
                    if (off.contains(tenantId) || organisationStatus.inactiveReason(tenantId).isPresent()) {
                        continue;
                    }
                    try {
                        RetentionEngine.Result r = engine.runForTenant(tenantId);
                        organisations++;
                        total[0] += r.archived();
                        total[1] += r.flagged();
                        total[2] += r.withoutPolicy();
                        total[3] += r.versionsRemoved();
                        total[4] += r.versionsDestroyed();
                        total[5] += r.failed();
                    } catch (RuntimeException e) {
                        total[5]++;
                        log.warn("Retention run failed for organisation {} - will try again next run: {}", tenantId, e.getMessage());
                    }
                }
                RunSummary summary = new RunSummary(java.time.OffsetDateTime.now(), organisations, total[0], total[1], total[2], total[3],
                        total[4], total[5]);
                if (total[0] + total[1] + total[2] + total[3] + total[4] + total[5] > 0) {
                    log.info("Retention run: {} organisation(s), {} archived, {} flagged for review, {} without a rule, "
                                    + "{} old version(s) removed, {} destroyed, {} failed",
                            organisations, total[0], total[1], total[2], total[3], total[4], total[5]);
                }
                return summary;
            } finally {
                lock(connection, "select pg_advisory_unlock(?)");
            }
        });
    }

    private static boolean lock(java.sql.Connection connection, String sql) throws java.sql.SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, LOCK_KEY);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getBoolean(1);
            }
        }
    }
}
