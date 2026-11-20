package com.oak.dms.reporting.delivery;

import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.catalog.AuditObjectTypes;
import com.oak.dms.audit.enums.AuditOutcome;
import com.oak.dms.audit.service.AuditService;
import com.oak.dms.reporting.delivery.ReportDeliveryChannel.Delivery;
import com.oak.dms.reporting.delivery.ReportDeliveryChannel.Outcome;
import com.oak.dms.reporting.delivery.ReportDeliveryChannel.Target;
import com.oak.dms.reporting.dto.ScheduleRequest.Recipient;
import com.oak.dms.reporting.entity.ReportRun;
import com.oak.dms.reporting.entity.ReportSchedule;
import com.oak.dms.reporting.repository.ReportRunRepository;
import com.oak.dms.reporting.repository.ReportScheduleRepository;
import com.oak.dms.reporting.run.ReportRunFinished;
import com.oak.dms.reporting.run.ReportRunStatus;
import com.oak.dms.reporting.run.RunSnapshot;
import com.oak.dms.reporting.schedule.ReportPeople;
import com.oak.dms.reporting.schedule.ReportRecipientStore;
import com.oak.dms.reporting.schedule.ReportScheduleDispatcher;
import com.oak.dms.reporting.settings.EffectiveReportSettings;
import com.oak.dms.reporting.settings.ReportSettingsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Hands the finished file of a scheduled run to the people it is for, through the channels the schedule chose.
 * <p>
 * Two steps, each its own transaction, so a crash between them loses nothing:
 * <ol>
 *   <li><b>Prepare</b> - claim the run ({@code delivered} is set once, so two servers cannot both prepare it), then write one
 *       {@code pending} delivery row per person and channel, or note why there is nothing to deliver (the run failed; the report was
 *       empty and the organisation skips those).</li>
 *   <li><b>Attempt</b> - each pending row is taken with {@code FOR UPDATE SKIP LOCKED} and handed to its channel. Success marks it
 *       sent; a failure that may pass (the mail server was down) is tried again after 1, then 5, then 15 minutes; one that cannot
 *       (no address, attachment too large) fails at once. A bad row fails that person, never the run or the others.</li>
 * </ol>
 * When the last row of a run settles with failures, the owner gets one summary. Everything is recorded by hash or user id - never an
 * address - and audited as {@code REPORT_DELIVERED} without row content or filter values.
 */
@Slf4j
@Service
public class ReportDeliveryService {

    /** Minutes to wait after the first, second and third failed attempt; a fourth failure is final. */
    static final int[] BACKOFF_MINUTES = {1, 5, 15};
    private static final int POLL_BATCH = 50;

    private final NamedParameterJdbcTemplate jdbc;
    private final ReportRunRepository runs;
    private final ReportScheduleRepository schedules;
    private final ReportRecipientStore recipientStore;
    private final ReportPeople people;
    private final ReportSettingsService settingsService;
    private final Map<String, ReportDeliveryChannel> channels;
    private final ReportNotifier notifier;
    private final ReportScheduleDispatcher dispatcher;
    private final AuditService audit;
    private final TransactionTemplate transaction;

