package com.oak.dms.search.preset;

import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.catalog.AuditObjectTypes;
import com.oak.dms.audit.enums.AuditOutcome;
import com.oak.dms.audit.service.AuditService;
import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.search.api.SearchApiException;
import com.oak.dms.search.config.SearchProperties;
import com.oak.dms.search.query.SearchFilterValidator;
import com.oak.dms.search.query.SearchRequest;
import com.oak.dms.search.query.SearchResponse;
import com.oak.dms.search.query.SearchResponse.Notice;
import com.oak.dms.search.query.SearchService;
import com.oak.dms.security.auth.AuthorizationService;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.security.rbac.catalog.PermissionCodes;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Saved search presets: private to their creator, versioned, capped, audited. A preset stores criteria only, never
 * anything about documents, and nothing here logs criteria, keywords or names (ids and counts only). Running a preset
 * goes through the very same validator and search path as a typed search, so visibility, fallback, totals and notices
 * behave identically.
 *
 * <p>Anything that is not the caller's own preset (unknown, someone else's, another organisation's) is the same 404:
 * the queries are scoped by tenant AND user, so there is nothing else to answer with, for administrators too.
 */
@Service
public class SavedSearchService {

    private static final int MAX_NAME = 100;

    public record Preset(UUID presetId, String name, Map<String, Object> criteria, int schemaVersion, boolean runnable,
                         OffsetDateTime createdAt, OffsetDateTime updatedAt) {
        @Override
        public String toString() {
            return "Preset[" + presetId + "]";
        }
    }

    public record Summary(UUID presetId, String name, String summary, OffsetDateTime updatedAt, boolean runnable) {
        @Override
        public String toString() {
            return "Summary[" + presetId + "]";
        }
    }

    public record Listing(List<Summary> items, int count, int limit) {
    }

    public record Run(Ref preset, Map<String, Object> criteria, List<Notice> warnings, SearchResponse result) {
        @Override
        public String toString() {
            return "Run[" + preset.presetId() + "]";
        }
    }

    public record Ref(UUID presetId, String name) {
        @Override
        public String toString() {
            return "Ref[" + presetId + "]";
        }
    }

    private final SavedSearchRepository repository;
    private final PresetLimitService limits;
    private final SearchFilterValidator validator;
    private final SearchService search;
    private final SearchProperties props;
    private final AuthorizationService authz;
    private final AuditService auditService;

