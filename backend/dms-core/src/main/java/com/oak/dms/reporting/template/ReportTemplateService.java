package com.oak.dms.reporting.template;

import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.catalog.AuditObjectTypes;
import com.oak.dms.audit.enums.AuditOutcome;
import com.oak.dms.audit.service.AuditService;
import com.oak.dms.folder.DocumentAccessService;
import com.oak.dms.reporting.ReportException;
import com.oak.dms.reporting.catalog.EffectiveCatalogue;
import com.oak.dms.reporting.datasource.DocumentReportDataSource;
import com.oak.dms.reporting.datasource.ReportContext;
import com.oak.dms.reporting.dto.ReportDefinition;
import com.oak.dms.reporting.dto.TemplatePublishRequest;
import com.oak.dms.reporting.dto.TemplateRequest;
import com.oak.dms.reporting.dto.TemplateResponse;
import com.oak.dms.reporting.dto.TemplateShareRequest;
import com.oak.dms.reporting.dto.TemplateShareRequest.Grant;
import com.oak.dms.reporting.entity.ReportTemplate;
import com.oak.dms.reporting.repository.ReportTemplateRepository;
import com.oak.dms.reporting.settings.EffectiveReportSettings;
import com.oak.dms.reporting.settings.ReportSettingsService;
import com.oak.dms.security.auth.AuthorizationService;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.security.rbac.catalog.PermissionCodes;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Saved report definitions: private, shared with chosen people, or published as the organisation's own (official).
 * <p>
 * Every read goes through {@link ReportTemplateStore}, so a template the caller may not see is a plain 404 - the same as one that does
 * not exist. A definition is checked against the organisation's current field catalogue whenever it is saved, so what is stored
 * could run when it was stored; a field switched off later is reported as a warning and left out of runs.
 */
@Service
public class ReportTemplateService {

    private static final int NAME_MAX = 150;
    private static final int DESCRIPTION_MAX = 500;
    private static final Set<String> PRINCIPALS = Set.of("user", "role", "department");
    private static final Set<String> LEVELS = Set.of("run", "edit");

    private final ReportTemplateRepository templates;
    private final ReportTemplateStore store;
    private final ReportSettingsService settingsService;
    private final DocumentReportDataSource dataSource;
    private final DocumentAccessService documentAccess;
    private final AuthorizationService authorization;
    private final AuditService audit;

    public ReportTemplateService(ReportTemplateRepository templates, ReportTemplateStore store,
                                 ReportSettingsService settingsService, DocumentReportDataSource dataSource,
                                 DocumentAccessService documentAccess, AuthorizationService authorization, AuditService audit) {
        this.templates = templates;
        this.store = store;
        this.settingsService = settingsService;
        this.dataSource = dataSource;
        this.documentAccess = documentAccess;
        this.authorization = authorization;
        this.audit = audit;
    }

    /** A template ready to run: the definition without fields that are gone, plus what the request changed. */
    public record Resolved(UUID templateId, String name, ReportDefinition definition) {
    }

    // ---------------------------------------------------------------- read

    @Transactional(readOnly = true)
    public List<TemplateResponse> list() {
        UUID tenantId = TenantContext.getTenantId();
        settingsService.requireEnabled(tenantId);
        Map<UUID, String> visible = store.visible(tenantId, TenantContext.getUserId(), canPublish());
        if (visible.isEmpty()) {
            return List.of();
        }
        List<ReportTemplate> found = templates.findByTenantIdAndTemplateIdInAndDeletedAtIsNull(tenantId, visible.keySet());
        Map<UUID, String> owners = store.userNames(tenantId, found.stream().map(ReportTemplate::getOwnerUserId)
                .filter(java.util.Objects::nonNull).collect(java.util.stream.Collectors.toSet()));
        EffectiveCatalogue catalogue = dataSource.catalogue(tenantId);
        return found.stream()
                .sorted(java.util.Comparator.comparing(t -> t.getName().toLowerCase()))
                .map(t -> respond(t, visible.get(t.getTemplateId()), owners, catalogue, false))
                .toList();
    }

