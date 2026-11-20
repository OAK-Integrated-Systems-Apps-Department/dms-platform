package com.oak.dms.reporting.schedule;

import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.catalog.AuditObjectTypes;
import com.oak.dms.audit.enums.AuditOutcome;
import com.oak.dms.audit.service.AuditService;
import com.oak.dms.common.dto.PageResponse;
import com.oak.dms.reporting.ReportException;
import com.oak.dms.reporting.dto.AdminScheduleResponse;
import com.oak.dms.reporting.entity.ReportSchedule;
import com.oak.dms.reporting.entity.ReportTemplate;
import com.oak.dms.reporting.repository.ReportScheduleRepository;
import com.oak.dms.reporting.repository.ReportTemplateRepository;
import com.oak.dms.reporting.settings.EffectiveReportSettings;
import com.oak.dms.reporting.settings.ReportSettingsService;
import com.oak.dms.reporting.template.ReportTemplateStore;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.security.rbac.catalog.PermissionCodes;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The organisation's report administrator over every schedule: list them, pause, resume or cancel any, and hand one to someone else.
 * Pausing never deletes history. What the administrator sees is metadata - owner, template, timing, health, how many recipients -
 * never who the recipients are.
 * <p>
 * A schedule runs as its OWNER, with their access: so handing one over is checked as carefully as creating one. The new owner must
 * be active, may run reports, and must be able to see the template; and a schedule that sends to external addresses (which go out
 * under the owner's access) can only go to someone who may configure reports, as creating it required.
 */
@Service
public class ReportScheduleAdminService {

    private final ReportScheduleRepository schedules;
    private final ReportTemplateRepository templates;
    private final ReportTemplateStore templateStore;
    private final ReportScheduleService scheduleService;
    private final ReportSettingsService settingsService;
    private final ReportRecipientStore recipientStore;
    private final ReportAccessProbe probe;
    private final ReportPeople people;
    private final AuditService audit;
    private final NamedParameterJdbcTemplate jdbc;
    private final com.oak.dms.reporting.delivery.ReportNotifier notifier;

    public ReportScheduleAdminService(ReportScheduleRepository schedules, ReportTemplateRepository templates,
                                      ReportTemplateStore templateStore, ReportScheduleService scheduleService,
                                      ReportSettingsService settingsService, ReportRecipientStore recipientStore,
                                      ReportAccessProbe probe, ReportPeople people, AuditService audit, NamedParameterJdbcTemplate jdbc,
                                      com.oak.dms.reporting.delivery.ReportNotifier notifier) {
        this.notifier = notifier;
        this.schedules = schedules;
        this.templates = templates;
        this.templateStore = templateStore;
        this.scheduleService = scheduleService;
        this.settingsService = settingsService;
        this.recipientStore = recipientStore;
        this.probe = probe;
        this.people = people;
        this.audit = audit;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public PageResponse<AdminScheduleResponse> list(String status, UUID ownerUserId, UUID templateId, Pageable pageable) {
        UUID tenantId = TenantContext.getTenantId();
        Specification<ReportSchedule> spec = (root, query, cb) -> {
            List<Predicate> all = new ArrayList<>();
            all.add(cb.equal(root.get("tenantId"), tenantId));
            if (status != null && !status.isBlank()) {
                all.add(cb.equal(root.get("status"), status));
            }
            if (ownerUserId != null) {
                all.add(cb.equal(root.get("ownerUserId"), ownerUserId));
            }
            if (templateId != null) {
                all.add(cb.equal(root.get("templateId"), templateId));
            }
            return cb.and(all.toArray(Predicate[]::new));
        };
        Page<ReportSchedule> page = schedules.findAll(spec, pageable);
        List<ReportSchedule> content = page.getContent();
        Map<UUID, String> owners = templateStore.userNames(tenantId, content.stream().map(ReportSchedule::getOwnerUserId).collect(Collectors.toSet()));
        Map<UUID, String> templateNames = new LinkedHashMap<>();
        templates.findAllById(content.stream().map(ReportSchedule::getTemplateId).collect(Collectors.toSet()))
                .forEach(t -> templateNames.put(t.getTemplateId(), t.getName()));
        Map<UUID, Long> recipientCounts = recipientCounts(content.stream().map(ReportSchedule::getScheduleId).toList());
        return PageResponse.from(page.map(s -> new AdminScheduleResponse(s.getScheduleId(), s.getName(), s.getOwnerUserId(),
                owners.get(s.getOwnerUserId()), s.getTemplateId(), templateNames.get(s.getTemplateId()), s.getFrequency(), s.getTimeZone(),
                s.getFormat(), s.getStatus(), s.getPauseReason(), s.getNextRunAt(), s.getLastRunAt(), s.getConsecutiveFailures(),
                s.getLastFailureCode(), recipientCounts.getOrDefault(s.getScheduleId(), 0L))));
    }

    @Transactional
    public void pause(UUID scheduleId) {
        ReportSchedule s = live(scheduleId);
        if (ReportSchedule.ACTIVE.equals(s.getStatus())) {
            s.setStatus(ReportSchedule.PAUSED);
            s.setPauseReason("ADMIN");
            s.setUpdatedAt(OffsetDateTime.now());
            schedules.save(s);
            audit(AuditActionCatalog.REPORT_SCHEDULE_PAUSED, s, Map.of("reason", "ADMIN", "status", s.getStatus()));
        }
    }

    /** Refused while the owner is gone: it would only be paused again at the next poll. Reassign it first. */
    @Transactional
    public void resume(UUID scheduleId) {
        ReportSchedule s = live(scheduleId);
        if (ReportSchedule.ACTIVE.equals(s.getStatus())) {
            return;
        }
        if (people.activePerson(s.getTenantId(), s.getOwnerUserId()).isEmpty()) {
            throw ReportException.conflict("SCHEDULE_OWNER_INACTIVE", "The owner is no longer an active user. Reassign the schedule first.");
        }
        scheduleService.resumeSchedule(s, settingsService.forTenant(s.getTenantId()));
        audit(AuditActionCatalog.REPORT_SCHEDULE_RESUMED, s, Map.of("by", "ADMIN", "status", s.getStatus()));
    }

    @Transactional
    public void cancel(UUID scheduleId) {
        ReportSchedule s = live(scheduleId);
        s.setStatus(ReportSchedule.CANCELLED);
        s.setNextRunAt(null);
        s.setPauseReason(null);
        s.setUpdatedAt(OffsetDateTime.now());
        schedules.save(s);
        audit(AuditActionCatalog.REPORT_SCHEDULE_CANCELLED, s, Map.of("by", "ADMIN"));
    }

    @Transactional
    public AdminScheduleResponse reassign(UUID scheduleId, UUID newOwner) {
        UUID tenantId = TenantContext.getTenantId();
        if (newOwner == null) {
            throw ReportException.unprocessable("REASSIGN_OWNER_REQUIRED", "Say who the schedule goes to.");
        }
        ReportSchedule s = live(scheduleId);
        EffectiveReportSettings settings = settingsService.requireEnabled(tenantId);
        if (people.activePerson(tenantId, newOwner).isEmpty()) {
            throw ReportException.unprocessable("REASSIGN_OWNER_INACTIVE", "That person is not an active user of your organisation.");
        }
        boolean external = recipientStore.recipientsOf(scheduleId).stream().anyMatch(r -> "email".equals(r.type()));
        ReportAccessProbe.Result may = external
                ? probe.check(tenantId, newOwner, PermissionCodes.CRG_CONFIGURE_REPORTS) : probe.check(tenantId, newOwner);
        if (may != ReportAccessProbe.Result.OK) {
            throw ReportException.unprocessable("REASSIGN_OWNER_CANNOT_RUN", external
                    ? "This schedule sends to external addresses, so its owner must be allowed to run and to administer reports."
                    : "That person is not allowed to run reports.");
        }
        if (templateStore.accessOf(tenantId, s.getTemplateId(), newOwner).isEmpty()) {
            throw ReportException.unprocessable("REASSIGN_OWNER_NO_TEMPLATE_ACCESS",
                    "That person cannot see the schedule's template. Share it with them first.");
        }
        if (!newOwner.equals(s.getOwnerUserId()) && schedules.countLiveByOwner(tenantId, newOwner) >= settings.maxSchedulesPerUser()) {
            throw ReportException.conflict("SCHEDULE_LIMIT_REACHED", "That person already has as many schedules as they may keep.");
        }

        UUID previous = s.getOwnerUserId();
        boolean wasWaitingForNewOwner = "OWNER_INACTIVE".equals(s.getPauseReason());
        s.setOwnerUserId(newOwner);
        s.setConsecutiveFailures(0);
        s.setLastFailureCode(null);
        s.setUpdatedAt(OffsetDateTime.now());
        if (wasWaitingForNewOwner) {
            scheduleService.resumeSchedule(s, settings); // what it was waiting for has happened
        } else {
            schedules.save(s);
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("reassigned", true);
        details.put("fromUser", previous.toString());
        details.put("toUser", newOwner.toString());
        details.put("status", s.getStatus());
        audit(AuditActionCatalog.REPORT_SCHEDULE_UPDATED, s, details);
        if (!newOwner.equals(previous)) {
            notifier.scheduleAssigned(tenantId, newOwner, s.getScheduleId(), s.getName());
        }
        return single(s);
    }

    private AdminScheduleResponse single(ReportSchedule s) {
        String owner = templateStore.userNames(s.getTenantId(), Set.of(s.getOwnerUserId())).get(s.getOwnerUserId());
        String template = templates.findById(s.getTemplateId()).map(ReportTemplate::getName).orElse(null);
        return new AdminScheduleResponse(s.getScheduleId(), s.getName(), s.getOwnerUserId(), owner, s.getTemplateId(), template,
                s.getFrequency(), s.getTimeZone(), s.getFormat(), s.getStatus(), s.getPauseReason(), s.getNextRunAt(), s.getLastRunAt(),
                s.getConsecutiveFailures(), s.getLastFailureCode(), recipientStore.recipientsOf(s.getScheduleId()).size());
    }

    // ---------------------------------------------------------------- internals

    /** In this organisation and not cancelled or ended; anything else is a 404 or a plain conflict. */
    private ReportSchedule live(UUID scheduleId) {
        ReportSchedule s = schedules.findByScheduleIdAndTenantId(scheduleId, TenantContext.getTenantId())
                .orElseThrow(() -> ReportException.notFound("SCHEDULE_NOT_FOUND", "That schedule was not found."));
        if (!s.isLive()) {
            throw ReportException.conflict("SCHEDULE_NOT_ACTIVE", "This schedule has been cancelled or has ended.");
        }
        return s;
    }

    private Map<UUID, Long> recipientCounts(List<UUID> scheduleIds) {
        Map<UUID, Long> counts = new LinkedHashMap<>();
        if (scheduleIds.isEmpty()) {
            return counts;
        }
        jdbc.query("select schedule_id, count(*) n from report_schedule_recipients where schedule_id in (:ids) group by schedule_id",
                new MapSqlParameterSource("ids", scheduleIds), rs -> {
                    counts.put(rs.getObject("schedule_id", UUID.class), rs.getLong("n"));
                });
        return counts;
    }

    private void audit(String action, ReportSchedule s, Map<String, Object> details) {
        Map<String, Object> payload = new LinkedHashMap<>(details);
        payload.put("adminAction", true);
        payload.put("templateId", s.getTemplateId().toString());
        audit.event(action).objectType(AuditObjectTypes.REPORT_SCHEDULE).objectId(s.getScheduleId()).outcome(AuditOutcome.SUCCESS)
                .newValue(payload).log();
    }
}