    public ReportDeliveryService(NamedParameterJdbcTemplate jdbc, ReportRunRepository runs, ReportScheduleRepository schedules,
                                 ReportRecipientStore recipientStore, ReportPeople people, ReportSettingsService settingsService,
                                 List<ReportDeliveryChannel> channels, ReportNotifier notifier, ReportScheduleDispatcher dispatcher,
                                 AuditService audit, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.runs = runs;
        this.schedules = schedules;
        this.recipientStore = recipientStore;
        this.people = people;
        this.settingsService = settingsService;
        this.channels = channels.stream().collect(Collectors.toMap(ReportDeliveryChannel::channel, Function.identity()));
        this.notifier = notifier;
        this.dispatcher = dispatcher;
        this.audit = audit;
        this.transaction = new TransactionTemplate(transactions);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // ---------------------------------------------------------------- entry points

    /** A scheduled run finished: prepare its deliveries and try them now. Never throws - the poller picks up whatever this misses. */
    @EventListener
    public void onFinished(ReportRunFinished event) {
        try {
            prepare(event.runId());
            attemptRun(event.runId());
        } catch (RuntimeException e) {
            log.error("Could not deliver report run {}; the poller will try again", event.runId(), e);
        }
    }

    /** Picks up runs whose server died before preparing them, and deliveries whose retry time has come. */
    @Scheduled(fixedDelayString = "${dms.reports.delivery-poll:PT30S}", initialDelayString = "${dms.reports.initial-delay:PT20S}")
    public void poll() {
        try {
            for (UUID runId : jdbc.queryForList("""
                    select run_id from report_runs
                    where schedule_id is not null and status in ('completed', 'failed') and delivered is null
                      and finished_at > now() - interval '1 day'
                    order by finished_at limit :batch""", new MapSqlParameterSource("batch", POLL_BATCH), UUID.class)) {
                prepare(runId);
            }
            for (UUID id : dueDeliveries(null)) {
                attempt(id);
            }
        } catch (RuntimeException e) {
            log.error("Could not work through pending report deliveries", e);
        }
    }

    // ---------------------------------------------------------------- 1. prepare

    /** @return true if this call prepared the run; false if it was not a scheduled finished run, or was already prepared */
    public boolean prepare(UUID runId) {
        Boolean prepared = transaction.execute(status -> doPrepare(runId));
        return Boolean.TRUE.equals(prepared);
    }

    private boolean doPrepare(UUID runId) {
        ReportRun run = runs.findById(runId).orElse(null);
        if (run == null || run.getScheduleId() == null
                || (run.getStatus() != ReportRunStatus.completed && run.getStatus() != ReportRunStatus.failed)) {
            return false;
        }
        // Claimed exactly once: whoever flips it from null owns the preparation.
        int claimed = jdbc.update("update report_runs set delivered = true where run_id = :id and delivered is null",
                new MapSqlParameterSource("id", runId));
        if (claimed == 0) {
            return false;
        }
        ReportSchedule schedule = schedules.findById(run.getScheduleId()).orElse(null);
        if (schedule == null) {
            return markNotDelivered(runId, "SCHEDULE_GONE");
        }
        EffectiveReportSettings settings = settingsService.forTenant(run.getTenantId());
        String title = titleOf(run, schedule);

        if (run.getStatus() == ReportRunStatus.failed) {
            runFailed(run, schedule, settings, title);
            return markNotDelivered(runId, "RUN_FAILED");
        }
        if (ReportSchedule.CANCELLED.equals(schedule.getStatus())) {
            return markNotDelivered(runId, "SCHEDULE_CANCELLED");
        }
        if (run.getScheduledFor() != null) {
            // A slot that produced a file ends the streak - unless an earlier file of the SAME slot already counted as a failure.
            jdbc.update("""
                    update report_schedules set consecutive_failures = 0, last_failure_code = null
                    where schedule_id = :id and consecutive_failures > 0 and (last_failure_slot is null or last_failure_slot < :slot)""",
                    new MapSqlParameterSource("id", schedule.getScheduleId()).addValue("slot", run.getScheduledFor()));
        }
        boolean empty = run.getFileObjectKey() == null;
        if (empty && settings.skipEmptyScheduledReports()) {
            return markNotDelivered(runId, "EMPTY");
        }
        int rows = createRows(run, schedule, settings);
        if (rows == 0) {
            return markNotDelivered(runId, "NO_TARGET");
        }
        return true;
    }

    private void runFailed(ReportRun run, ReportSchedule schedule, EffectiveReportSettings settings, String title) {
        Set<UUID> tell = new LinkedHashSet<>();
        tell.add(schedule.getOwnerUserId());
        if (settings.failureNotifyAdmins()) {
            tell.addAll(settings.failureAdminUserIds());
        }
        String reason = run.getErrorMessage() == null ? "The report could not be produced." : run.getErrorMessage();
        notifier.failed(run.getTenantId(), tell, run.getRunId(), title, reason, "run-failed:" + run.getRunId());
        if (run.getScheduledFor() != null) {
            dispatcher.registerFailure(schedule, run.getScheduledFor(), run.getErrorCode() == null ? "REPORT_FAILED" : run.getErrorCode(),
                    settings);
        }
    }

    /** One pending row per person and channel. Returns how many exist (a repeat insert is ignored, not doubled). */
    private int createRows(ReportRun run, ReportSchedule schedule, EffectiveReportSettings settings) {
        int rows = 0;
        if (ReportRun.SCOPE_EXTERNAL.equals(run.getDeliveryScope())) {
            for (Recipient r : recipientStore.recipientsOf(schedule.getScheduleId())) {
                if ("email".equals(r.type())) {
                    rows += insertRow(run, schedule, "email", "email", ReportRecipientStore.hash("email", r.ref()), null);
                }
            }
            return rows;
        }
        UUID person = ReportRun.SCOPE_RECIPIENT.equals(run.getDeliveryScope()) ? run.getForUserId() : run.getRequestedBy();
        for (String channel : schedule.getDeliveryChannels()) {
            if (settings.allowedDeliveryChannels().contains(channel)) {
                rows += insertRow(run, schedule, channel, "user", ReportRecipientStore.hash("user", person.toString()), person);
            }
        }
        return rows;
    }

    private int insertRow(ReportRun run, ReportSchedule schedule, String channel, String type, String hash, UUID userId) {
        jdbc.update("""
                insert into report_run_deliveries (run_id, tenant_id, channel, recipient_type, recipient_ref_hash, status,
                                                   recipient_user_id, delivery_mode, next_attempt_at)
                values (:run, :tenant, :channel, :type, :hash, 'pending', :user, :mode, now())
                on conflict (run_id, channel, recipient_type, recipient_ref_hash) do nothing""",
                new MapSqlParameterSource("run", run.getRunId()).addValue("tenant", run.getTenantId()).addValue("channel", channel)
                        .addValue("type", type).addValue("hash", hash).addValue("user", userId)
                        .addValue("mode", schedule.getDeliveryMode()));
        return 1;
    }

    private boolean markNotDelivered(UUID runId, String note) {
        jdbc.update("update report_runs set delivered = false, delivery_note = :note where run_id = :id",
                new MapSqlParameterSource("note", note).addValue("id", runId));
        return true;
    }

    // ---------------------------------------------------------------- 2. attempt

    /** Tries every pending delivery of the run that is due. */
    public void attemptRun(UUID runId) {
        for (UUID id : dueDeliveries(runId)) {
            attempt(id);
        }
    }

    private List<UUID> dueDeliveries(UUID runId) {
        return jdbc.queryForList("""
                select delivery_id from report_run_deliveries
                where status = 'pending' and next_attempt_at <= now() and (cast(:run as uuid) is null or run_id = :run)
                order by next_attempt_at limit :batch""",
                new MapSqlParameterSource("run", runId).addValue("batch", POLL_BATCH), UUID.class);
    }

    /** One delivery, in its own transaction; a row another server holds is skipped, not waited for. */
    void attempt(UUID deliveryId) {
        try {
            transaction.executeWithoutResult(status -> doAttempt(deliveryId));
        } catch (RuntimeException e) {
            log.error("Report delivery {} failed unexpectedly", deliveryId, e);
        }
    }

    private record Row(UUID runId, String channel, String type, String hash, UUID userId, String mode, int attempts) {
    }

    private void doAttempt(UUID deliveryId) {
        List<Row> found = jdbc.query("""
                select run_id, channel, recipient_type, recipient_ref_hash, recipient_user_id, delivery_mode, attempts
                from report_run_deliveries
                where delivery_id = :id and status = 'pending' and next_attempt_at <= now()
                for update skip locked""", new MapSqlParameterSource("id", deliveryId),
                (rs, i) -> new Row(rs.getObject("run_id", UUID.class), rs.getString("channel"), rs.getString("recipient_type"),
                        rs.getString("recipient_ref_hash"), rs.getObject("recipient_user_id", UUID.class),
                        rs.getString("delivery_mode"), rs.getInt("attempts")));
        if (found.isEmpty()) {
            return;
        }
        Row row = found.get(0);
        // Rows of one run settle one at a time, so whoever settles the last one sees the true picture.
        jdbc.queryForList("select pg_advisory_xact_lock(hashtext(:run))", new MapSqlParameterSource("run", row.runId().toString()));

        ReportRun run = runs.findById(row.runId()).orElseThrow();
        ReportSchedule schedule = schedules.findById(run.getScheduleId()).orElseThrow();
        EffectiveReportSettings settings = settingsService.forTenant(run.getTenantId());

        Outcome outcome = decide(row, run, schedule, settings);
        record(deliveryId, row, outcome);
        if (outcome.status() == Outcome.Status.SENT) {
            auditDelivered(run, row, outcome);
        }
        settle(run, schedule, settings);
    }

    /** Everything that can rule a delivery out is checked now, at the moment of sending - not when the schedule was saved. */
    private Outcome decide(Row row, ReportRun run, ReportSchedule schedule, EffectiveReportSettings settings) {
        if (ReportSchedule.CANCELLED.equals(schedule.getStatus())) {
            return Outcome.failed("SCHEDULE_CANCELLED");
        }
        ReportDeliveryChannel channel = channels.get(row.channel());
        if (channel == null) {
            return Outcome.failed("CHANNEL_UNKNOWN");
        }
        if (!settings.allowedDeliveryChannels().contains(row.channel())) {
            return Outcome.failed("CHANNEL_NOT_ALLOWED");
        }
        boolean hasFile = run.getFileObjectKey() != null;
        if (hasFile && (run.getExpiresAt() != null && run.getExpiresAt().isBefore(OffsetDateTime.now()))) {
            return Outcome.failed("REPORT_EXPIRED");
        }
        Target target;
        if ("email".equals(row.type())) {
            Optional<String> address = recipientStore.recipientsOf(schedule.getScheduleId()).stream()
                    .filter(r -> "email".equals(r.type()) && ReportRecipientStore.hash("email", r.ref()).equals(row.hash()))
                    .map(Recipient::ref).findFirst();
            if (address.isEmpty()) {
                return Outcome.failed("RECIPIENT_REMOVED"); // taken off the schedule after the run was made
            }
            target = new Target(null, address.get());
        } else {
            if (people.activePerson(run.getTenantId(), row.userId()).isEmpty()) {
                return Outcome.failed("RECIPIENT_INACTIVE");
            }
            target = new Target(row.userId(), null);
        }
        try {
            boolean alsoByEmail = "in_app".equals(row.channel()) && !target.external()
                    && schedule.getDeliveryChannels().contains("email") && settings.allowedDeliveryChannels().contains("email");
            return channel.deliver(new Delivery(run, titleOf(run, schedule), settings, target, row.mode(), hasFile, alsoByEmail));
        } catch (RuntimeException e) {
            log.warn("Channel {} threw delivering run {}", row.channel(), run.getRunId(), e);
            return Outcome.retry("CHANNEL_ERROR");
        }
    }

    private void record(UUID deliveryId, Row row, Outcome outcome) {
        int attempts = row.attempts() + 1;
        MapSqlParameterSource p = new MapSqlParameterSource("id", deliveryId).addValue("attempts", attempts);
        switch (outcome.status()) {
            case SENT -> jdbc.update("""
                    update report_run_deliveries set status = 'sent', attempts = :attempts, sent_at = now(), next_attempt_at = null,
                           delivery_mode = :mode, note = :note, error_code = null where delivery_id = :id""",
                    p.addValue("mode", outcome.mode()).addValue("note", outcome.note()));
            case FAILED -> fail(p, outcome.code());
            case RETRY -> {
                if (attempts > BACKOFF_MINUTES.length) {
                    fail(p, outcome.code()); // the fourth failure is final
                } else {
                    jdbc.update("""
                            update report_run_deliveries set attempts = :attempts, error_code = :code,
                                   next_attempt_at = now() + make_interval(mins => :wait) where delivery_id = :id""",
                            p.addValue("code", outcome.code()).addValue("wait", BACKOFF_MINUTES[attempts - 1]));
                }
            }
        }
    }

    private void fail(MapSqlParameterSource p, String code) {
        jdbc.update("""
                update report_run_deliveries set status = 'failed', attempts = :attempts, error_code = :code, next_attempt_at = null
                where delivery_id = :id""", p.addValue("code", code));
    }

    /** The last row of a run has settled and some failed: tell the owner once, with counts only. */
    private void settle(ReportRun run, ReportSchedule schedule, EffectiveReportSettings settings) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        jdbc.query("select status, count(*) n from report_run_deliveries where run_id = :run group by status",
                new MapSqlParameterSource("run", run.getRunId()), rs -> {
                    counts.put(rs.getString("status"), rs.getInt("n"));
                });
        if (counts.getOrDefault("pending", 0) > 0 || counts.getOrDefault("failed", 0) == 0) {
            return;
        }
        Set<UUID> tell = new LinkedHashSet<>();
        tell.add(schedule.getOwnerUserId());
        if (settings.failureNotifyAdmins()) {
            tell.addAll(settings.failureAdminUserIds());
        }
        int failed = counts.get("failed");
        int total = counts.values().stream().mapToInt(Integer::intValue).sum();
        notifier.failed(run.getTenantId(), tell, run.getRunId(), titleOf(run, schedule),
                failed + " of " + total + " deliveries of this report did not go through. The schedule's history shows which.",
                "delivery-failed:" + run.getRunId());
    }

    private void auditDelivered(ReportRun run, Row row, Outcome outcome) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("runId", run.getRunId().toString());
        if (run.getTemplateId() != null) {
            details.put("templateId", run.getTemplateId().toString());
        }
        details.put("format", run.getFormat());
        details.put("channel", row.channel());
        details.put("mode", outcome.mode());
        details.put("recipientKind", "email".equals(row.type()) ? "external" : "internal");
        audit.event(AuditActionCatalog.REPORT_DELIVERED).tenantId(run.getTenantId()).objectType(AuditObjectTypes.REPORT_RUN)
                .objectId(run.getRunId()).outcome(AuditOutcome.SUCCESS).newValue(details).log();
    }

    private static String titleOf(ReportRun run, ReportSchedule schedule) {
        String title = run.getDefinitionSnapshot() == null ? null : RunSnapshot.from(run.getDefinitionSnapshot()).title();
        return title == null || title.isBlank() ? schedule.getName() : title;
    }
}
