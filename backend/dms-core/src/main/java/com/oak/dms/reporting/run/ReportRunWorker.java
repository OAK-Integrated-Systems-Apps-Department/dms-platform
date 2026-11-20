package com.oak.dms.reporting.run;

import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.catalog.AuditObjectTypes;
import com.oak.dms.audit.enums.AuditOutcome;
import com.oak.dms.audit.service.AuditService;
import com.oak.dms.common.io.TempFiles;
import com.oak.dms.folder.DocumentAccessService;
import com.oak.dms.folder.DocumentAccessService.DocumentAccessView;
import com.oak.dms.reporting.ReportException;
import com.oak.dms.reporting.ReportMetrics;
import com.oak.dms.reporting.datasource.DocumentReportDataSource;
import com.oak.dms.reporting.datasource.ReadOnlySnapshot;
import com.oak.dms.reporting.datasource.ReportContext;
import com.oak.dms.reporting.datasource.ReportQuery;
import com.oak.dms.reporting.entity.ReportRun;
import com.oak.dms.reporting.export.ReportExporter;
import com.oak.dms.reporting.export.ReportFileSpec;
import com.oak.dms.reporting.format.ReportValueFormatter;
import com.oak.dms.reporting.repository.ReportRunRepository;
import com.oak.dms.reporting.settings.EffectiveReportSettings;
import com.oak.dms.reporting.settings.ReportSettingsService;
import com.oak.dms.security.auth.JobIdentity;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.storage.entity.StorageLocation;
import com.oak.dms.storage.enums.StoragePurpose;
import com.oak.dms.storage.service.ObjectStorageServiceFactory;
import com.oak.dms.storage.service.StorageLocationResolver;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.task.TaskExecutor;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Produces report files in the background, modelled on {@code ExportJobWorker}: each run is bound to the person it is for (their
 * access as it is NOW, not when they asked), runs on the application task executor with {@code TenantContext} set for the length of
 * the job and cleared in {@code finally}, and writes through a temporary file that is always deleted.
 * <p>
 * Safe with several application instances: a run is claimed with one guarded {@code UPDATE ... WHERE status = 'queued'} taken under a
 * per-organisation advisory lock, so two instances cannot both start the run, nor push an organisation past its concurrency limit.
 * A run that cannot start yet (its organisation is at the limit) simply stays queued; {@link #pollQueued} looks again.
 * Every status change after the claim is a guarded update too, so a cancel that arrives mid-run always wins over completion.
 */
@Slf4j
@Component
public class ReportRunWorker {

    static final String FAILED_MESSAGE = "The report could not be produced. Try again, or narrow the filters.";
    private static final int POLL_BATCH = 50;

    private final ReportRunRepository runs;
    private final ReportSettingsService settingsService;
    private final JobIdentity jobIdentity;
    private final DocumentAccessService documentAccess;
    private final DocumentReportDataSource dataSource;
    private final ReportGenerator generator;
    private final Map<String, ReportExporter> exporters;
    private final StorageLocationResolver locations;
    private final ObjectStorageServiceFactory storage;
    private final AuditService audit;
    private final ReportMetrics metrics;
    private final ReportRunStateMachine machine;
    private final NamedParameterJdbcTemplate jdbc;
    private final TaskExecutor taskExecutor;
    /** Its own transaction, so a cancel or a progress write is visible/committed whatever the report's snapshot sees. */
    private final TransactionTemplate independent;
    private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();
    private final ApplicationEventPublisher events;

    public ReportRunWorker(ReportRunRepository runs, ReportSettingsService settingsService, JobIdentity jobIdentity,
                           DocumentAccessService documentAccess, DocumentReportDataSource dataSource, ReportGenerator generator,
                           List<ReportExporter> exporters, StorageLocationResolver locations,
                           ObjectStorageServiceFactory storage, AuditService audit, ReportMetrics metrics,
                           ReportRunStateMachine machine, NamedParameterJdbcTemplate jdbc,
                           @Qualifier("applicationTaskExecutor") TaskExecutor taskExecutor,
                           PlatformTransactionManager transactions, ApplicationEventPublisher events) {
        this.events = events;
        this.runs = runs;
        this.settingsService = settingsService;
        this.jobIdentity = jobIdentity;
        this.documentAccess = documentAccess;
        this.dataSource = dataSource;
        this.generator = generator;
        this.exporters = exporters.stream().collect(Collectors.toMap(ReportExporter::format, Function.identity()));
        this.locations = locations;
        this.storage = storage;
        this.audit = audit;
        this.metrics = metrics;
        this.machine = machine;
        this.jdbc = jdbc;
        this.taskExecutor = taskExecutor;
        this.independent = new TransactionTemplate(transactions);
        this.independent.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Queues the run; returns at once. Call only once the run row is committed. */
    public void start(UUID runId) {
        if (!inFlight.add(runId)) {
            return;
        }
        try {
            taskExecutor.execute(() -> {
                try {
                    run(runId);
                } finally {
                    inFlight.remove(runId);
                }
            });
        } catch (RuntimeException e) {
            inFlight.remove(runId);
            log.error("Could not queue report run {}", runId, e);
        }
    }

    /** Starts runs that were waiting (their organisation was at its limit) or were queued by another instance or before a restart. */
    @Scheduled(fixedDelayString = "${dms.reports.queue-poll:PT5S}", initialDelayString = "${dms.reports.initial-delay:PT20S}")
    public void pollQueued() {
        try {
            runs.findByStatusOrderByCreatedAtAsc(ReportRunStatus.queued, PageRequest.of(0, POLL_BATCH))
                    .forEach(r -> start(r.getRunId()));
        } catch (RuntimeException e) {
            log.error("Could not look for queued report runs", e);
        }
    }

    void run(UUID runId) {
        ReportRun run = runs.findById(runId).orElse(null);
        if (run == null || run.getStatus() != ReportRunStatus.queued) {
            return;
        }
        Instant started = Instant.now();
        Path temp = null;
        String outcome = "failed";
        try {
            // As the person the run is for, with their access as it is now - not as it was when they asked.
            if (!jobIdentity.bind(run.getTenantId(), run.getRequestedBy())) {
                fail(run, "REPORT_REQUESTER_INACTIVE", "The person who requested this report is no longer an active user.");
                return;
            }
            EffectiveReportSettings settings = settingsService.forTenant(run.getTenantId());
            if (!settings.enabled()) {
                fail(run, "REPORTS_DISABLED", "Reports are turned off for your organisation.");
                return;
            }
            ReportExporter exporter = exporters.get(run.getFormat());
            if (exporter == null || !settings.allows(run.getFormat())) {
                fail(run, "REPORT_FORMAT_NOT_ALLOWED", "That file format is not available for your organisation.");
                return;
            }
            if (!claim(run, settings.maxConcurrentRuns())) {
                outcome = "waiting";
                return; // stays queued; the poller starts it when the organisation has room
            }

            RunSnapshot snapshot = RunSnapshot.from(run.getDefinitionSnapshot());
            ZoneId zone = ZoneId.of(snapshot.zone());
            Instant now = Instant.now();
            UUID forUser = ReportRun.SCOPE_RECIPIENT.equals(run.getDeliveryScope()) ? run.getForUserId() : run.getRequestedBy();
            DocumentAccessView access = ReportRun.SCOPE_RECIPIENT.equals(run.getDeliveryScope())
                    ? documentAccess.forUser(run.getTenantId(), forUser) : documentAccess.forCaller();
            ReportContext context = new ReportContext(run.getTenantId(), forUser, access, settings, zone, now);
            ReportQuery query = dataSource.plan(snapshot.definition(), context);

            temp = TempFiles.create("dms-report-", "." + exporter.extension());
            ReportValueFormatter formatter = new ReportValueFormatter(settings, zone);
            String requestedBy = TenantContext.getDisplayNameOrNull();
            boolean truncate = settings.truncateOnOverLimit() && !"pdf".equals(run.getFormat());
            UUID id = run.getRunId();
            ReportGenerator.Outcome result = generator.generate(query, context, exporter, temp,
                    settings.rowLimitFor(run.getFormat()), truncate, settingsService.runTimeout(),
                    rows -> independent.executeWithoutResult(s -> runs.updateProgress(id, rows)),
                    // not running any more (cancelled, or failed by the sweeper): stop writing a file nobody is waiting for
                    () -> independent.execute(s -> runs.statusOf(id).orElse(ReportRunStatus.cancelled)) != ReportRunStatus.running,
                    (rowCount, truncated, limit, groups) -> new ReportFileSpec(
                            snapshot.title() == null ? "Custom report" : snapshot.title(), now.atOffset(ZoneOffset.UTC),
                            requestedBy, query.resolvedFilters(), rowCount, truncated, limit, query.columns(),
                            query.groupColumn(), groups, formatter, settings));

            if (result.empty()) {
                outcome = "empty";
                complete(run, 0, false, null, null, 0, settings);
                return;
            }
            StorageLocation location = locations.resolveForTenant(run.getTenantId(), StoragePurpose.staging);
            String key = run.getTenantId() + "/tmp/reports/" + run.getRunId() + "/report." + exporter.extension();
            long size = Files.size(temp);
            try (InputStream content = Files.newInputStream(temp)) {
                storage.resolve(location).upload(location, key, content, size, exporter.contentType());
            }
            if (complete(run, result.written(), result.truncated(), location, key, size, settings)) {
                outcome = "completed";
                metrics.rows(run.getFormat(), result.written());
                audit(run, AuditOutcome.SUCCESS, null, Map.of("rowCount", result.written(), "truncated", result.truncated(),
                        "fileSizeBytes", size, "durationMs", Duration.between(started, Instant.now()).toMillis()));
            } else {
                outcome = "cancelled"; // cancelled while the file was being stored: the file is not wanted
                storage.resolve(location).delete(location, key);
            }
        } catch (ReportGenerator.RunCancelledException e) {
            outcome = "cancelled";
            log.info("Report run {} was cancelled while running", runId);
        } catch (ReportException e) {
            fail(run, e.getCode(), e.getMessage());
        } catch (RuntimeException | IOException e) {
            if (ReadOnlySnapshot.isTimeout(e)) {
                fail(run, "REPORT_TIMEOUT", "The report took too long to produce. Narrow the filters and try again.");
            } else {
                log.error("Report run {} failed", runId, e); // the full cause stays in the log; the user gets the plain message
                fail(run, "REPORT_FAILED", FAILED_MESSAGE);
            }
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException e) {
                    log.warn("Could not delete report temp file {}", temp, e);
                }
            }
            TenantContext.clear();
            if (!"waiting".equals(outcome)) {
                metrics.run(Duration.between(started, Instant.now()), outcome, run.getFormat());
            }
            announceFinished(run, outcome);
        }
    }

    /**
     * Tells delivery a scheduled run has ended (completed, failed, or never started) - not one that was cancelled or is still waiting.
     * A listener's failure is logged and goes no further: the run is already recorded, and the delivery poller catches what this misses.
     */
    private void announceFinished(ReportRun run, String outcome) {
        if (run.getScheduleId() == null || "waiting".equals(outcome) || "cancelled".equals(outcome)) {
            return;
        }
        try {
            events.publishEvent(new ReportRunFinished(run.getRunId()));
        } catch (RuntimeException e) {
            log.error("Could not announce that report run {} finished", run.getRunId(), e);
        }
    }

    /**
     * queued -> running, once, and only if the organisation has room. The advisory lock is per organisation and ends with the
     * statement's transaction, so concurrent claims for one organisation take turns and each sees the others' result.
     */
    private boolean claim(ReportRun run, int concurrencyLimit) {
        machine.transition(ReportRunStatus.queued, ReportRunStatus.running);
        Integer updated = independent.execute(s -> {
            jdbc.queryForList("select pg_advisory_xact_lock(hashtext(:tenant))",
                    new MapSqlParameterSource("tenant", run.getTenantId().toString()));
            return jdbc.update("""
                    update report_runs set status = 'running', started_at = now(), progress_rows = 0
                    where run_id = :id and status = 'queued'
                      and (select count(*) from report_runs where tenant_id = :tenant and status = 'running') < :limit""",
                    new MapSqlParameterSource("id", run.getRunId()).addValue("tenant", run.getTenantId())
                            .addValue("limit", concurrencyLimit));
        });
        return updated != null && updated == 1;
    }

    /** running -> completed, unless the run was cancelled meanwhile. @return false when it was not running any more */
    private boolean complete(ReportRun run, long rows, boolean truncated, StorageLocation location, String key, long size,
                             EffectiveReportSettings settings) {
        machine.transition(ReportRunStatus.running, ReportRunStatus.completed);
        OffsetDateTime now = OffsetDateTime.now();
        int updated = jdbc.update("""
                update report_runs set status = 'completed', row_count = :rows, truncated = :truncated, progress_rows = :rows,
                       file_object_key = :key, file_size_bytes = :size, storage_location_id = :location,
                       finished_at = :now, expires_at = :expires
                where run_id = :id and status = 'running'""",
                new MapSqlParameterSource("rows", rows).addValue("truncated", truncated).addValue("key", key)
                        .addValue("size", key == null ? null : size)
                        .addValue("location", location == null ? null : location.getStorageLocationId())
                        .addValue("now", now)
                        .addValue("expires", key == null ? null : now.plusDays(settings.fileRetentionDays()))
                        .addValue("id", run.getRunId()));
        if (updated == 1 && key == null) {
            audit(run, AuditOutcome.SUCCESS, null, Map.of("rowCount", 0, "truncated", false, "fileSizeBytes", 0));
        }
        return updated == 1;
    }

    /** queued|running -> failed, unless it already finished some other way. */
    private void fail(ReportRun run, String code, String message) {
        int updated = jdbc.update("""
                update report_runs set status = 'failed', error_code = :code, error_message = :message, finished_at = now()
                where run_id = :id and status in ('queued', 'running')""",
                new MapSqlParameterSource("code", code).addValue("message", truncate(message, 500)).addValue("id", run.getRunId()));
        if (updated == 1) {
            audit(run, AuditOutcome.FAILURE, message, Map.of("errorCode", code));
        }
    }

    private void audit(ReportRun run, AuditOutcome outcome, String failureReason, Map<String, Object> details) {
        Map<String, Object> payload = new LinkedHashMap<>(details);
        payload.put("format", run.getFormat());
        payload.put("trigger", run.getTriggerType());
        var event = audit.event(outcome == AuditOutcome.SUCCESS ? AuditActionCatalog.REPORT_GENERATED : AuditActionCatalog.REPORT_FAILED)
                .tenantId(run.getTenantId())
                .objectType(AuditObjectTypes.REPORT_RUN)
                .objectId(run.getRunId())
                .outcome(outcome)
                .newValue(payload);
        if (failureReason != null) {
            event.failureReason(truncate(failureReason, 500));
        }
        event.log();
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
