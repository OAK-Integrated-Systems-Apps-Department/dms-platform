package com.oak.dms.reporting.run;

import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.catalog.AuditObjectTypes;
import com.oak.dms.audit.enums.AuditOutcome;
import com.oak.dms.audit.service.AuditService;
import com.oak.dms.reporting.entity.ReportRun;
import com.oak.dms.reporting.repository.ReportRunRepository;
import com.oak.dms.reporting.settings.ReportSettingsService;
import com.oak.dms.storage.entity.StorageLocation;
import com.oak.dms.storage.repository.StorageLocationRepository;
import com.oak.dms.storage.service.ObjectStorageServiceFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

/**
 * The housekeeping of report runs, safe to run on every instance at once (each step is a guarded update):
 * <ul>
 *   <li><b>Expiry</b> - a finished file past its {@code expires_at} is deleted from storage and the run becomes {@code expired}.
 *       A run is only marked once its file is gone, so a storage hiccup is retried at the next sweep, not forgotten.</li>
 *   <li><b>Stuck runs</b> - a run still {@code running} well after the run timeout belonged to a process that died; it is
 *       failed with a plain message (the user can retry) rather than left running forever.</li>
 *   <li><b>History</b> - run metadata is kept for {@code dms.reports.run-history-retention} (default 180 days), then deleted.</li>
 * </ul>
 */
@Slf4j
@Component
public class ReportRunSweeper {

    private static final int BATCH = 100;

    private final ReportRunRepository runs;
    private final ReportSettingsService settingsService;
    private final StorageLocationRepository locations;
    private final ObjectStorageServiceFactory storage;
    private final NamedParameterJdbcTemplate jdbc;
    private final AuditService audit;

    public ReportRunSweeper(ReportRunRepository runs, ReportSettingsService settingsService,
                            StorageLocationRepository locations, ObjectStorageServiceFactory storage,
                            NamedParameterJdbcTemplate jdbc, AuditService audit) {
        this.runs = runs;
        this.settingsService = settingsService;
        this.locations = locations;
        this.storage = storage;
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @Scheduled(fixedDelayString = "${dms.reports.sweep-interval:PT10M}", initialDelayString = "${dms.reports.initial-delay:PT20S}")
    public void sweep() {
        try {
            expireFiles();
            failStuckRuns();
            deleteOldHistory();
        } catch (RuntimeException e) {
            log.error("Report run sweep failed", e);
        }
    }

    /** @return how many runs were expired */
    @Transactional
    public int expireFiles() {
        List<ReportRun> due = runs.findByStatusAndExpiresAtBefore(ReportRunStatus.completed, OffsetDateTime.now(),
                PageRequest.of(0, BATCH));
        int expired = 0;
        for (ReportRun run : due) {
            if (run.getFileObjectKey() != null) {
                try {
                    StorageLocation location = locations.findByStorageLocationIdAndDeletedFalse(run.getStorageLocationId()).orElse(null);
                    if (location != null) {
                        storage.resolve(location).delete(location, run.getFileObjectKey()); // a no-op if it is already gone
                    }
                } catch (RuntimeException e) {
                    log.warn("Could not delete the file of report run {}; will try again", run.getRunId(), e);
                    continue;
                }
            }
            expired += jdbc.update("""
                    update report_runs set status = 'expired', file_object_key = null
                    where run_id = :id and status = 'completed'""", new MapSqlParameterSource("id", run.getRunId()));
        }
        if (expired > 0) {
            log.info("Expired {} report file(s)", expired);
        }
        return expired;
    }

    /** @return how many runs were failed */
    @Transactional
    public int failStuckRuns() {
        OffsetDateTime cutoff = OffsetDateTime.now().minus(settingsService.runTimeout().plus(settingsService.stuckRunGrace()));
        int failed = 0;
        for (ReportRun run : runs.findByStatusAndStartedAtBefore(ReportRunStatus.running, cutoff, PageRequest.of(0, BATCH))) {
            int updated = jdbc.update("""
                    update report_runs set status = 'failed', error_code = 'REPORT_INTERRUPTED',
                           error_message = 'The report was interrupted. Run it again.', finished_at = now()
                    where run_id = :id and status = 'running'""", new MapSqlParameterSource("id", run.getRunId()));
            if (updated == 1) {
                failed++;
                log.warn("Report run {} was still running {} after it started; failed as interrupted", run.getRunId(),
                        settingsService.runTimeout().plus(settingsService.stuckRunGrace()));
                audit.event(AuditActionCatalog.REPORT_FAILED)
                        .tenantId(run.getTenantId())
                        .objectType(AuditObjectTypes.REPORT_RUN)
                        .objectId(run.getRunId())
                        .outcome(AuditOutcome.FAILURE)
                        .failureReason("The report was interrupted.")
                        .newValue(Map.of("errorCode", "REPORT_INTERRUPTED", "format", run.getFormat(),
                                "trigger", run.getTriggerType()))
                        .log();
            }
        }
        return failed;
    }

    /** @return how many runs were deleted */
    @Transactional
    public int deleteOldHistory() {
        OffsetDateTime cutoff = OffsetDateTime.now().minus(settingsService.runHistoryRetention());
        return runs.deleteHistoryBefore(cutoff, EnumSet.of(ReportRunStatus.completed, ReportRunStatus.failed,
                ReportRunStatus.cancelled, ReportRunStatus.expired, ReportRunStatus.skipped));
    }
}