    @Transactional(readOnly = true)
    public TemplateResponse get(UUID templateId) {
        UUID tenantId = TenantContext.getTenantId();
        settingsService.requireEnabled(tenantId);
        ReportTemplate template = find(tenantId, templateId);
        String access = accessOrNotFound(tenantId, templateId);
        return respond(template, access, store.userNames(tenantId, owners(template)), dataSource.catalogue(tenantId), true);
    }

    // ---------------------------------------------------------------- create, update, delete

    @Transactional
    public TemplateResponse create(TemplateRequest request) {
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();
        EffectiveReportSettings settings = settingsService.requireEnabled(tenantId);
        requireLimit(tenantId, userId, settings);
        return saveNew(tenantId, userId, request.name(), request.description(), request.definition());
    }

    @Transactional
    public TemplateResponse update(UUID templateId, TemplateRequest request) {
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();
        settingsService.requireEnabled(tenantId);
        ReportTemplate template = find(tenantId, templateId);
        String access = accessOrNotFound(tenantId, templateId);
        requireEdit(template, access);

        String name = cleanName(request.name());
        validate(request.definition());
        requireNameFree(template, name, templateId);
        Map<String, Object> before = describe(template);
        template.setName(name);
        template.setDescription(cleanDescription(request.description()));
        template.setDefinition(TemplateDefinitions.toStored(request.definition()));
        template.setDefinitionVersion(TemplateDefinitions.CURRENT_VERSION);
        template.setUpdatedBy(userId);
        template.setUpdatedAt(OffsetDateTime.now());
        flush(template);

        auditTemplate(AuditActionCatalog.REPORT_TEMPLATE_UPDATED, template, before, describe(template));
        return respond(template, access, store.userNames(tenantId, owners(template)), dataSource.catalogue(tenantId), true);
    }

    /**
     * Soft delete. A template with live schedules is refused with their count - or, with {@code cancelSchedules}, deleted together
     * with them in this one transaction, each cancellation audited.
     */
    @Transactional
    public void delete(UUID templateId, boolean cancelSchedules) {
        UUID tenantId = TenantContext.getTenantId();
        settingsService.requireEnabled(tenantId);
        ReportTemplate template = find(tenantId, templateId);
        String access = accessOrNotFound(tenantId, templateId);
        if (template.isOfficial()) {
            requirePublisher();
        } else if (!TemplateResponse.OWNER.equals(access)) {
            throw ReportException.forbidden("TEMPLATE_NOT_OWNER", "Only the owner can delete a template.");
        }
        long live = store.liveSchedules(tenantId, templateId);
        if (live > 0 && !cancelSchedules) {
            throw ReportException.conflict("TEMPLATE_HAS_SCHEDULES",
                    "This template has " + live + " active schedule" + (live == 1 ? "" : "s")
                            + ". Cancel them first, or delete with cancelSchedules=true.",
                    Map.of("schedules", live));
        }
        for (UUID scheduleId : store.cancelSchedules(tenantId, templateId)) {
            audit.event(AuditActionCatalog.REPORT_SCHEDULE_CANCELLED)
                    .objectType(AuditObjectTypes.REPORT_SCHEDULE)
                    .objectId(scheduleId)
                    .outcome(AuditOutcome.SUCCESS)
                    .newValue(Map.of("reason", "TEMPLATE_DELETED", "templateId", templateId.toString()))
                    .log();
        }
        template.setDeletedAt(OffsetDateTime.now());
        template.setUpdatedBy(TenantContext.getUserId());
        template.setUpdatedAt(OffsetDateTime.now());
        templates.save(template);
        auditTemplate(AuditActionCatalog.REPORT_TEMPLATE_DELETED, template, describe(template), null);
    }

