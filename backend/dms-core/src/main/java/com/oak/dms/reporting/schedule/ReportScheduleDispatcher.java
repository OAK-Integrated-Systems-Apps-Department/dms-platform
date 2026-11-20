package com.oak.dms.reporting.schedule;

import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.catalog.AuditObjectTypes;
import com.oak.dms.audit.enums.AuditOutcome;
import com.oak.dms.audit.service.AuditService;
import com.oak.dms.reporting.ReportException;
import com.oak.dms.reporting.catalog.EffectiveCatalogue;
import com.oak.dms.reporting.datasource.DocumentReportDataSource;
import com.oak.dms.reporting.delivery.ReportNotifier;
import com.oak.dms.reporting.dto.ReportDefinition;
import com.oak.dms.reporting.dto.ScheduleRequest.Recipient;
import com.oak.dms.reporting.entity.ReportRun;
import com.oak.dms.reporting.entity.ReportSchedule;
import com.oak.dms.reporting.entity.ReportTemplate;
import com.oak.dms.reporting.repository.ReportScheduleRepository;
import com.oak.dms.reporting.repository.ReportTemplateRepository;
import com.oak.dms.reporting.run.RunSnapshot;
import com.oak.dms.reporting.settings.EffectiveReportSettings;
import com.oak.dms.reporting.settings.ReportSettingsService;
import com.oak.dms.reporting.template.ReportTemplateService;
import com.oak.dms.reporting.template.ReportTemplateStore;
import com.oak.dms.reporting.template.TemplateDefinitions;
import com.oak.dms.security.auth.OrganisationStatusGuard;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * What happens when a schedule's time comes: decide whether it may run, and make the runs - one file per person, because everyone
 * gets the report under THEIR OWN access (a role or department is expanded to its active people now), plus one for any external
 * addresses, generated under the OWNER's access. The workers produce the files; delivery follows as each finishes.
 * <p>
 * Runs for one slot are inserted with {@code ON CONFLICT DO NOTHING} against V138's unique slot index, so even two schedulers that both
 * got hold of the same slot create each run once. Every check the brief lists is made here, in its order, at execution time: the
 * organisation, the feature, the owner, the owner's permission, the template and the fields. Any failure leaves a {@code skipped} run
 * with a specific code, tells the owner (and the organisation's report administrators when configured), and after enough in a row
 * pauses the schedule.
 */
@Component
public class ReportScheduleDispatcher {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final ReportDefinition EMPTY = new ReportDefinition(null, null, null, null, null, null, null);

    private final ReportScheduleRepository schedules;
    private final ReportTemplateRepository templates;
    private final ReportTemplateStore templateStore;
    private final ReportTemplateService templateService;
    private final ReportSettingsService settingsService;
    private final OrganisationStatusGuard organisationStatus;
    private final ReportAccessProbe probe;
    private final ReportPeople people;
    private final ReportRecipientStore recipientStore;
    private final DocumentReportDataSource dataSource;
    private final ReportNotifier notifier;
    private final AuditService audit;
    private final NamedParameterJdbcTemplate jdbc;

    public ReportScheduleDispatcher(ReportScheduleRepository schedules, ReportTemplateRepository templates,
                                    ReportTemplateStore templateStore, ReportTemplateService templateService,
                                    ReportSettingsService settingsService, OrganisationStatusGuard organisationStatus,
                                    ReportAccessProbe probe, ReportPeople people, ReportRecipientStore recipientStore,
                                    DocumentReportDataSource dataSource, ReportNotifier notifier, AuditService audit,
                                    NamedParameterJdbcTemplate jdbc) {
        this.schedules = schedules;
        this.templates = templates;
        this.templateStore = templateStore;
        this.templateService = templateService;
        this.settingsService = settingsService;
        this.organisationStatus = organisationStatus;
        this.probe = probe;
        this.people = people;
        this.recipientStore = recipientStore;
        this.dataSource = dataSource;
        this.notifier = notifier;
        this.audit = audit;
        this.jdbc = jdbc;
    }

    static NextRunCalculator.Spec specOf(ReportSchedule s) {
        return new NextRunCalculator.Spec(s.getFrequency(), s.getDaysOfWeek().stream().map(Short::intValue).toList(),
                s.getDayOfMonth() == null ? null : s.getDayOfMonth().intValue(), s.getTimeOfDay(), ZoneId.of(s.getTimeZone()),
                s.getStartDate(), s.getEndDate());
    }

    /**
     * Handles one due slot inside the caller's transaction, which holds the schedule row. Returns the runs to start once it commits.
     *
     * @param scheduled true from the scheduler: moves {@code next_run_at}, applies the catch-up window and records skipped runs.
     *                  false for "run now": changes no schedule state and refuses (422) instead of recording a skipped run.
     */
    public List<UUID> fire(ReportSchedule s, OffsetDateTime slot, boolean scheduled) {
        EffectiveReportSettings settings = settingsService.forTenant(s.getTenantId());
        OffsetDateTime now = OffsetDateTime.now();
        if (scheduled) {
            advance(s, now);
            // After downtime a schedule runs ONCE for what it missed - the advance above already left the older slots behind - and
            // only if the slot is recent enough to still matter.
            if (Duration.between(slot, now).compareTo(Duration.ofHours(settings.catchUpWindowHours())) > 0) {
                recordSkipped(s, slot, "MISSED_WINDOW", message("MISSED_WINDOW"), ReportRun.SCOPE_OWNER, null);
                audit(AuditActionCatalog.REPORT_SCHEDULE_RUN_SKIPPED, s, Map.of("reason", "MISSED_WINDOW"));
                return List.of();
            }
        }
        Optional<String> refusal = precheck(s, settings);
        if (refusal.isPresent()) {
            if (!scheduled) {
                throw ReportException.unprocessable("SCHEDULE_CANNOT_RUN", message(refusal.get()));
            }
            skipSlot(s, slot, settings, refusal.get());
            return List.of();
        }
        return createRuns(s, scheduled ? slot : null, scheduled, settings);
    }

    /**
     * A schedule whose slot threw something unexpected: move it past the slot and count a failure, so one broken schedule cannot
     * stand in front of the others on every poll. After enough of them it pauses like any other failing schedule.
     */
    public void quarantine(UUID scheduleId) {
        schedules.findById(scheduleId).filter(s -> ReportSchedule.ACTIVE.equals(s.getStatus()) && s.getNextRunAt() != null
                && !s.getNextRunAt().isAfter(OffsetDateTime.now())).ifPresent(s -> {
            OffsetDateTime slot = s.getNextRunAt();
            advance(s, OffsetDateTime.now());
            registerFailure(s, slot, "SCHEDULER_ERROR", settingsService.forTenant(s.getTenantId()));
        });
    }

    /** The owner left: pause at once, with the reason, and tell the report administrators (the owner cannot be told). */
    public void pauseForInactiveOwner(UUID scheduleId) {
        schedules.findById(scheduleId).filter(s -> ReportSchedule.ACTIVE.equals(s.getStatus())).ifPresent(s ->
                pause(s, ReportSchedule.PAUSED, "OWNER_INACTIVE", settingsService.forTenant(s.getTenantId()),
                        message("OWNER_INACTIVE") + " Reassign it to keep it running.", false));
    }

    // ---------------------------------------------------------------- checks, in the brief's order

    /** The first reason this schedule may not run now, or empty. */
    Optional<String> precheck(ReportSchedule s, EffectiveReportSettings settings) {
        if (organisationStatus.inactiveReason(s.getTenantId()).isPresent()) {
            return Optional.of("TENANT_INACTIVE");
        }
        if (!settings.enabled()) {
            return Optional.of("REPORTS_DISABLED");
        }
        if (!settings.schedulingEnabled()) {
            return Optional.of("SCHEDULING_DISABLED");
        }
        switch (probe.check(s.getTenantId(), s.getOwnerUserId())) {
            case INACTIVE -> {
                return Optional.of("OWNER_INACTIVE");
            }
            case NO_PERMISSION -> {
                return Optional.of("OWNER_PERMISSION_LOST");
            }
            case OK -> {
            }
        }
        Optional<ReportTemplate> template = templates.findByTemplateIdAndTenantIdAndDeletedAtIsNull(s.getTemplateId(), s.getTenantId());
        if (template.isEmpty() || templateStore.accessOf(s.getTenantId(), s.getTemplateId(), s.getOwnerUserId()).isEmpty()) {
            return Optional.of("TEMPLATE_UNAVAILABLE");
        }
        ReportDefinition stored = TemplateDefinitions.load(template.get().getDefinition(), template.get().getDefinitionVersion());
        if (!TemplateDefinitions.unavailable(stored, dataSource.catalogue(s.getTenantId())).isEmpty()) {
            return Optional.of("FIELDS_UNAVAILABLE");
        }
        if ("hourly".equals(s.getFrequency()) && !"hourly".equals(settings.minScheduleInterval())) {
            return Optional.of("FREQUENCY_NOT_ALLOWED");
        }
        if (!settings.allows(s.getFormat())) {
            return Optional.of("FORMAT_NOT_ALLOWED");
        }
        if (s.getDeliveryChannels().stream().noneMatch(settings.allowedDeliveryChannels()::contains)) {
            return Optional.of("CHANNEL_NOT_ALLOWED");
        }
        return Optional.empty();
    }

    static String message(String code) {
        return switch (code) {
            case "TENANT_INACTIVE" -> "Your organisation's account is not active.";
            case "REPORTS_DISABLED" -> "Reports are turned off for your organisation.";
            case "SCHEDULING_DISABLED" -> "Scheduled reports are turned off for your organisation.";
            case "OWNER_INACTIVE" -> "The schedule's owner is no longer an active user.";
            case "OWNER_PERMISSION_LOST" -> "The schedule's owner no longer has permission to run reports.";
            case "TEMPLATE_UNAVAILABLE" -> "The template was deleted, or is no longer available to the schedule's owner.";
            case "FIELDS_UNAVAILABLE" -> "The template uses a field your administrator has switched off.";
            case "FREQUENCY_NOT_ALLOWED" -> "Hourly schedules are no longer available for your organisation.";
            case "FORMAT_NOT_ALLOWED" -> "The schedule's file format is no longer available for your organisation.";
            case "CHANNEL_NOT_ALLOWED" -> "None of the schedule's delivery channels is available for your organisation any more.";
            case "MISSED_WINDOW" -> "The scheduled time passed while the system was unavailable, too long ago to run it now.";
            case "NO_RECIPIENTS" -> "Nobody could receive this report: every recipient is inactive or may no longer run reports.";
            default -> "The schedule could not run.";
        };
    }

    // ---------------------------------------------------------------- a slot that cannot run

    private void skipSlot(ReportSchedule s, OffsetDateTime slot, EffectiveReportSettings settings, String code) {
        UUID skipped = recordSkipped(s, slot, code, message(code), ReportRun.SCOPE_OWNER, null);
        audit(AuditActionCatalog.REPORT_SCHEDULE_RUN_SKIPPED, s, Map.of("reason", code));
        if ("TENANT_INACTIVE".equals(code)) {
            return; // nobody to tell, and nothing to count against the schedule: it resumes when the organisation does
        }
        if ("OWNER_INACTIVE".equals(code)) {
            pause(s, ReportSchedule.PAUSED, "OWNER_INACTIVE", settings, message(code) + " Reassign it to keep it running.", false);
            return;
        }
        notifier.failed(s.getTenantId(), recipientsOfFailure(s, settings), skipped, s.getName(), message(code), "schedule-skip:" + skipped);
        registerFailure(s, slot, code, settings);
    }

    /** The slot failed (a check, or nobody could receive it): count it, and pause the schedule at the organisation's limit. */
    public void registerFailure(ReportSchedule s, OffsetDateTime slot, String code, EffectiveReportSettings settings) {
        Integer failures = jdbc.query("""
                update report_schedules set consecutive_failures = consecutive_failures + 1, last_failure_slot = :slot,
                       last_failure_code = :code, updated_at = now()
                where schedule_id = :id and (last_failure_slot is null or last_failure_slot < :slot)
                returning consecutive_failures""",
                new MapSqlParameterSource("slot", slot).addValue("code", code).addValue("id", s.getScheduleId()),
                rs -> rs.next() ? rs.getInt(1) : null);
        if (failures != null) {
            s.setConsecutiveFailures(failures);
            s.setLastFailureSlot(slot);
            s.setLastFailureCode(code);
            if (failures >= settings.pauseAfterConsecutiveFailures() && ReportSchedule.ACTIVE.equals(s.getStatus())) {
                pause(s, ReportSchedule.PAUSED_FAILED, code, settings,
                        "It failed " + failures + " times in a row: " + message(code) + " Fix the cause, then resume it.", true);
            }
        }
    }

    /** Pauses and tells people - the owner, and the report administrators when the organisation asked for that. */
    void pause(ReportSchedule s, String status, String reason, EffectiveReportSettings settings, String body, boolean tellOwner) {
        s.setStatus(status);
        s.setPauseReason(reason);
        s.setUpdatedAt(OffsetDateTime.now());
        schedules.save(s);
        audit(AuditActionCatalog.REPORT_SCHEDULE_PAUSED, s, Map.of("reason", reason, "status", status));
        Set<UUID> tell = new LinkedHashSet<>();
        if (tellOwner) {
            tell.add(s.getOwnerUserId());
        }
        if (settings.failureNotifyAdmins()) {
            tell.addAll(settings.failureAdminUserIds());
        }
        notifier.schedulePaused(s.getTenantId(), tell, s.getScheduleId(), s.getName(), body);
    }

    private Set<UUID> recipientsOfFailure(ReportSchedule s, EffectiveReportSettings settings) {
        Set<UUID> tell = new LinkedHashSet<>();
        tell.add(s.getOwnerUserId());
        if (settings.failureNotifyAdmins()) {
            tell.addAll(settings.failureAdminUserIds());
        }
        return tell;
    }

    private void advance(ReportSchedule s, OffsetDateTime now) {
        Optional<Instant> next = NextRunCalculator.next(specOf(s), now.toInstant());
        s.setLastRunAt(now);
        s.setNextRunAt(next.map(i -> i.atOffset(ZoneOffset.UTC)).orElse(null));
        if (next.isEmpty()) {
            s.setStatus(ReportSchedule.ENDED);
        }
        s.setUpdatedAt(now);
        schedules.save(s);
    }

    // ---------------------------------------------------------------- the runs of a slot

    private List<UUID> createRuns(ReportSchedule s, OffsetDateTime slot, boolean scheduled, EffectiveReportSettings settings) {
        UUID tenantId = s.getTenantId();
        ReportTemplate template = templates.findByTemplateIdAndTenantIdAndDeletedAtIsNull(s.getTemplateId(), tenantId).orElseThrow();
        EffectiveCatalogue catalogue = dataSource.catalogue(tenantId);
        ReportTemplateService.Resolved resolved = templateService.resolveStored(template, catalogue);
        String snapshot = json(new RunSnapshot(resolved.definition(), s.getTimeZone(), resolved.name()).toMap());
        String trigger = scheduled ? ReportRun.TRIGGER_SCHEDULED : ReportRun.TRIGGER_MANUAL;

        List<Recipient> recipients = recipientStore.recipientsOf(s.getScheduleId());
        Set<UUID> internal = new LinkedHashSet<>();
        List<String> external = new ArrayList<>();
        for (Recipient r : recipients) {
            switch (r.type()) {
                case "user" -> internal.add(UUID.fromString(r.ref()));
                case "role" -> internal.addAll(people.usersWithRole(tenantId, UUID.fromString(r.ref())));
                case "department" -> internal.addAll(people.usersInDepartment(tenantId, UUID.fromString(r.ref())));
                default -> external.add(r.ref());
            }
        }

        List<UUID> queued = new ArrayList<>();
        int alreadyThere = 0; // runs another scheduler instance made for this very slot a moment ago
        if (recipients.isEmpty()) {
            Optional<UUID> made = insertQueued(s, slot, ReportRun.SCOPE_OWNER, null, trigger, snapshot);
            made.ifPresent(queued::add);
            alreadyThere += made.isEmpty() ? 1 : 0;
        }
        for (UUID userId : internal) {
            ReportAccessProbe.Result may = probe.check(tenantId, userId);
            if (may == ReportAccessProbe.Result.OK) {
                Optional<UUID> made = insertQueued(s, slot, ReportRun.SCOPE_RECIPIENT, userId, trigger, snapshot);
                made.ifPresent(queued::add);
                alreadyThere += made.isEmpty() ? 1 : 0;
            } else {
                // Listed in the run's delivery log, so the owner can see who did not get it and why.
                String code = may == ReportAccessProbe.Result.INACTIVE ? "RECIPIENT_INACTIVE" : "RECIPIENT_NO_PERMISSION";
                UUID run = recordSkipped(s, slot, code, "This person could not receive the report.", ReportRun.SCOPE_RECIPIENT, userId);
                if (run != null) {
                    logSkippedDelivery(tenantId, run, userId, s.getDeliveryChannels().get(0), code);
                }
            }
        }
        if (!external.isEmpty()) {
            Optional<UUID> made = insertQueued(s, slot, ReportRun.SCOPE_EXTERNAL, null, trigger, snapshot);
            made.ifPresent(queued::add);
            alreadyThere += made.isEmpty() ? 1 : 0;
        }
        if (queued.isEmpty() && alreadyThere > 0) {
            return queued; // lost the race for this slot: the other instance has it, and nothing here failed
        }
        if (queued.isEmpty() && scheduled) {
            audit(AuditActionCatalog.REPORT_SCHEDULE_RUN_SKIPPED, s, Map.of("reason", "NO_RECIPIENTS"));
            UUID note = recordSkipped(s, slot, "NO_RECIPIENTS", message("NO_RECIPIENTS"), ReportRun.SCOPE_OWNER, null);
            notifier.failed(tenantId, recipientsOfFailure(s, settings), note, s.getName(), message("NO_RECIPIENTS"), "schedule-skip:" + note);
            registerFailure(s, slot, "NO_RECIPIENTS", settings);
        } else if (queued.isEmpty()) {
            throw ReportException.unprocessable("SCHEDULE_CANNOT_RUN", message("NO_RECIPIENTS"));
        }
        return queued;
    }

    private Optional<UUID> insertQueued(ReportSchedule s, OffsetDateTime slot, String scope, UUID forUser, String trigger, String snapshot) {
        UUID runId = UUID.randomUUID();
        int inserted = jdbc.update("""
                insert into report_runs (run_id, tenant_id, template_id, schedule_id, requested_by, for_user_id, delivery_scope, trigger,
                                         format, status, definition_snapshot, scheduled_for, correlation_id)
                values (:run, :tenant, :template, :schedule, :owner, :forUser, :scope, :trigger, :format, 'queued',
                        cast(:snapshot as jsonb), :slot, :correlation)
                on conflict do nothing""", runParams(s, runId, slot, scope, forUser, trigger, s.getFormat(), snapshot));
        return inserted == 1 ? Optional.of(runId) : Optional.empty();
    }

    /** A run that did not happen, kept so the history shows why. Null if another scheduler already recorded this one. */
    private UUID recordSkipped(ReportSchedule s, OffsetDateTime slot, String code, String text, String scope, UUID forUser) {
        UUID runId = UUID.randomUUID();
        String snapshot = json(new RunSnapshot(EMPTY, s.getTimeZone(), s.getName()).toMap());
        MapSqlParameterSource params = runParams(s, runId, slot, scope, forUser, ReportRun.TRIGGER_SCHEDULED, s.getFormat(), snapshot)
                .addValue("code", code).addValue("text", text);
        int inserted = jdbc.update("""
                insert into report_runs (run_id, tenant_id, template_id, schedule_id, requested_by, for_user_id, delivery_scope, trigger,
                                         format, status, definition_snapshot, scheduled_for, correlation_id, error_code, error_message,
                                         finished_at)
                values (:run, :tenant, :template, :schedule, :owner, :forUser, :scope, :trigger, :format, 'skipped',
                        cast(:snapshot as jsonb), :slot, :correlation, :code, :text, now())
                on conflict do nothing""", params);
        return inserted == 1 ? runId : null;
    }

    private static MapSqlParameterSource runParams(ReportSchedule s, UUID runId, OffsetDateTime slot, String scope, UUID forUser,
                                                   String trigger, String format, String snapshot) {
        return new MapSqlParameterSource("run", runId).addValue("tenant", s.getTenantId()).addValue("template", s.getTemplateId())
                .addValue("schedule", s.getScheduleId()).addValue("owner", s.getOwnerUserId()).addValue("forUser", forUser)
                .addValue("scope", scope).addValue("trigger", trigger).addValue("format", format).addValue("snapshot", snapshot)
                .addValue("slot", slot).addValue("correlation", UUID.randomUUID());
    }

    private void logSkippedDelivery(UUID tenantId, UUID runId, UUID userId, String channel, String note) {
        jdbc.update("""
                insert into report_run_deliveries (run_id, tenant_id, channel, recipient_type, recipient_ref_hash, status, recipient_user_id, note)
                values (:run, :tenant, :channel, 'user', :hash, 'skipped', :user, :note) on conflict do nothing""",
                new MapSqlParameterSource("run", runId).addValue("tenant", tenantId).addValue("channel", channel)
                        .addValue("hash", ReportRecipientStore.hash("user", userId.toString())).addValue("user", userId)
                        .addValue("note", note));
    }

    private void audit(String action, ReportSchedule s, Map<String, Object> details) {
        audit.event(action).tenantId(s.getTenantId()).objectType(AuditObjectTypes.REPORT_SCHEDULE).objectId(s.getScheduleId())
                .outcome(AuditOutcome.SUCCESS).newValue(new LinkedHashMap<>(details)).log();
    }

    private static String json(Map<String, Object> map) {
        return JSON.writeValueAsString(map);
    }
}
