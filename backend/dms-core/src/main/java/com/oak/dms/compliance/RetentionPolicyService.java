package com.oak.dms.compliance;

import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.catalog.AuditObjectTypes;
import com.oak.dms.audit.enums.AuditOutcome;
import com.oak.dms.audit.service.AuditService;
import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.department.repository.DepartmentRepository;
import com.oak.dms.folder.FolderRepository;
import com.oak.dms.metadata.repository.DocumentTypeRepository;
import com.oak.dms.security.auth.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Administrators' retention rules (CRM-FR-006, 008, 010, 011): one per scope - the organisation default, a document type,
 * a department or a folder - each with its own active duration, archive duration, clock start and regulatory label, all
 * running side by side. Changing a rule never touches a document directly; the next retention run applies it.
 */
@Service
@RequiredArgsConstructor
public class RetentionPolicyService {

    private static final Set<String> SCOPES = Set.of(RetentionPolicy.SCOPE_ORGANISATION, RetentionPolicy.SCOPE_DOCUMENT_TYPE,
            RetentionPolicy.SCOPE_DEPARTMENT, RetentionPolicy.SCOPE_FOLDER);
    private static final Set<String> CLOCKS = Set.of(RetentionPolicy.CLOCK_CREATED, RetentionPolicy.CLOCK_LAST_MODIFIED);
    /** Generous but finite, so a typo like 9999 years is caught rather than silently kept for ever. */
    private static final int MAX_AMOUNT = 1000;

    /** What an administrator edits. */
    public record PolicyRequest(String name, String description, String scopeType, UUID scopeId, Boolean includeSubfolders,
                                int activeAmount, String activeUnit, int archiveAmount, String archiveUnit,
                                String clockStart, String framework, Boolean enabled,
                                Integer maxVersions, Integer versionAgeAmount, String versionAgeUnit) {
    }

    public record PolicyView(UUID policyId, String name, String description, String scopeType, UUID scopeId,
                             String scopeName, boolean includeSubfolders, int activeAmount, String activeUnit,
                             int archiveAmount, String archiveUnit, String clockStart, String framework, boolean enabled,
                             OffsetDateTime createdAt, OffsetDateTime updatedAt,
                             Integer maxVersions, Integer versionAgeAmount, String versionAgeUnit) {
    }

    /** One thing a rule can be pointed at. {@code parentId} is only set for folders, so the screen can show a path. */
    public record ScopeOption(UUID id, String name, UUID parentId) {
    }

    /** Everything a rule can target, so the rule form needs no other administration permission to fill its pickers. */
    public record Scopes(List<ScopeOption> documentTypes, List<ScopeOption> departments, List<ScopeOption> folders) {
    }

    private final RetentionPolicyRepository repository;
    private final DocumentTypeRepository documentTypeRepository;
    private final DepartmentRepository departmentRepository;
    private final FolderRepository folderRepository;
    private final AuditService auditService;
    private final com.oak.dms.security.auth.AuthorizationService authorizationService;

    @Transactional(readOnly = true)
    public List<PolicyView> list() {
        UUID tenantId = TenantContext.getTenantId();
        return repository.findByTenantIdAndDeletedFalseOrderByNameAsc(tenantId).stream()
                .map(p -> view(p, scopeName(p))).toList();
    }

    @Transactional(readOnly = true)
    public Scopes scopes() {
        UUID tenantId = TenantContext.getTenantId();
        List<ScopeOption> types = documentTypeRepository.findAllByTenantOrderedByDisplayOrder(tenantId).stream()
                .map(t -> new ScopeOption(t.getDocumentTypeId(), t.getTypeName(), null)).toList();
        List<ScopeOption> departments = departmentRepository
                .search(tenantId, null, null, org.springframework.data.domain.PageRequest.of(0, 500,
                        org.springframework.data.domain.Sort.by("name")))
                .stream().map(d -> new ScopeOption(d.getDepartmentId(), d.getName(), null)).toList();
        List<ScopeOption> folders = folderRepository.findByTenantIdAndDeletedFalseOrderByFolderNameAsc(tenantId).stream()
                .map(f -> new ScopeOption(f.getFolderId(), f.getFolderName(), f.getParentFolderId())).toList();
        return new Scopes(types, departments, folders);
    }

