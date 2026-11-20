package com.oak.dms.reporting.schedule;

import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.catalog.AuditObjectTypes;
import com.oak.dms.audit.enums.AuditOutcome;
import com.oak.dms.audit.service.AuditService;
import com.oak.dms.common.dto.PageResponse;
import com.oak.dms.reporting.ReportException;
import com.oak.dms.reporting.dto.RunResponse;
import com.oak.dms.reporting.dto.ScheduleRequest;
import com.oak.dms.reporting.dto.ScheduleRequest.Recipient;
import com.oak.dms.reporting.dto.ScheduleResponse;
import com.oak.dms.reporting.dto.ScheduleRunResponse;
import com.oak.dms.reporting.entity.ReportRun;
import com.oak.dms.reporting.entity.ReportSchedule;
import com.oak.dms.reporting.entity.ReportTemplate;
import com.oak.dms.reporting.repository.ReportRunRepository;
import com.oak.dms.reporting.repository.ReportScheduleRepository;
import com.oak.dms.reporting.repository.ReportTemplateRepository;
import com.oak.dms.reporting.run.ReportRunWorker;
import com.oak.dms.reporting.settings.EffectiveReportSettings;
import com.oak.dms.reporting.settings.ReportSettingsService;
import com.oak.dms.reporting.template.ReportTemplateStore;
import com.oak.dms.security.auth.AuthorizationService;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.security.rbac.catalog.PermissionCodes;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A person's schedules: create, change, pause, resume, cancel, run once now, and read the history. Everything is the owner's own;
 * someone else's schedule is a plain 404. The tenant administrator's views of every schedule are {@code ReportAdminService}'s.
 * <p>
 * Saving checks everything the scheduler will check again when the time comes (the owner may lose access in between): scheduling is
 * on, the owner may run the template, the format and channels are allowed, the recipients exist and fit the limit, external addresses
 * are allowed and on the allow-list, and there is a next run to wait for.
 */
@Service
public class ReportScheduleService {

