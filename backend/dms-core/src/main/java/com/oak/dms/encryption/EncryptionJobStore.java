package com.oak.dms.encryption;

import com.oak.dms.encryption.EncryptionAdminService.JobStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Key-management job state in encryption_jobs (V58), shared by every
 * dms-core instance: a job is claimed with one conditional upsert (so it
 * runs once per tenant however many instances or clicks), its progress and
 * heartbeat are written as it goes, and it survives restarts. A running job
 * whose heartbeat is older than {@link #STALE} lost its instance: it shows as
 * interrupted and can be started again.
 */
@Component
@RequiredArgsConstructor
public class EncryptionJobStore {

    static final Duration STALE = Duration.ofMinutes(3);

    private final JdbcTemplate jdbc;

    /** @return true when this caller now runs the job; false when it is already running somewhere */
    public boolean claim(UUID tenantId, String job, UUID startedBy) {
        List<Map<String, Object>> claimed = jdbc.queryForList("""
                insert into encryption_jobs (tenant_id, job, status, processed, failed, started_at, finished_at,
                                             heartbeat_at, started_by, instance, last_error)
                values (?, ?, 'running', 0, 0, now(), null, now(), ?, ?, null)
                on conflict (tenant_id, job) do update
                    set status = 'running', processed = 0, failed = 0, started_at = now(), finished_at = null,
                        heartbeat_at = now(), started_by = excluded.started_by, instance = excluded.instance,
                        last_error = null
                    where encryption_jobs.status <> 'running'
                       or encryption_jobs.heartbeat_at < now() - make_interval(secs => ?)
                returning tenant_id
                """, tenantId, job, startedBy, instance(), STALE.toSeconds());
        return !claimed.isEmpty();
    }

    public void progress(UUID tenantId, String job, long processed, long failed, String error) {
        jdbc.update("update encryption_jobs set processed = processed + ?, failed = failed + ?, heartbeat_at = now(), "
                        + "last_error = coalesce(?, last_error) where tenant_id = ? and job = ? and status = 'running'",
                processed, failed, truncate(error), tenantId, job);
    }

    public void heartbeat(UUID tenantId, String job) {
        jdbc.update("update encryption_jobs set heartbeat_at = now() where tenant_id = ? and job = ? "
                + "and status = 'running'", tenantId, job);
    }

    /** @param failed true when the job itself broke off (not merely some items failing) */
    public JobStatus finish(UUID tenantId, String job, boolean failed, String error) {
        jdbc.update("update encryption_jobs set status = ?, finished_at = now(), heartbeat_at = now(), "
                        + "last_error = coalesce(?, last_error) where tenant_id = ? and job = ?",
                failed ? "failed" : "finished", truncate(error), tenantId, job);
        return get(tenantId, job);
    }

    public JobStatus get(UUID tenantId, String job) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select * from encryption_jobs where tenant_id = ? and job = ?", tenantId, job);
        if (rows.isEmpty()) {
            return JobStatus.idle(job);
        }
        Map<String, Object> row = rows.get(0);
        boolean running = "running".equals(row.get("status"));
        OffsetDateTime heartbeat = time(row.get("heartbeat_at"));
        String error = (String) row.get("last_error");
        if (running && heartbeat != null && heartbeat.isBefore(OffsetDateTime.now().minus(STALE))) {
            running = false; // its instance stopped mid-way
            error = "Interrupted - the server running it stopped. Start it again to carry on.";
        }
        return new JobStatus(job, running, ((Number) row.get("processed")).longValue(),
                ((Number) row.get("failed")).longValue(), time(row.get("started_at")), time(row.get("finished_at")),
                error);
    }

    private static OffsetDateTime time(Object value) {
        if (value instanceof OffsetDateTime odt) {
            return odt;
        }
        if (value instanceof Timestamp ts) {
            return ts.toInstant().atOffset(ZoneOffset.UTC);
        }
        return null;
    }

    private static String truncate(String error) {
        return error == null || error.length() <= 1000 ? error : error.substring(0, 997) + "...";
    }

    private static String instance() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown";
        }
    }
}