    @Transactional
    public PolicyView create(PolicyRequest request) {
        UUID tenantId = TenantContext.getTenantId();
        RetentionPolicy policy = RetentionPolicy.builder().policyId(UUID.randomUUID()).tenantId(tenantId)
                .createdBy(TenantContext.getUserId()).build();
        apply(policy, request, null);
        repository.save(policy);
        audit(AuditActionCatalog.RETENTION_POLICY_CREATED, policy, null);
        return view(policy, scopeName(policy));
    }

    @Transactional
    public PolicyView update(UUID policyId, PolicyRequest request) {
        RetentionPolicy policy = find(policyId);
        Map<String, Object> before = snapshot(policy);
        apply(policy, request, policyId);
        policy.setUpdatedBy(TenantContext.getUserId());
        policy.setUpdatedAt(OffsetDateTime.now());
        repository.save(policy);
        audit(AuditActionCatalog.RETENTION_POLICY_UPDATED, policy, before);
        return view(policy, scopeName(policy));
    }

    /** Documents it covered fall back to whichever other rule covers them on the next run (or are flagged, if none). */
    @Transactional
    public void delete(UUID policyId) {
        RetentionPolicy policy = find(policyId);
        policy.setDeleted(true);
        policy.setDeletedAt(OffsetDateTime.now());
        policy.setUpdatedBy(TenantContext.getUserId());
        policy.setUpdatedAt(OffsetDateTime.now());
        repository.save(policy);
        audit(AuditActionCatalog.RETENTION_POLICY_DELETED, policy, snapshot(policy));
    }