    @Transactional
    public TemplateResponse duplicate(UUID templateId, String requestedName) {
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();
        EffectiveReportSettings settings = settingsService.requireEnabled(tenantId);
        ReportTemplate source = find(tenantId, templateId);
        accessOrNotFound(tenantId, templateId);
        if (source.isOfficial() && source.isLocked() && !authorization.hasPermission(PermissionCodes.CRG_TEMPLATE_PUBLISH)) {
            throw ReportException.forbidden("TEMPLATE_LOCKED", "This is a locked organisation template. You can run it, but not copy it.");
        }
        requireLimit(tenantId, userId, settings);
        String name = requestedName != null && !requestedName.isBlank() ? cleanName(requestedName) : freeCopyName(tenantId, userId, source.getName());
        ReportDefinition definition = TemplateDefinitions.load(source.getDefinition(), source.getDefinitionVersion());
        return saveNew(tenantId, userId, name, source.getDescription(), TemplateDefinitions.withoutUnavailable(
                definition, dataSource.catalogue(tenantId)));
    }

    // ---------------------------------------------------------------- sharing and publishing

    /** Replaces who a template is shared with. An empty list makes it private again. */
    @Transactional
    public TemplateResponse share(UUID templateId, TemplateShareRequest request) {
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();
        settingsService.requireEnabled(tenantId);
        ReportTemplate template = find(tenantId, templateId);
        String access = accessOrNotFound(tenantId, templateId);
        if (template.isOfficial()) {
            throw ReportException.conflict("TEMPLATE_IS_OFFICIAL", "An organisation template is shared by publishing it, not here.");
        }
        if (!TemplateResponse.OWNER.equals(access)) {
            throw ReportException.forbidden("TEMPLATE_NOT_OWNER", "Only the owner can change who a template is shared with.");
        }
        List<Grant> grants = cleanGrants(tenantId, request == null ? null : request.grants(), true);
        grants.removeIf(g -> "user".equals(g.principalType()) && userId.equals(g.principalId())); // the owner needs no grant
        Map<String, Object> before = describe(template);
        store.replaceGrants(tenantId, templateId, grants);
        template.setScope(grants.isEmpty() ? ReportTemplate.PRIVATE : ReportTemplate.SHARED);
        template.setUpdatedBy(userId);
        template.setUpdatedAt(OffsetDateTime.now());
        templates.save(template);

        Map<String, Object> after = describe(template);
        after.put("grants", grants.size());
        audit.event(AuditActionCatalog.REPORT_TEMPLATE_SHARED)
                .objectType(AuditObjectTypes.REPORT_TEMPLATE)
                .objectId(templateId)
                .outcome(AuditOutcome.SUCCESS)
                .previousValue(before)
                .newValue(after)
                .log();
        return respond(template, access, store.userNames(tenantId, owners(template)), dataSource.catalogue(tenantId), true);
    }