    private static final Set<String> FREQUENCIES = Set.of("hourly", "daily", "weekly", "monthly");
    private static final Set<String> CHANNELS = Set.of("in_app", "email");
    private static final Set<String> MODES = Set.of("link", "attachment");
    private static final Set<String> RECIPIENT_TYPES = Set.of("user", "role", "department", "email");
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@,;<>()\\[\\]\"]+@[^\\s@,;<>()\\[\\]\"]+\\.[^\\s@,;<>()\\[\\]\"]+$");

    private final ReportScheduleRepository schedules;
    private final ReportRecipientStore recipients;
    private final ReportTemplateRepository templates;
    private final ReportTemplateStore templateStore;
    private final ReportSettingsService settingsService;
    private final AuthorizationService authorization;
    private final ReportRunRepository runs;
    private final ReportScheduleDispatcher dispatcher;
    private final ReportRunWorker worker;
    private final AuditService audit;
    private final NamedParameterJdbcTemplate jdbc;

    public ReportScheduleService(ReportScheduleRepository schedules, ReportRecipientStore recipients,
                                 ReportTemplateRepository templates, ReportTemplateStore templateStore,
                                 ReportSettingsService settingsService, AuthorizationService authorization,
                                 ReportRunRepository runs, ReportScheduleDispatcher dispatcher, ReportRunWorker worker,
                                 AuditService audit, NamedParameterJdbcTemplate jdbc) {
        this.schedules = schedules;
        this.recipients = recipients;
        this.templates = templates;
        this.templateStore = templateStore;
        this.settingsService = settingsService;
        this.authorization = authorization;
        this.runs = runs;
        this.dispatcher = dispatcher;
        this.worker = worker;
        this.audit = audit;
        this.jdbc = jdbc;
    }

    // ---------------------------------------------------------------- read

    @Transactional(readOnly = true)
    public List<ScheduleResponse> listMine() {
        UUID tenantId = TenantContext.getTenantId();
        settingsService.requireEnabled(tenantId);
        return schedules.findAll((root, query, cb) -> cb.and(cb.equal(root.get("tenantId"), tenantId),
                        cb.equal(root.get("ownerUserId"), TenantContext.getUserId()), cb.notEqual(root.get("status"), ReportSchedule.CANCELLED)),
                org.springframework.data.domain.Sort.by("name")).stream().map(this::respond).toList();
    }

    @Transactional(readOnly = true)
    public ScheduleResponse get(UUID scheduleId) {
        settingsService.requireEnabled(TenantContext.getTenantId());
        return respond(own(scheduleId));
    }

    @Transactional(readOnly = true)
    public PageResponse<ScheduleRunResponse> history(UUID scheduleId, Pageable pageable) {
        ReportSchedule schedule = own(scheduleId);
        Page<ReportRun> page = runs.findAll((root, query, cb) -> cb.and(cb.equal(root.get("tenantId"), schedule.getTenantId()),
                cb.equal(root.get("scheduleId"), schedule.getScheduleId())), pageable);
        Map<UUID, int[]> counts = deliveryCounts(page.getContent().stream().map(ReportRun::getRunId).toList());
        return PageResponse.from(page.map(r -> {
            int[] c = counts.getOrDefault(r.getRunId(), new int[4]);
            return new ScheduleRunResponse(RunResponse.from(r), r.getDelivered(), r.getDeliveryNote(), c[0], c[1], c[2], c[3]);
        }));
    }

    // ---------------------------------------------------------------- create, update

    @Transactional
    public ScheduleResponse create(ScheduleRequest request) {
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();
        EffectiveReportSettings settings = requireScheduling(tenantId);
        if (schedules.countLiveByOwner(tenantId, userId) >= settings.maxSchedulesPerUser()) {
            throw ReportException.conflict("SCHEDULE_LIMIT_REACHED",
                    "You can keep " + settings.maxSchedulesPerUser() + " schedules. Cancel one to add another.");
        }
        if (schedules.countLiveByTenant(tenantId) >= settings.maxSchedulesPerTenant()) {
            throw ReportException.conflict("SCHEDULE_TENANT_LIMIT_REACHED", "Your organisation has reached its limit of schedules.");
        }
        Validated v = validate(tenantId, userId, request, settings);
        ReportSchedule schedule = ReportSchedule.builder().tenantId(tenantId).ownerUserId(userId).build();
        apply(schedule, request, v);
        schedules.saveAndFlush(schedule); // flushed: the recipient rows below are plain SQL and need the schedule row to exist
        recipients.replace(tenantId, schedule.getScheduleId(), v.recipients());
        auditSchedule(AuditActionCatalog.REPORT_SCHEDULE_CREATED, schedule, v.recipients());
        return respond(schedule);
    }

    @Transactional
    public ScheduleResponse update(UUID scheduleId, ScheduleRequest request) {
        UUID tenantId = TenantContext.getTenantId();
        EffectiveReportSettings settings = requireScheduling(tenantId);
        ReportSchedule schedule = ownLive(scheduleId);
        Validated v = validate(tenantId, schedule.getOwnerUserId(), request, settings);
        apply(schedule, request, v);
        schedule.setConsecutiveFailures(0);
        schedule.setLastFailureCode(null);
        if (ReportSchedule.PAUSED_FAILED.equals(schedule.getStatus())) {
            schedule.setNextRunAt(null); // still paused: it is resumed on purpose
        }
        schedules.save(schedule);
        recipients.replace(tenantId, scheduleId, v.recipients());
        auditSchedule(AuditActionCatalog.REPORT_SCHEDULE_UPDATED, schedule, v.recipients());
        return respond(schedule);
    }

    // ---------------------------------------------------------------- state

    @Transactional
    public ScheduleResponse pause(UUID scheduleId) {
        ReportSchedule schedule = ownLive(scheduleId);
        if (ReportSchedule.ACTIVE.equals(schedule.getStatus())) {
            changeStatus(schedule, ReportSchedule.PAUSED, "USER");
            auditState(AuditActionCatalog.REPORT_SCHEDULE_PAUSED, schedule, "USER");
        }
        return respond(schedule);
    }

    @Transactional
    public ScheduleResponse resume(UUID scheduleId) {
        UUID tenantId = TenantContext.getTenantId();
        EffectiveReportSettings settings = requireScheduling(tenantId);
        ReportSchedule schedule = ownLive(scheduleId);
        if (!ReportSchedule.ACTIVE.equals(schedule.getStatus())) {
            resumeSchedule(schedule, settings);
            auditState(AuditActionCatalog.REPORT_SCHEDULE_RESUMED, schedule, null);
        }
        return respond(schedule);
    }

    /** Shared with the administrator's resume: back to active, failures forgotten, next run worked out from now. */
    void resumeSchedule(ReportSchedule schedule, EffectiveReportSettings settings) {
        Optional<Instant> next = NextRunCalculator.next(ReportScheduleDispatcher.specOf(schedule), Instant.now());
        if (next.isEmpty()) {
            throw ReportException.unprocessable("SCHEDULE_NO_FUTURE_RUN", "This schedule has no further runs - its end date has passed.");
        }
        schedule.setStatus(ReportSchedule.ACTIVE);
        schedule.setPauseReason(null);
        schedule.setConsecutiveFailures(0);
        schedule.setLastFailureCode(null);
        schedule.setNextRunAt(next.get().atOffset(ZoneOffset.UTC));
        schedule.setUpdatedAt(OffsetDateTime.now());
        schedules.save(schedule);
    }

    /** DELETE: cancelling keeps the history. */
    @Transactional
    public void cancel(UUID scheduleId) {
        ReportSchedule schedule = ownLive(scheduleId);
        schedule.setStatus(ReportSchedule.CANCELLED);
        schedule.setNextRunAt(null);
        schedule.setPauseReason(null);
        schedule.setUpdatedAt(OffsetDateTime.now());
        schedules.save(schedule);
        auditState(AuditActionCatalog.REPORT_SCHEDULE_CANCELLED, schedule, null);
    }

    /** Runs the schedule once, now, as it would run at its next slot; does not move the next run. */
    @Transactional
    public List<UUID> runNow(UUID scheduleId) {
        UUID tenantId = TenantContext.getTenantId();
        requireScheduling(tenantId);
        ReportSchedule schedule = ownLive(scheduleId);
        List<UUID> created = dispatcher.fire(schedule, OffsetDateTime.now(), false);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                created.forEach(worker::start);
            }
        });
        return created;
    }

    // ---------------------------------------------------------------- validation

    private record Validated(UUID templateId, ZoneId zone, List<Integer> days, List<Recipient> recipients, Instant next) {
    }

    private Validated validate(UUID tenantId, UUID ownerId, ScheduleRequest r, EffectiveReportSettings settings) {
        Map<String, String> problems = new LinkedHashMap<>();
        String name = r.name() == null ? "" : r.name().strip();
        if (name.isEmpty() || name.length() > 150) {
            problems.put("name", "1 to 150 characters.");
        }
        if (r.frequency() == null || !FREQUENCIES.contains(r.frequency())) {
            problems.put("frequency", "Choose hourly, daily, weekly or monthly.");
        }
        if ("hourly".equals(r.frequency()) && !"hourly".equals(settings.minScheduleInterval())) {
            throw ReportException.unprocessable("SCHEDULE_HOURLY_NOT_ALLOWED",
                    "Hourly schedules are not available for your organisation. Choose daily, weekly or monthly.");
        }
        List<Integer> days = r.daysOfWeek() == null ? List.of() : new ArrayList<>(new TreeSet<>(r.daysOfWeek()));
        if ("weekly".equals(r.frequency()) && (days.isEmpty() || days.stream().anyMatch(d -> d == null || d < 1 || d > 7))) {
            problems.put("daysOfWeek", "Choose at least one day, Monday (1) to Sunday (7).");
        }
        if ("monthly".equals(r.frequency()) && (r.dayOfMonth() == null || (r.dayOfMonth() != -1 && (r.dayOfMonth() < 1 || r.dayOfMonth() > 31)))) {
            problems.put("dayOfMonth", "Choose a day from 1 to 31, or the last day (-1).");
        }
        if (r.timeOfDay() == null) {
            problems.put("timeOfDay", "Choose a time of day.");
        }
        ZoneId zone = null;
        if (r.timeZone() == null || !ZoneId.getAvailableZoneIds().contains(r.timeZone())) {
            problems.put("timeZone", "Use a time zone name such as Asia/Colombo.");
        } else {
            zone = ZoneId.of(r.timeZone());
        }
        if (r.startDate() != null && r.endDate() != null && r.endDate().isBefore(r.startDate())) {
            problems.put("endDate", "The end date cannot be before the start date.");
        }
        if (r.format() == null || !settings.allows(r.format())) {
            problems.put("format", "Choose one of: " + String.join(", ", settings.allowedFormats()));
        }
        List<String> channels = r.deliveryChannels() == null ? List.of() : r.deliveryChannels().stream().distinct().toList();
        if (channels.isEmpty() || !CHANNELS.containsAll(channels) || !settings.allowedDeliveryChannels().containsAll(channels)) {
            problems.put("deliveryChannels", "Choose from: " + String.join(", ", settings.allowedDeliveryChannels()));
        }
        String mode = r.deliveryMode() == null || r.deliveryMode().isBlank() ? settings.deliveryModeDefault() : r.deliveryMode();
        if (!MODES.contains(mode)) {
            problems.put("deliveryMode", "Choose link or attachment.");
        } else if ("attachment".equals(mode) && (!settings.allowAttachments() || !channels.contains("email"))) {
            problems.put("deliveryMode", "Attachments are not available: your organisation must allow them, and the schedule must use email.");
        }
        List<Recipient> clean = cleanRecipients(tenantId, r.recipients(), settings, channels, problems);

        UUID templateId = r.templateId();
        if (templateId == null || templateStore.accessOf(tenantId, templateId, ownerId).isEmpty()
                || templates.findByTemplateIdAndTenantIdAndDeletedAtIsNull(templateId, tenantId).isEmpty()) {
            throw ReportException.notFound("TEMPLATE_NOT_FOUND", "That template was not found.");
        }
        if (!problems.isEmpty()) {
            throw ReportException.invalid("SCHEDULE_INVALID", "The schedule has problems you need to fix.", problems);
        }
        Instant next = NextRunCalculator.next(new NextRunCalculator.Spec(r.frequency(), days, r.dayOfMonth(), r.timeOfDay(), zone,
                r.startDate(), r.endDate()), Instant.now()).orElseThrow(() -> ReportException.unprocessable("SCHEDULE_NO_FUTURE_RUN",
                "This schedule would never run: its end date has already passed."));
        return new Validated(templateId, zone, days, clean, next);
    }

    private List<Recipient> cleanRecipients(UUID tenantId, List<Recipient> raw, EffectiveReportSettings settings, List<String> channels,
                                            Map<String, String> problems) {
        Map<String, Recipient> unique = new LinkedHashMap<>();
        boolean external = false;
        for (Recipient r : raw == null ? List.<Recipient>of() : raw) {
            if (r == null || r.type() == null || r.ref() == null || r.ref().isBlank() || !RECIPIENT_TYPES.contains(r.type())) {
                problems.put("recipients", "Each recipient needs a type (user, role, department or email) and a value.");
                continue;
            }
            Recipient clean;
            if ("email".equals(r.type())) {
                String address = r.ref().strip().toLowerCase(Locale.ROOT);
                if (!EMAIL.matcher(address).matches() || address.length() > 320) {
                    problems.put("recipients", "One of the email addresses is not valid.");
                    continue;
                }
                clean = new Recipient("email", address);
                external = true;
            } else {
                UUID id;
                try {
                    id = UUID.fromString(r.ref().strip());
                } catch (IllegalArgumentException e) {
                    problems.put("recipients", "A user, role or department is given by its id.");
                    continue;
                }
                if (!templateStore.principalExists(tenantId, r.type(), id)) {
                    problems.put("recipients", "One of the people, roles or departments does not exist.");
                    continue;
                }
                clean = new Recipient(r.type(), id.toString());
            }
            unique.putIfAbsent(clean.type() + ":" + clean.ref(), clean);
        }
        List<Recipient> result = new ArrayList<>(unique.values());
        if (result.size() > settings.maxRecipientsPerSchedule()) {
            problems.put("recipients", "At most " + settings.maxRecipientsPerSchedule() + " recipients.");
        }
        if (external) {
            validateExternal(result, settings, channels, problems);
        }
        return result;
    }

    /**
     * External addresses get the report under the OWNER's access, outside the organisation: that needs the organisation's switch, the
     * allow-list, the configure permission, and an attachment (a link would lead to a sign-in they do not have).
     */
    private void validateExternal(List<Recipient> all, EffectiveReportSettings settings, List<String> channels, Map<String, String> problems) {
        if (!settings.allowExternalRecipients() || settings.externalDomainAllowlist().isEmpty()) {
            problems.put("recipients", "Your organisation does not allow sending reports to external addresses.");
            return;
        }
        if (!authorization.hasPermission(PermissionCodes.CRG_CONFIGURE_REPORTS)) {
            problems.put("recipients", "Only a report administrator can add external addresses.");
            return;
        }
        if (!settings.allowAttachments() || !channels.contains("email")) {
            problems.put("recipients", "External addresses receive the report as an email attachment: attachments must be allowed and email chosen.");
            return;
        }
        Set<String> allowed = Set.copyOf(settings.externalDomainAllowlist().stream().map(d -> d.toLowerCase(Locale.ROOT)).toList());
        for (Recipient r : all) {
            if ("email".equals(r.type()) && !allowed.contains(r.ref().substring(r.ref().indexOf('@') + 1))) {
                problems.put("recipients", "An external address is not on your organisation's allowed domains.");
                return;
            }
        }
    }

    private void apply(ReportSchedule schedule, ScheduleRequest r, Validated v) {
        EffectiveReportSettings settings = settingsService.forTenant(schedule.getTenantId());
        schedule.setTemplateId(v.templateId());
        schedule.setName(r.name().strip());
        schedule.setFrequency(r.frequency());
        schedule.setDaysOfWeek("weekly".equals(r.frequency()) ? v.days().stream().map(Integer::shortValue).toList() : new ArrayList<>());
        schedule.setDayOfMonth("monthly".equals(r.frequency()) ? r.dayOfMonth().shortValue() : null);
        schedule.setTimeOfDay(r.timeOfDay());
        schedule.setTimeZone(v.zone().getId());
        schedule.setStartDate(r.startDate());
        schedule.setEndDate(r.endDate());
        schedule.setFormat(r.format());
        schedule.setDeliveryChannels(new ArrayList<>(r.deliveryChannels().stream().distinct().toList()));
        schedule.setDeliveryMode(r.deliveryMode() == null || r.deliveryMode().isBlank() ? settings.deliveryModeDefault() : r.deliveryMode());
        if (ReportSchedule.ACTIVE.equals(schedule.getStatus())) {
            schedule.setNextRunAt(v.next().atOffset(ZoneOffset.UTC));
        }
        schedule.setUpdatedAt(OffsetDateTime.now());
    }

    // ---------------------------------------------------------------- internals

    private EffectiveReportSettings requireScheduling(UUID tenantId) {
        EffectiveReportSettings settings = settingsService.requireEnabled(tenantId);
        if (!settings.schedulingEnabled()) {
            throw ReportException.forbidden("REPORT_SCHEDULING_DISABLED", "Scheduled reports are turned off for your organisation.");
        }
        if (!authorization.hasPermission(PermissionCodes.CRG_RUN) || !authorization.hasPermission(PermissionCodes.DOCM_DOCUMENTS_VIEW)) {
            throw ReportException.forbidden("REPORT_RUN_REQUIRED", "You need permission to run reports to schedule them.");
        }
        return settings;
    }

    private ReportSchedule own(UUID scheduleId) {
        return schedules.findByScheduleIdAndTenantId(scheduleId, TenantContext.getTenantId())
                .filter(s -> s.getOwnerUserId().equals(TenantContext.getUserId()))
                .orElseThrow(() -> ReportException.notFound("SCHEDULE_NOT_FOUND", "That schedule was not found."));
    }

    /** Own, and not already cancelled or ended. */
    private ReportSchedule ownLive(UUID scheduleId) {
        ReportSchedule schedule = own(scheduleId);
        if (!schedule.isLive()) {
            throw ReportException.conflict("SCHEDULE_NOT_ACTIVE", "This schedule has been cancelled or has ended.");
        }
        return schedule;
    }

    private void changeStatus(ReportSchedule schedule, String status, String reason) {
        schedule.setStatus(status);
        schedule.setPauseReason(reason);
        schedule.setUpdatedAt(OffsetDateTime.now());
        schedules.save(schedule);
    }

    private ScheduleResponse respond(ReportSchedule s) {
        String templateName = templates.findById(s.getTemplateId()).map(ReportTemplate::getName).orElse(null);
        boolean mine = s.getOwnerUserId().equals(TenantContext.getUserId());
        List<Recipient> all = recipients.recipientsOf(s.getScheduleId());
        // External addresses are the owner's to see: an administrator looking at the schedule sees that they exist, not who.
        List<Recipient> shown = mine ? all : all.stream().map(r -> "email".equals(r.type()) ? new Recipient("email", "(external address)") : r).toList();
        return new ScheduleResponse(s.getScheduleId(), s.getTemplateId(), templateName, s.getOwnerUserId(), s.getName(), s.getFrequency(),
                s.getDaysOfWeek().stream().map(Short::intValue).toList(), s.getDayOfMonth() == null ? null : s.getDayOfMonth().intValue(),
                s.getTimeOfDay(), s.getTimeZone(), s.getStartDate(), s.getEndDate(), s.getFormat(), s.getDeliveryChannels(),
                s.getDeliveryMode(), s.getStatus(), s.getPauseReason(), s.getNextRunAt(), s.getLastRunAt(), s.getConsecutiveFailures(),
                s.getLastFailureCode(), shown, s.getCreatedAt());
    }

    /** [sent, failed, pending, skipped] per run, one query. */
    private Map<UUID, int[]> deliveryCounts(List<UUID> runIds) {
        Map<UUID, int[]> result = new LinkedHashMap<>();
        if (runIds.isEmpty()) {
            return result;
        }
        jdbc.query("select run_id, status, count(*) n from report_run_deliveries where run_id in (:ids) group by run_id, status",
                new MapSqlParameterSource("ids", runIds), rs -> {
                    int[] c = result.computeIfAbsent(rs.getObject("run_id", UUID.class), k -> new int[4]);
                    switch (rs.getString("status")) {
                        case "sent" -> c[0] = rs.getInt("n");
                        case "failed" -> c[1] = rs.getInt("n");
                        case "pending" -> c[2] = rs.getInt("n");
                        default -> c[3] = rs.getInt("n");
                    }
                });
        return result;
    }

    private void auditSchedule(String action, ReportSchedule s, List<Recipient> recipientList) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("templateId", s.getTemplateId().toString());
        details.put("frequency", s.getFrequency());
        details.put("format", s.getFormat());
        details.put("channels", s.getDeliveryChannels());
        details.put("deliveryMode", s.getDeliveryMode());
        // Counts only: never an address.
        for (String type : List.of("user", "role", "department", "email")) {
            details.put(type + "Recipients", recipientList.stream().filter(r -> type.equals(r.type())).count());
        }
        audit.event(action).objectType(AuditObjectTypes.REPORT_SCHEDULE).objectId(s.getScheduleId())
                .outcome(AuditOutcome.SUCCESS).newValue(details).log();
    }

    private void auditState(String action, ReportSchedule s, String reason) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("status", s.getStatus());
        if (reason != null) {
            details.put("reason", reason);
        }
        audit.event(action).objectType(AuditObjectTypes.REPORT_SCHEDULE).objectId(s.getScheduleId())
                .outcome(AuditOutcome.SUCCESS).newValue(details).log();
    }
}