    private void apply(RetentionPolicy policy, PolicyRequest r, UUID selfId) {
        UUID tenantId = policy.getTenantId();
        String name = r.name() == null ? "" : r.name().strip();
        if (name.isEmpty() || name.length() > 200) {
            throw ComplianceException.badRequest("RETENTION_POLICY_NAME_INVALID", "Give the rule a name of up to 200 characters.");
        }
        String scope = norm(r.scopeType());
        if (!SCOPES.contains(scope)) {
            throw ComplianceException.badRequest("RETENTION_SCOPE_INVALID",
                    "A rule covers the whole organisation, a document type, a department or a folder.");
        }
        UUID scopeId = RetentionPolicy.SCOPE_ORGANISATION.equals(scope) ? null : r.scopeId();
        if (!RetentionPolicy.SCOPE_ORGANISATION.equals(scope)) {
            if (scopeId == null) {
                throw ComplianceException.badRequest("RETENTION_SCOPE_REQUIRED", "Choose which " + scope.replace('_', ' ') + " the rule covers.");
            }
            boolean exists = switch (scope) {
                case RetentionPolicy.SCOPE_DOCUMENT_TYPE ->
                        documentTypeRepository.findByDocumentTypeIdAndTenantIdAndDeletedFalse(scopeId, tenantId).isPresent();
                case RetentionPolicy.SCOPE_DEPARTMENT ->
                        departmentRepository.findByDepartmentIdAndTenantIdAndDeletedFalse(scopeId, tenantId).isPresent();
                default -> folderRepository.findByFolderIdAndTenantIdAndDeletedFalse(scopeId, tenantId).isPresent();
            };
            if (!exists) {
                throw new ResourceNotFoundException(scope.replace('_', ' '), scopeId);
            }
        }
        String activeUnit = norm(r.activeUnit());
        String archiveUnit = norm(r.archiveUnit());
        if (r.activeAmount() < 1 || r.activeAmount() > MAX_AMOUNT || !RetentionSpan.UNITS.contains(activeUnit)) {
            throw ComplianceException.badRequest("RETENTION_ACTIVE_PERIOD_INVALID",
                    "The active period must be between 1 and " + MAX_AMOUNT + " days, months or years.");
        }
        if (r.archiveAmount() < 0 || r.archiveAmount() > MAX_AMOUNT || !RetentionSpan.UNITS.contains(archiveUnit)) {
            throw ComplianceException.badRequest("RETENTION_ARCHIVE_PERIOD_INVALID",
                    "The archive period must be between 0 and " + MAX_AMOUNT + " days, months or years (0 = none).");
        }
        String clock = r.clockStart() == null || r.clockStart().isBlank() ? RetentionPolicy.CLOCK_CREATED : norm(r.clockStart());
        if (!CLOCKS.contains(clock)) {
            throw ComplianceException.badRequest("RETENTION_CLOCK_INVALID", "The clock starts at the document's creation or its last change.");
        }
        String framework = r.framework() == null || r.framework().isBlank() ? null : r.framework().strip();
        if (framework != null && framework.length() > 120) {
            throw ComplianceException.badRequest("RETENTION_FRAMEWORK_INVALID", "The regulatory label can be up to 120 characters.");
        }
        boolean taken = scopeId == null
                ? repository.findByTenantIdAndDeletedFalseOrderByNameAsc(tenantId).stream()
                .anyMatch(p -> scope.equals(p.getScopeType()) && p.getScopeId() == null && !p.getPolicyId().equals(selfId))
                : repository.findByTenantIdAndDeletedFalseOrderByNameAsc(tenantId).stream()
                .anyMatch(p -> scope.equals(p.getScopeType()) && scopeId.equals(p.getScopeId()) && !p.getPolicyId().equals(selfId));
        if (taken) {
            throw ComplianceException.conflict("RETENTION_SCOPE_TAKEN",
                    "There is already a rule for that " + (scopeId == null ? "organisation default" : scope.replace('_', ' '))
                            + ". Edit it instead.");
        }

        policy.setName(name);
        policy.setDescription(r.description() == null || r.description().isBlank() ? null : r.description().strip());
        policy.setScopeType(scope);
        policy.setScopeId(scopeId);
        policy.setIncludeSubfolders(r.includeSubfolders() == null || r.includeSubfolders());
        policy.setActiveAmount(r.activeAmount());
        policy.setActiveUnit(activeUnit);
        policy.setArchiveAmount(r.archiveAmount());
        policy.setArchiveUnit(archiveUnit);
        policy.setClockStart(clock);
        policy.setFramework(framework);
        policy.setEnabled(r.enabled() == null || r.enabled());
        applyVersionLimits(policy, r);
    }

    /**
     * The limits on old versions: at most N kept, and/or superseded versions older than a period removed. Each is optional
     * (blank = no limit). Setting or changing them needs the DVC "configure version retention" permissions - the maximum
     * and the period are separate permissions - on top of the permission to edit the rule.
     */
    private void applyVersionLimits(RetentionPolicy policy, PolicyRequest r) {
        Integer max = r.maxVersions();
        if (max != null && (max < 1 || max > 10_000)) {
            throw ComplianceException.badRequest("RETENTION_MAX_VERSIONS_INVALID", "Keep between 1 and 10000 versions, or leave it blank for no limit.");
        }
        Integer ageAmount = r.versionAgeAmount();
        String ageUnit = r.versionAgeUnit() == null || r.versionAgeUnit().isBlank() ? null : norm(r.versionAgeUnit());
        if (ageAmount == null) {
            ageUnit = null;
        } else if (ageAmount < 1 || ageAmount > MAX_AMOUNT || ageUnit == null || !RetentionSpan.UNITS.contains(ageUnit)) {
            throw ComplianceException.badRequest("RETENTION_VERSION_AGE_INVALID",
                    "Remove old versions after 1 to " + MAX_AMOUNT + " days, months or years, or leave it blank for no limit.");
        }
        boolean maxChanged = !java.util.Objects.equals(max, policy.getMaxVersions());
        boolean ageChanged = !java.util.Objects.equals(ageAmount, policy.getVersionAgeAmount())
                || !java.util.Objects.equals(ageUnit, policy.getVersionAgeUnit());
        if (maxChanged && !authorizationService.hasPermission(com.oak.dms.security.rbac.catalog.PermissionCodes.DVC_SET_MAX_VERSIONS)) {
            throw new org.springframework.security.access.AccessDeniedException("You are not allowed to set the maximum number of versions.");
        }
        if (ageChanged && !authorizationService.hasPermission(com.oak.dms.security.rbac.catalog.PermissionCodes.DVC_SET_VERSION_PERIOD)) {
            throw new org.springframework.security.access.AccessDeniedException("You are not allowed to set the version retention period.");
        }
        policy.setMaxVersions(max);
        policy.setVersionAgeAmount(ageAmount);
        policy.setVersionAgeUnit(ageUnit);
    }