    /**
     * Makes the caller's template the organisation's own: no owner any more, optionally locked, visible to everyone or an audience.
     * On a template that is already official (the caller may publish, so already holds the permission this needs) it changes the lock,
     * the editable filters and the audience in place.
     */
    @Transactional
    public TemplateResponse publish(UUID templateId, TemplatePublishRequest request) {
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();
        settingsService.requireEnabled(tenantId);
        ReportTemplate template = find(tenantId, templateId);
        String access = accessOrNotFound(tenantId, templateId);
        if (!template.isOfficial()) {
            if (!TemplateResponse.OWNER.equals(access)) {
                throw ReportException.forbidden("TEMPLATE_NOT_OWNER", "Only the owner can publish a template.");
            }
            if (templates.officialNameTaken(tenantId, template.getName(), templateId)) {
                throw ReportException.conflict("TEMPLATE_NAME_TAKEN", "An organisation template with this name already exists. Rename yours first.");
            }
        }
        ReportDefinition definition = TemplateDefinitions.load(template.getDefinition(), template.getDefinitionVersion());
        validate(definition);
        List<String> editable = request.locked() ? cleanEditableFilters(request.userEditableFilters()) : List.of();
        List<Grant> audience = cleanGrants(tenantId, request.audience(), false);

        Map<String, Object> before = describe(template);
        store.replaceGrants(tenantId, templateId, audience);
        template.setScope(ReportTemplate.OFFICIAL);
        template.setOwnerUserId(null);
        template.setLocked(request.locked());
        template.setUserEditableFilters(new ArrayList<>(editable));
        template.setUpdatedBy(userId);
        template.setUpdatedAt(OffsetDateTime.now());
        flush(template);

        Map<String, Object> after = describe(template);
        after.put("audience", audience.isEmpty() ? "everyone" : audience.size() + " grants");
        audit.event(AuditActionCatalog.REPORT_TEMPLATE_PUBLISHED)
                .objectType(AuditObjectTypes.REPORT_TEMPLATE)
                .objectId(templateId)
                .outcome(AuditOutcome.SUCCESS)
                .previousValue(before)
                .newValue(after)
                .log();
        return respond(template, TemplateResponse.EDIT, Map.of(), dataSource.catalogue(tenantId), true);
    }

    /** Takes an official template back to a private one owned by the caller. */
    @Transactional
    public TemplateResponse unpublish(UUID templateId) {
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();
        settingsService.requireEnabled(tenantId);
        ReportTemplate template = find(tenantId, templateId);
        accessOrNotFound(tenantId, templateId);
        if (!template.isOfficial()) {
            throw ReportException.conflict("TEMPLATE_NOT_OFFICIAL", "This template is not published.");
        }
        if (templates.ownerNameTaken(tenantId, userId, template.getName(), templateId)) {
            throw ReportException.conflict("TEMPLATE_NAME_TAKEN", "You already have a template with this name. Rename one of them first.");
        }
        EffectiveReportSettings settings = settingsService.forTenant(tenantId);
        requireLimit(tenantId, userId, settings);
        Map<String, Object> before = describe(template);
        store.replaceGrants(tenantId, templateId, List.of());
        template.setScope(ReportTemplate.PRIVATE);
        template.setOwnerUserId(userId);
        template.setLocked(false);
        template.setUserEditableFilters(new ArrayList<>());
        template.setUpdatedBy(userId);
        template.setUpdatedAt(OffsetDateTime.now());
        flush(template);
        auditTemplate(AuditActionCatalog.REPORT_TEMPLATE_UPDATED, template, before, describe(template));
        return respond(template, TemplateResponse.OWNER, Map.of(), dataSource.catalogue(tenantId), true);
    }

    // ---------------------------------------------------------------- running

    /**
     * The template as a run will execute it, for a caller who may run it. A field switched off since the template was saved is
     * dropped (the run goes ahead without it); the request's filter overrides replace the template's filter on the same field - all
     * of them allowed for a template the caller may edit or that is open, only the editable ones for a locked official one.
     */
    @Transactional(readOnly = true)
    public Resolved resolveForRun(UUID templateId, List<ReportDefinition.Filter> overrides) {
        UUID tenantId = TenantContext.getTenantId();
        ReportTemplate template = find(tenantId, templateId);
        accessOrNotFound(tenantId, templateId);
        return resolve(template, overrides, dataSource.catalogue(tenantId));
    }

    /** For the scheduler: the template as stored, for an owner it has already checked. */
    public Resolved resolveStored(ReportTemplate template, EffectiveCatalogue catalogue) {
        return resolve(template, null, catalogue);
    }

    private Resolved resolve(ReportTemplate template, List<ReportDefinition.Filter> overrides, EffectiveCatalogue catalogue) {
        ReportDefinition stored = TemplateDefinitions.load(template.getDefinition(), template.getDefinitionVersion());
        ReportDefinition definition = TemplateDefinitions.withoutUnavailable(stored, catalogue);
        if (overrides != null && !overrides.isEmpty()) {
            definition = withOverrides(template, definition, overrides);
        }
        return new Resolved(template.getTemplateId(), template.getName(), definition);
    }