    public SavedSearchService(SavedSearchRepository repository, PresetLimitService limits, SearchFilterValidator validator,
                              SearchService search, SearchProperties props, AuthorizationService authz,
                              AuditService auditService) {
        this.repository = repository;
        this.limits = limits;
        this.validator = validator;
        this.search = search;
        this.props = props;
        this.authz = authz;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public Listing list() {
        UUID tenantId = TenantContext.getTenantId();
        List<Summary> items = new ArrayList<>();
        for (SavedSearchRepository.Row r : repository.list(tenantId, TenantContext.getUserId())) {
            items.add(new Summary(r.presetId(), r.name(), summaryOf(r), r.updatedAt(),
                    PresetCriteriaMigrator.isSupported(r.schemaVersion())));
        }
        return new Listing(items, items.size(), limits.limit(tenantId));
    }

    @Transactional(readOnly = true)
    public Preset get(UUID id) {
        return toPreset(require(id));
    }

    @Transactional
    public Preset create(String rawName, Map<String, Object> rawCriteria, boolean overwrite) {
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();
        String name = name(rawName);
        PresetCriteria criteria = checked(tenantId, rawCriteria);

        repository.lockUser(tenantId, userId);
        var existing = repository.findByName(tenantId, userId, name);
        if (existing.isPresent()) {
            if (!overwrite) {
                throw nameExists(existing.get().presetId());
            }
            // Replacing someone's own preset is an update as well as a create.
            if (!authz.hasPermission(PermissionCodes.DSR_MANAGE_PRESETS_UPDATE)) {
                throw new AccessDeniedException("Replacing a saved search needs the update permission.");
            }
            var row = repository.update(tenantId, userId, existing.get().presetId(), name, criteria.toMap()).orElseThrow();
            audit(AuditActionCatalog.SEARCH_PRESET_UPDATED, row.presetId(), criteria);
            return toPreset(row);
        }
        int limit = limits.limit(tenantId);
        if (repository.count(tenantId, userId) >= limit) {
            throw new SearchApiException(HttpStatus.CONFLICT, "PRESET_LIMIT_REACHED",
                    "You have reached the limit of " + limit + " saved searches. Delete one to save a new one.",
                    Map.of("limit", limit));
        }
        try {
            var row = repository.insert(tenantId, userId, name, criteria.toMap());
            audit(AuditActionCatalog.SEARCH_PRESET_CREATED, row.presetId(), criteria);
            return toPreset(row);
        } catch (DuplicateKeyException e) {
            throw nameExists(repository.findByName(tenantId, userId, name).map(SavedSearchRepository.Row::presetId).orElse(null));
        }
    }

    @Transactional
    public Preset update(UUID id, String rawName, Map<String, Object> rawCriteria) {
        if (rawName == null && rawCriteria == null) {
            throw PresetCriteria.invalid("Give a new name, new criteria, or both.");
        }
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();
        SavedSearchRepository.Row current = require(id);
        String name = rawName == null ? null : name(rawName);
        PresetCriteria criteria = rawCriteria == null ? null : checked(tenantId, rawCriteria);

        repository.lockUser(tenantId, userId);
        if (name != null) {
            var clash = repository.findByName(tenantId, userId, name);
            if (clash.isPresent() && !clash.get().presetId().equals(current.presetId())) {
                throw nameExists(clash.get().presetId());
            }
        }
        try {
            var row = repository.update(tenantId, userId, id, name, criteria == null ? null : criteria.toMap())
                    .orElseThrow(() -> new ResourceNotFoundException("Saved search", id));
            audit(AuditActionCatalog.SEARCH_PRESET_UPDATED, row.presetId(), criteria);
            return toPreset(row);
        } catch (DuplicateKeyException e) {
            throw nameExists(null);
        }
    }

    @Transactional
    public void delete(UUID id) {
        if (!repository.delete(TenantContext.getTenantId(), TenantContext.getUserId(), id)) {
            throw new ResourceNotFoundException("Saved search", id);
        }
        audit(AuditActionCatalog.SEARCH_PRESET_DELETED, id, null);
    }

    /**
     * Runs a preset for the caller as of now. Outdated filter values (a deleted department, type, folder, a removed
     * metadata field) are dropped and named in {@code warnings}; archived states are dropped, with a warning, if the
     * caller no longer holds the archive permission. The stored preset is never modified by a run.
     */
    public Run run(UUID id, Integer size) {
        UUID tenantId = TenantContext.getTenantId();
        SavedSearchRepository.Row row = require(id);
        PresetCriteria criteria = PresetCriteria.parse(PresetCriteriaMigrator.migrate(row.criteria(), row.schemaVersion()));
        List<Notice> warnings = new ArrayList<>();
        boolean canArchive = authz.hasPermission(PermissionCodes.CRM_VIEW_ARCHIVE);
        if (criteria.wantsArchived() && !canArchive) {
            criteria = criteria.withoutArchived();
            warnings.add(Notice.of("ARCHIVE_NOT_ALLOWED",
                    "Inactive and archived documents were left out: you no longer have permission to search them."));
        }
        SearchFilterValidator.Validated v = validator.validate(tenantId, criteria.toRequest(pageSize(size)));
        warnings.addAll(v.notices());
        SearchRequest request = v.request();
        Map<String, Object> resolved = PresetCriteria.fromRequest(request).toMap();
        SearchResponse result = search.search(request, canArchive);
        return new Run(new Ref(row.presetId(), row.name()), resolved, warnings, result);
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    /** Parses, requires something to search for, and refuses anything stale or archived-without-permission up front. */
    private PresetCriteria checked(UUID tenantId, Map<String, Object> raw) {
        PresetCriteria criteria = PresetCriteria.parse(raw);
        if (!criteria.hasCriteria()) {
            throw new SearchApiException(HttpStatus.BAD_REQUEST, "PRESET_NO_CRITERIA",
                    "Add a keyword or a filter before saving a search.");
        }
        if (criteria.wantsArchived() && !authz.hasPermission(PermissionCodes.CRM_VIEW_ARCHIVE)) {
            throw new AccessDeniedException("Saving a search for inactive or archived documents needs the archive permission.");
        }
        SearchFilterValidator.Validated v = validator.validate(tenantId, criteria.toRequest(props.getQuery().getDefaultPageSize()));
        if (!v.notices().isEmpty()) {
            throw PresetCriteria.invalid("Some filters refer to something that does not exist: "
                    + String.join(", ", v.notices().get(0).details()) + ".");
        }
        return PresetCriteria.fromRequest(v.request());
    }

    private SavedSearchRepository.Row require(UUID id) {
        return repository.find(TenantContext.getTenantId(), TenantContext.getUserId(), id)
                .orElseThrow(() -> new ResourceNotFoundException("Saved search", id));
    }

    private static String name(String raw) {
        String name = raw == null ? "" : raw.strip();
        if (name.isEmpty() || name.length() > MAX_NAME) {
            throw PresetCriteria.invalid("A saved search needs a name of 1 to " + MAX_NAME + " characters.");
        }
        return name;
    }

    private int pageSize(Integer size) {
        int def = props.getQuery().getDefaultPageSize();
        return size == null ? def : Math.max(1, Math.min(size, props.getQuery().getMaxPageSize()));
    }

    private static SearchApiException nameExists(UUID existingId) {
        return new SearchApiException(HttpStatus.CONFLICT, "PRESET_NAME_EXISTS",
                "You already have a saved search with that name.", existingId == null ? null : Map.of("presetId", existingId));
    }

    private Preset toPreset(SavedSearchRepository.Row r) {
        boolean runnable = PresetCriteriaMigrator.isSupported(r.schemaVersion());
        // A preset from a newer version is shown as stored; it just cannot be run here.
        Map<String, Object> criteria = runnable ? PresetCriteriaMigrator.migrate(r.criteria(), r.schemaVersion()) : r.criteria();
        return new Preset(r.presetId(), r.name(), criteria, r.schemaVersion(), runnable, r.createdAt(), r.updatedAt());
    }

    private static String summaryOf(SavedSearchRepository.Row r) {
        if (!PresetCriteriaMigrator.isSupported(r.schemaVersion())) {
            return "Saved by a newer version";
        }
        try {
            return PresetCriteria.parse(PresetCriteriaMigrator.migrate(r.criteria(), r.schemaVersion())).summary();
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** The audit trail names the criteria in use, never their values, and never the preset's name. */
    private void audit(String action, UUID presetId, PresetCriteria criteria) {
        var event = auditService.event(action)
                .objectType(AuditObjectTypes.SEARCH_PRESET)
                .objectId(presetId)
                .outcome(AuditOutcome.SUCCESS);
        if (criteria != null) {
            event.newValue(Map.of("criteria", criteria.fieldNames()));
        }
        event.log();
    }
}
