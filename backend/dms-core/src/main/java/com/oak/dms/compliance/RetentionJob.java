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
 * The recurring retention job (CRM-NFR-002): every active organisation that has not switched the automation off is processed on
 * each run, so no document misses its transition. Most runs are a quick pass over the documents that are due; a full pass happens
 * when a rule changed, and at least daily (see {@link RetentionEngine}). One server at a time (a Postgres session lock, like the
 * recycle-bin sweep); a failure is logged and retried on the next run. An organisation with no rules has its documents flagged
 * "no rule" for an administrator to deal with - or is left alone, if its setting says so.
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

    /**
     * One organisation as the scheduled job would run it (the same switches: automation off, suspended organisation), without the
     * lock - for tests and for operators. Returns null when the organisation is skipped.
     */
    public RetentionEngine.Result runFor(UUID tenantId) {
        if (settingsRepository.tenantsWithAutomationOff().contains(tenantId) || organisationStatus.inactiveReason(tenantId).isPresent()) {
            return null;
        }
        return engine.runScheduled(tenantId);
    }

    /** All organisations, or nothing at all when another server is already doing it. */
    public RunSummary runAll() {
        return jdbc.execute((ConnectionCallback<RunSummary>) connection -> {
            if (!lock(connection, "select pg_try_advisory_lock(?)")) {
                return null;
            }
            try {
                Set<UUID> off = Set.copyOf(settingsRepository.tenantsWithAutomationOff());
                // Every organisation: one with rules gets them applied, one without has its documents flagged "no rule" (or
                // nothing, by its own setting) - see RetentionEngine.
                List<UUID> tenants = jdbc.queryForList("select tenant_id from tenants where not is_deleted and status = 'active'", UUID.class);
                int organisations = 0;
                int[] total = new int[6];
                for (UUID tenantId : tenants) {
                    if (off.contains(tenantId) || organisationStatus.inactiveReason(tenantId).isPresent()) {
                        continue;
                    }
                    try {
                        RetentionEngine.Result r = engine.runScheduled(tenantId);
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