    private ReportDefinition withOverrides(ReportTemplate template, ReportDefinition definition,
                                           List<ReportDefinition.Filter> overrides) {
        boolean locked = template.isOfficial() && template.isLocked();
        Map<String, ReportDefinition.Filter> byField = new LinkedHashMap<>();
        definition.filtersOrEmpty().forEach(f -> byField.put(f.field(), f));
        for (ReportDefinition.Filter override : overrides) {
            boolean allowed = locked ? template.getUserEditableFilters().contains(override.field()) : true;
            if (!allowed) {
                throw ReportException.invalid("TEMPLATE_FILTER_NOT_EDITABLE", "This template does not let you change that filter.",
                        Map.of(String.valueOf(override.field()), "Locked by your administrator."));
            }
            byField.put(override.field(), override);
        }
        return new ReportDefinition(definition.source(), new ArrayList<>(byField.values()), definition.columns(), definition.sort(),
                definition.groupBy(), definition.includeInactive(), definition.timeZone());
    }

    // ---------------------------------------------------------------- internals

    private TemplateResponse saveNew(UUID tenantId, UUID userId, String rawName, String description, ReportDefinition definition) {
        String name = cleanName(rawName);
        validate(definition);
        if (templates.ownerNameTaken(tenantId, userId, name, new UUID(0, 0))) {
            throw ReportException.conflict("TEMPLATE_NAME_TAKEN", "You already have a template with this name.");
        }
        ReportTemplate template = ReportTemplate.builder()
                .tenantId(tenantId)
                .ownerUserId(userId)
                .scope(ReportTemplate.PRIVATE)
                .name(name)
                .description(cleanDescription(description))
                .definition(TemplateDefinitions.toStored(definition))
                .definitionVersion(TemplateDefinitions.CURRENT_VERSION)
                .createdBy(userId)
                .updatedBy(userId)
                .build();
        flush(template);
        auditTemplate(AuditActionCatalog.REPORT_TEMPLATE_CREATED, template, null, describe(template));
        return respond(template, TemplateResponse.OWNER, Map.of(), dataSource.catalogue(tenantId), true);
    }

    /** Saved with the database's unique index as the last word: two people racing for one name get a clean 409, not a 500. */
    private void flush(ReportTemplate template) {
        try {
            templates.saveAndFlush(template);
        } catch (DataIntegrityViolationException e) {
            throw ReportException.conflict("TEMPLATE_NAME_TAKEN", "A template with this name already exists.");
        }
    }

    /** Refuses a definition the organisation's current catalogue cannot run, with one message per problem. */
    private void validate(ReportDefinition definition) {
        if (definition == null) {
            throw ReportException.unprocessable("TEMPLATE_DEFINITION_REQUIRED", "A template needs a definition.");
        }
        UUID tenantId = TenantContext.getTenantId();
        EffectiveReportSettings settings = settingsService.forTenant(tenantId);
        ZoneId zone = ReportContext.zoneFor(definition.timeZone(), settings);
        // The same builder a run uses: if this passes, the definition can run.
        dataSource.plan(definition, new ReportContext(tenantId, TenantContext.getUserId(), documentAccess.forCaller(), settings, zone,
                Instant.now()));
    }

    private void requireLimit(UUID tenantId, UUID userId, EffectiveReportSettings settings) {
        if (templates.countByTenantIdAndOwnerUserIdAndDeletedAtIsNull(tenantId, userId) >= settings.maxTemplatesPerUser()) {
            throw ReportException.conflict("TEMPLATE_LIMIT_REACHED",
                    "You can keep " + settings.maxTemplatesPerUser() + " templates. Delete one to save another.");
        }
    }

