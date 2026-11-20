package com.oak.dms.reporting.admin;

import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.catalog.AuditObjectTypes;
import com.oak.dms.audit.enums.AuditOutcome;
import com.oak.dms.audit.service.AuditService;
import com.oak.dms.common.dto.PageResponse;
import com.oak.dms.reporting.ReportException;
import com.oak.dms.reporting.catalog.FieldDescriptor;
import com.oak.dms.reporting.catalog.ReportFieldCatalog;
import com.oak.dms.reporting.dto.AdminFieldResponse;
import com.oak.dms.reporting.dto.AdminFieldsUpdate;
import com.oak.dms.reporting.dto.AdminRunResponse;
import com.oak.dms.reporting.dto.AdminSettingsResponse;
import com.oak.dms.reporting.dto.FieldOverride;
import com.oak.dms.reporting.dto.ReportSettingsUpdate;
import com.oak.dms.reporting.entity.ReportFieldSetting;
import com.oak.dms.reporting.entity.ReportRun;
import com.oak.dms.reporting.repository.ReportFieldSettingRepository;
import com.oak.dms.reporting.repository.ReportRunRepository;
import com.oak.dms.reporting.run.ReportRunStatus;
import com.oak.dms.reporting.settings.ReportSettingsService;
import com.oak.dms.reporting.template.ReportTemplateStore;
import com.oak.dms.security.auth.TenantContext;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * What the organisation's report administrator manages: the settings, the field catalogue's overrides, and a metadata-only look at
 * every run. (Schedules are {@code ReportScheduleAdminService}'s.)
 * <p>
 * None of this asks whether reports are switched ON: the administrator must be able to reach the settings to switch them back on, and
 * to look at what happened while they were off. Every change is audited with old and new values, and a change that changes nothing
 * is neither saved nor audited.
 */
@Service
public class ReportAdminService {

    private static final int LABEL_MAX = 120;
    private static final int ORDER_MAX = 100_000;

    private final ReportSettingsService settingsService;
    private final ReportFieldCatalog catalog;
    private final ReportFieldSettingRepository fieldSettings;
    private final ReportRunRepository runs;
    private final ReportTemplateStore store;
    private final AuditService audit;
    private final com.oak.dms.reporting.schedule.ReportPeople people;
    private final com.oak.dms.reporting.repository.ReportTemplateRepository templates;
    private final com.oak.dms.reporting.repository.ReportScheduleRepository schedules;

    public ReportAdminService(ReportSettingsService settingsService, ReportFieldCatalog catalog,
                              ReportFieldSettingRepository fieldSettings, ReportRunRepository runs, ReportTemplateStore store,
                              AuditService audit, com.oak.dms.reporting.repository.ReportTemplateRepository templates,
                              com.oak.dms.reporting.repository.ReportScheduleRepository schedules,
                              com.oak.dms.reporting.schedule.ReportPeople people) {
        this.templates = templates;
        this.schedules = schedules;
        this.people = people;
        this.settingsService = settingsService;
        this.catalog = catalog;
        this.fieldSettings = fieldSettings;
        this.runs = runs;
        this.store = store;
        this.audit = audit;
    }

    // ---------------------------------------------------------------- settings

    @Transactional(readOnly = true)
    public AdminSettingsResponse settings() {
        return new AdminSettingsResponse(settingsService.forTenant(TenantContext.getTenantId()), settingsService.ceilings());
    }

    /** Validates against the platform ceilings (422, every problem at once), saves, audits old and new values, returns what is now in force. */
    @Transactional
    public AdminSettingsResponse saveSettings(ReportSettingsUpdate update) {
        return new AdminSettingsResponse(settingsService.save(update), settingsService.ceilings());
    }

    // ---------------------------------------------------------------- pickers

    /** People or roles by name for the administrator's pickers (type user | role). */
    @Transactional(readOnly = true)
    public List<com.oak.dms.reporting.schedule.ReportPeople.Option> principals(String type, String q, int limit) {
        if (!"user".equals(type) && !"role".equals(type)) {
            throw com.oak.dms.reporting.ReportException.unprocessable("REPORT_PRINCIPAL_TYPE", "Choose user or role.");
        }
        return people.search(TenantContext.getTenantId(), type, q, limit);
    }

    // ---------------------------------------------------------------- fields

    /** Every field the code offers, with the administrator's override (if any) beside it. */
    @Transactional(readOnly = true)
    public List<AdminFieldResponse> fields() {
        UUID tenantId = TenantContext.getTenantId();
        Map<String, ReportFieldSetting> overrides = fieldSettings.findAllByTenantId(tenantId).stream()
                .collect(Collectors.toMap(ReportFieldSetting::getFieldKey, s -> s, (a, b) -> a));
        Map<String, int[]> usage = fieldUsage(tenantId);
        List<AdminFieldResponse> result = new ArrayList<>();
        for (FieldDescriptor f : catalog.codeDefaults(tenantId)) {
            ReportFieldSetting s = overrides.get(f.key());
            int[] used = usage.getOrDefault(f.key(), new int[2]);
            result.add(new AdminFieldResponse(f.key(), f.label(), f.type().name().toLowerCase(), f.isMetadata(), f.selectable(),
                    f.filterable(), f.sortable(), f.groupable(), f.defaultSelected(), s == null ? null : toOverride(s), used[0], used[1]));
        }
        return result;
    }

    /**
     * Per field key: {official templates using it, live schedules whose template uses it}. Counts only - a private template of
     * another person is never named. One pass over the official templates and the templates a live schedule runs.
     */
    private Map<String, int[]> fieldUsage(UUID tenantId) {
        Map<UUID, Integer> perTemplate = new java.util.HashMap<>();
        for (Object[] row : schedules.countLiveByTemplate(tenantId)) {
            perTemplate.put((UUID) row[0], ((Number) row[1]).intValue());
        }
        Map<String, int[]> usage = new java.util.HashMap<>();
        for (var t : templates.findOfficialOrScheduled(tenantId)) {
            java.util.Set<String> keys = new java.util.LinkedHashSet<>();
            try {
                var d = com.oak.dms.reporting.template.TemplateDefinitions.load(t.getDefinition(), t.getDefinitionVersion());
                d.filtersOrEmpty().forEach(f -> keys.add(f.field()));
                keys.addAll(d.columnsOrEmpty());
                d.sortOrEmpty().forEach(s -> keys.add(s.field()));
                if (d.groupBy() != null && !d.groupBy().isBlank()) {
                    keys.add(d.groupBy());
                }
            } catch (RuntimeException unreadable) {
                continue; // a definition from a newer release: nothing sensible to count
            }
            int live = perTemplate.getOrDefault(t.getTemplateId(), 0);
            for (String key : keys) {
                int[] counts = usage.computeIfAbsent(key, k -> new int[2]);
                if (t.isOfficial()) {
                    counts[0]++;
                }
                counts[1] += live;
            }
        }
        return usage;
    }

    /**
     * Replaces every override in one transaction: a field not in the list goes back to the code's defaults. Refused (422, every
     * problem named by field) for an unknown or repeated field, a label that is too long, or a catalogue left with nothing to put in
     * a column. Rows that say nothing the code does not already say are not stored.
     */
    @Transactional
    public List<AdminFieldResponse> saveFields(AdminFieldsUpdate update) {
        UUID tenantId = TenantContext.getTenantId();
        Map<String, FieldDescriptor> known = catalog.codeDefaults(tenantId).stream()
                .collect(Collectors.toMap(FieldDescriptor::key, f -> f, (a, b) -> a, LinkedHashMap::new));
        List<FieldOverride> wanted = update == null || update.overrides() == null ? List.of() : update.overrides();
        Map<String, FieldOverride> clean = validate(wanted, known);

        Map<String, ReportFieldSetting> existing = fieldSettings.findAllByTenantId(tenantId).stream()
                .filter(s -> known.containsKey(s.getFieldKey()))
                .collect(Collectors.toMap(ReportFieldSetting::getFieldKey, s -> s, (a, b) -> a, LinkedHashMap::new));
        Map<String, Object> before = new LinkedHashMap<>();
        existing.forEach((key, s) -> before.put(key, describe(toOverride(s))));
        Map<String, Object> after = new LinkedHashMap<>();
        clean.forEach((key, o) -> after.put(key, describe(o)));

        if (!before.equals(after)) {
            fieldSettings.deleteAllByTenant(tenantId);
            UUID by = TenantContext.getUserId();
            List<ReportFieldSetting> rows = new ArrayList<>();
            clean.forEach((key, o) -> rows.add(ReportFieldSetting.builder().tenantId(tenantId).fieldKey(key).enabled(o.enabledOrDefault())
                    .labelOverride(blankToNull(o.labelOverride())).selectable(o.selectableOrDefault()).filterable(o.filterableOrDefault())
                    .sortable(o.sortableOrDefault()).groupable(o.groupableOrDefault()).defaultSelected(o.defaultSelectedOrDefault())
                    .displayOrder(o.displayOrder()).showCode(o.showCodeOrDefault()).updatedBy(by).build()));
            fieldSettings.saveAll(rows);
            audit(before, after);
        }
        return fields();
    }

    private Map<String, FieldOverride> validate(List<FieldOverride> wanted, Map<String, FieldDescriptor> known) {
        Map<String, String> problems = new LinkedHashMap<>();
        Map<String, FieldOverride> clean = new LinkedHashMap<>();
        for (FieldOverride o : wanted) {
            if (o == null || o.fieldKey() == null || o.fieldKey().isBlank()) {
                problems.put("overrides", "Every override needs a field key.");
                continue;
            }
            String key = o.fieldKey();
            if (!known.containsKey(key)) {
                problems.put(key, "This field does not exist in the catalogue.");
                continue;
            }
            if (clean.containsKey(key)) {
                problems.put(key, "This field is listed more than once.");
                continue;
            }
            String label = blankToNull(o.labelOverride());
            if (label != null && label.length() > LABEL_MAX) {
                problems.put(key, "The label can be at most " + LABEL_MAX + " characters.");
                continue;
            }
            if (o.displayOrder() != null && (o.displayOrder() < 0 || o.displayOrder() > ORDER_MAX)) {
                problems.put(key, "The display order must be between 0 and " + ORDER_MAX + ".");
                continue;
            }
            if (o.defaultSelectedOrDefault() && (!o.enabledOrDefault() || !o.selectableOrDefault())) {
                problems.put(key, "A field that is off, or cannot be a column, cannot be selected by default.");
                continue;
            }
            FieldOverride normalised = new FieldOverride(key, o.enabledOrDefault(), label, o.selectableOrDefault(), o.filterableOrDefault(),
                    o.sortableOrDefault(), o.groupableOrDefault(), o.defaultSelectedOrDefault(), o.displayOrder(), o.showCodeOrDefault());
            if (!saysNothing(normalised, known.get(key))) {
                clean.put(key, normalised);
            } else {
                clean.put(key, null); // reserved so a repeat is still caught; dropped just below
            }
        }
        clean.values().removeIf(Objects::isNull);
        boolean someColumn = known.values().stream().anyMatch(f -> {
            FieldOverride o = clean.get(f.key());
            return o == null ? f.selectable() : o.enabledOrDefault() && o.selectableOrDefault() && f.selectable();
        });
        if (!someColumn) {
            problems.put("overrides", "At least one field must stay available as a column.");
        }
        if (!problems.isEmpty()) {
            throw ReportException.invalid("REPORT_FIELD_SETTINGS_INVALID", "The field settings have problems you need to fix.", problems);
        }
        return clean;
    }

    /** An override that restates the code's own behaviour is the same as no override. */
    private static boolean saysNothing(FieldOverride o, FieldDescriptor code) {
        return o.enabledOrDefault() && o.labelOverride() == null && o.selectableOrDefault() && o.filterableOrDefault()
                && o.sortableOrDefault() && o.groupableOrDefault() && o.defaultSelectedOrDefault() == code.defaultSelected()
                && o.displayOrder() == null && !o.showCodeOrDefault();
    }

    private static FieldOverride toOverride(ReportFieldSetting s) {
        return new FieldOverride(s.getFieldKey(), s.isEnabled(), s.getLabelOverride(), s.isSelectable(), s.isFilterable(), s.isSortable(),
                s.isGroupable(), s.isDefaultSelected(), s.getDisplayOrder(), s.isShowCode());
    }

    private static Map<String, Object> describe(FieldOverride o) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("enabled", o.enabledOrDefault());
        d.put("label", o.labelOverride());
        d.put("selectable", o.selectableOrDefault());
        d.put("filterable", o.filterableOrDefault());
        d.put("sortable", o.sortableOrDefault());
        d.put("groupable", o.groupableOrDefault());
        d.put("defaultSelected", o.defaultSelectedOrDefault());
        d.put("displayOrder", o.displayOrder());
        d.put("showCode", o.showCodeOrDefault());
        return d;
    }

    /** Only the fields that changed, old and new, so the entry stays readable however many fields the organisation has. */
    private void audit(Map<String, Object> before, Map<String, Object> after) {
        Set<String> changed = new java.util.TreeSet<>();
        changed.addAll(before.keySet());
        changed.addAll(after.keySet());
        changed.removeIf(k -> Objects.equals(before.get(k), after.get(k)));
        Map<String, Object> old = new LinkedHashMap<>();
        Map<String, Object> now = new LinkedHashMap<>();
        changed.forEach(k -> {
            old.put(k, before.getOrDefault(k, "code default"));
            now.put(k, after.getOrDefault(k, "code default"));
        });
        audit.event(AuditActionCatalog.REPORT_FIELD_SETTINGS_UPDATED).objectType(AuditObjectTypes.TENANT)
                .objectId(TenantContext.getTenantId()).outcome(AuditOutcome.SUCCESS).previousValue(old).newValue(now).log();
    }

    // ---------------------------------------------------------------- every run, metadata only

    @Transactional(readOnly = true)
    public PageResponse<AdminRunResponse> allRuns(ReportRunStatus status, UUID requestedBy, UUID templateId, UUID scheduleId,
                                                  OffsetDateTime from, Pageable pageable) {
        UUID tenantId = TenantContext.getTenantId();
        Specification<ReportRun> spec = (root, query, cb) -> {
            List<Predicate> all = new ArrayList<>();
            all.add(cb.equal(root.get("tenantId"), tenantId));
            if (status != null) {
                all.add(cb.equal(root.get("status"), status));
            }
            if (requestedBy != null) {
                all.add(cb.equal(root.get("requestedBy"), requestedBy));
            }
            if (templateId != null) {
                all.add(cb.equal(root.get("templateId"), templateId));
            }
            if (scheduleId != null) {
                all.add(cb.equal(root.get("scheduleId"), scheduleId));
            }
            if (from != null) {
                all.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from));
            }
            return cb.and(all.toArray(Predicate[]::new));
        };
        Page<ReportRun> page = runs.findAll(spec, pageable);
        Map<UUID, String> names = store.userNames(tenantId, page.getContent().stream().map(ReportRun::getRequestedBy).collect(Collectors.toSet()));
        return PageResponse.from(page.map(r -> new AdminRunResponse(r.getRunId(), r.getTemplateId(), r.getScheduleId(), r.getRequestedBy(),
                names.get(r.getRequestedBy()), r.getForUserId(), r.getDeliveryScope(), r.getTriggerType(), r.getFormat(),
                r.getStatus().name(), r.getRowCount(), r.isTruncated(), r.getErrorCode(), r.getCreatedAt(), r.getStartedAt(),
                r.getFinishedAt(), r.getExpiresAt())));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