    private RetentionPolicy find(UUID policyId) {
        return repository.findByPolicyIdAndTenantIdAndDeletedFalse(policyId, TenantContext.getTenantId())
                .orElseThrow(() -> new ResourceNotFoundException("Retention policy", policyId));
    }

    private String scopeName(RetentionPolicy p) {
        UUID tenantId = p.getTenantId();
        if (p.getScopeId() == null) {
            return "Whole organisation";
        }
        return switch (p.getScopeType()) {
            case RetentionPolicy.SCOPE_DOCUMENT_TYPE -> documentTypeRepository
                    .findByDocumentTypeIdAndTenantIdAndDeletedFalse(p.getScopeId(), tenantId)
                    .map(t -> t.getTypeName()).orElse("(deleted document type)");
            case RetentionPolicy.SCOPE_DEPARTMENT -> departmentRepository
                    .findByDepartmentIdAndTenantIdAndDeletedFalse(p.getScopeId(), tenantId)
                    .map(d -> d.getName()).orElse("(deleted department)");
            default -> folderRepository.findByFolderIdAndTenantIdAndDeletedFalse(p.getScopeId(), tenantId)
                    .map(f -> f.getFolderName()).orElse("(deleted folder)");
        };
    }

    private static PolicyView view(RetentionPolicy p, String scopeName) {
        return new PolicyView(p.getPolicyId(), p.getName(), p.getDescription(), p.getScopeType(), p.getScopeId(), scopeName,
                p.isIncludeSubfolders(), p.getActiveAmount(), p.getActiveUnit(), p.getArchiveAmount(), p.getArchiveUnit(),
                p.getClockStart(), p.getFramework(), p.isEnabled(), p.getCreatedAt(), p.getUpdatedAt(),
                p.getMaxVersions(), p.getVersionAgeAmount(), p.getVersionAgeUnit());
    }

    private void audit(String action, RetentionPolicy p, Map<String, Object> before) {
        var event = auditService.event(action).objectType(AuditObjectTypes.TENANT).objectId(p.getPolicyId())
                .resourceName(p.getName()).outcome(AuditOutcome.SUCCESS).newValue(snapshot(p));
        if (before != null) {
            event.previousValue(before);
        }
        event.log();
    }

    private static Map<String, Object> snapshot(RetentionPolicy p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", p.getName());
        m.put("scopeType", p.getScopeType());
        m.put("scopeId", p.getScopeId() == null ? null : p.getScopeId().toString());
        m.put("active", p.getActiveAmount() + " " + p.getActiveUnit());
        m.put("archive", p.getArchiveAmount() + " " + p.getArchiveUnit());
        m.put("clockStart", p.getClockStart());
        m.put("framework", p.getFramework());
        m.put("enabled", p.isEnabled());
        m.put("maxVersions", p.getMaxVersions());
        m.put("versionAge", p.getVersionAgeAmount() == null ? null : p.getVersionAgeAmount() + " " + p.getVersionAgeUnit());
        return m;
    }

    private static String norm(String s) {
        return s == null ? "" : s.strip().toLowerCase(Locale.ROOT);
    }
}