    private void requireNameFree(ReportTemplate template, String name, UUID except) {
        boolean taken = template.isOfficial()
                ? templates.officialNameTaken(template.getTenantId(), name, except)
                : templates.ownerNameTaken(template.getTenantId(), template.getOwnerUserId(), name, except);
        if (taken) {
            throw ReportException.conflict("TEMPLATE_NAME_TAKEN", "A template with this name already exists.");
        }
    }

    private String freeCopyName(UUID tenantId, UUID userId, String source) {
        String base = source.length() > NAME_MAX - 12 ? source.substring(0, NAME_MAX - 12) : source;
        String candidate = base + " (copy)";
        for (int n = 2; templates.ownerNameTaken(tenantId, userId, candidate, new UUID(0, 0)); n++) {
            candidate = base + " (copy " + n + ")";
        }
        return candidate;
    }

    private void requireEdit(ReportTemplate template, String access) {
        if (template.isOfficial()) {
            requirePublisher();
            return;
        }
        if (!TemplateResponse.OWNER.equals(access) && !TemplateResponse.EDIT.equals(access)) {
            throw ReportException.forbidden("TEMPLATE_READ_ONLY", "This template was shared with you to run, not to change.");
        }
    }

    /** Whether the caller may publish and unpublish organisation templates (so may always see every one of them). */
    private boolean canPublish() {
        return authorization.hasPermission(PermissionCodes.CRG_TEMPLATE_PUBLISH);
    }

    private void requirePublisher() {
        if (!canPublish()) {
            throw ReportException.forbidden("TEMPLATE_PUBLISH_REQUIRED", "Only people who can publish report templates can change an organisation template.");
        }
    }

    private List<Grant> cleanGrants(UUID tenantId, List<Grant> raw, boolean allowEdit) {
        Map<String, Grant> unique = new LinkedHashMap<>();
        for (Grant grant : raw == null ? List.<Grant>of() : raw) {
            if (grant == null || grant.principalId() == null || grant.principalType() == null
                    || !PRINCIPALS.contains(grant.principalType())) {
                throw ReportException.unprocessable("TEMPLATE_GRANT_INVALID", "Each grant needs a type (user, role or department) and an id.");
            }
            String level = grant.accessLevel() == null || grant.accessLevel().isBlank() ? "run" : grant.accessLevel();
            if (!LEVELS.contains(level) || (!allowEdit && "edit".equals(level))) {
                throw ReportException.unprocessable("TEMPLATE_GRANT_INVALID",
                        allowEdit ? "Access can be run or edit." : "An organisation template can only be shared to run.");
            }
            if (!store.principalExists(tenantId, grant.principalType(), grant.principalId())) {
                throw ReportException.unprocessable("TEMPLATE_GRANT_UNKNOWN_PRINCIPAL", "One of the people, roles or departments does not exist.");
            }
            // The same person twice keeps the stronger level.
            String key = grant.principalType() + ":" + grant.principalId();
            Grant existing = unique.get(key);
            if (existing == null || "edit".equals(level)) {
                unique.put(key, new Grant(grant.principalType(), grant.principalId(), level));
            }
        }
        return new ArrayList<>(unique.values());
    }

    private List<String> cleanEditableFilters(List<String> requested) {
        Set<String> clean = new LinkedHashSet<>();
        for (String field : requested == null ? List.<String>of() : requested) {
            if (field != null && !field.isBlank()) {
                clean.add(field);
            }
        }
        // Any filterable field may be offered - not only ones already in the template, so a person can ADD a filter, e.g. a
        // department - but it must be a real, filterable field.
        EffectiveCatalogue catalogue = dataSource.catalogue(TenantContext.getTenantId());
        for (String field : clean) {
            if (catalogue.find(field).filter(f -> f.filterable()).isEmpty()) {
                throw ReportException.invalid("TEMPLATE_EDITABLE_FILTER_INVALID", "A field you marked editable cannot be filtered.",
                        Map.of(field, "Not a filterable field."));
            }
        }
        return new ArrayList<>(clean);
    }

    private static String cleanName(String raw) {
        String name = raw == null ? "" : raw.strip().replaceAll("\\s+", " ");
        if (name.isEmpty() || name.length() > NAME_MAX) {
            throw ReportException.invalid("TEMPLATE_NAME_INVALID", "Give the template a name of up to " + NAME_MAX + " characters.",
                    Map.of("name", "1 to " + NAME_MAX + " characters."));
        }
        return name;
    }

    private static String cleanDescription(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.strip();
        if (text.length() > DESCRIPTION_MAX) {
            throw ReportException.invalid("TEMPLATE_DESCRIPTION_INVALID", "The description can be up to " + DESCRIPTION_MAX + " characters.",
                    Map.of("description", "Up to " + DESCRIPTION_MAX + " characters."));
        }
        return text;
    }

    private ReportTemplate find(UUID tenantId, UUID templateId) {
        return templates.findByTemplateIdAndTenantIdAndDeletedAtIsNull(templateId, tenantId)
                .orElseThrow(() -> ReportException.notFound("TEMPLATE_NOT_FOUND", "That template was not found."));
    }

    private String accessOrNotFound(UUID tenantId, UUID templateId) {
        return store.accessOf(tenantId, templateId, TenantContext.getUserId(), canPublish())
                .orElseThrow(() -> ReportException.notFound("TEMPLATE_NOT_FOUND", "That template was not found."));
    }

    private static Set<UUID> owners(ReportTemplate template) {
        return template.getOwnerUserId() == null ? Set.of() : Set.of(template.getOwnerUserId());
    }

    private TemplateResponse respond(ReportTemplate t, String access, Map<UUID, String> owners, EffectiveCatalogue catalogue,
                                     boolean detail) {
        ReportDefinition definition = TemplateDefinitions.load(t.getDefinition(), t.getDefinitionVersion());
        List<String> warnings = TemplateDefinitions.unavailable(definition, catalogue).stream()
                .map(key -> "Field \"" + key + "\" is no longer available and is left out when this template runs.").toList();
        boolean owner = TemplateResponse.OWNER.equals(access);
        List<Grant> grants = detail && (owner || (t.isOfficial() && authorization.hasPermission(PermissionCodes.CRG_TEMPLATE_PUBLISH)))
                ? store.grantsOf(t.getTemplateId()) : null;
        long schedules = detail && (owner || t.isOfficial()) ? store.liveSchedules(t.getTenantId(), t.getTemplateId()) : 0;
        return new TemplateResponse(t.getTemplateId(), t.getName(), t.getDescription(), t.getScope(), t.getOwnerUserId(),
                t.getOwnerUserId() == null ? null : owners.get(t.getOwnerUserId()), definition, t.isLocked(),
                t.getUserEditableFilters(), access, warnings, grants, schedules, t.getCreatedAt(), t.getUpdatedAt());
    }

    private static Map<String, Object> describe(ReportTemplate t) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("scope", t.getScope());
        d.put("locked", t.isLocked());
        ReportDefinition def = TemplateDefinitions.load(t.getDefinition(), t.getDefinitionVersion());
        // Field keys and operators only - never a filter value.
        d.put("filters", def.filtersOrEmpty().stream().map(f -> f.field() + " " + f.operator()).toList());
        d.put("columnCount", def.columnsOrEmpty().size());
        return d;
    }

    private void auditTemplate(String action, ReportTemplate template, Map<String, Object> before, Map<String, Object> after) {
        var event = audit.event(action)
                .objectType(AuditObjectTypes.REPORT_TEMPLATE)
                .objectId(template.getTemplateId())
                .outcome(AuditOutcome.SUCCESS);
        if (before != null) {
            event.previousValue(before);
        }
        if (after != null) {
            event.newValue(after);
        }
        event.log();
    }
}
