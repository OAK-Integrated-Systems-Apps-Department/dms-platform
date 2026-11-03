package com.oak.dms.compliance;

import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.catalog.AuditObjectTypes;
import com.oak.dms.audit.enums.AuditOutcome;
import com.oak.dms.audit.service.AuditService;
import com.oak.dms.common.dto.PageResponse;
import com.oak.dms.security.auth.TenantContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Retention rules, the organisation's retention settings, the review queue, and running the job now. */
@RestController
@RequestMapping("/api/v1/retention")
@RequiredArgsConstructor
@Tag(name = "Records retention", description = "Retention rules per organisation, document type, department or folder")
public class RetentionController {

    private static final String P = "T(com.oak.dms.security.rbac.catalog.PermissionCodes).";
    private static final String CAN_SEE_RULES = "@authz.hasAnyPermission(" + P + "CRM_POLICY_VIEW, " + P + "CRM_POLICY_CREATE, "
            + P + "CRM_POLICY_UPDATE, " + P + "CRM_POLICY_DELETE, " + P + "CRM_CONFIGURE_RETENTION)";

    public record SettingsRequest(boolean automationEnabled, String precedence, String whenNoPolicy, boolean requireStatusReason,
                                  Integer versionGraceDays) {
    }

    private final RetentionPolicyService policies;
    private final RetentionSettingsService settings;
    private final DocumentLifecycleService lifecycle;
    private final RetentionEngine engine;
    private final AuditService auditService;
    private final VersionRetentionService versionRetention;

    @GetMapping("/policies")
    @PreAuthorize(CAN_SEE_RULES)
    @Operation(summary = "My organisation's retention rules")
    public List<RetentionPolicyService.PolicyView> listPolicies() {
        return policies.list();
    }

    @GetMapping("/scopes")
    @PreAuthorize(CAN_SEE_RULES)
    @Operation(summary = "The document types, departments and folders a retention rule can cover")
    public RetentionPolicyService.Scopes scopes() {
        return policies.scopes();
    }

    @PostMapping("/policies")
    @PreAuthorize("@authz.hasPermission(" + P + "CRM_POLICY_CREATE)")
    @Operation(summary = "Create a retention rule",
            description = "scopeType is organisation (the default for everything), document_type, department or folder - one rule "
                    + "per scope (409 RETENTION_SCOPE_TAKEN). Active and archive periods are an amount of days, months or "
                    + "years; the clock starts at created or last_modified. Audited.")
    public RetentionPolicyService.PolicyView createPolicy(@RequestBody RetentionPolicyService.PolicyRequest request) {
        return policies.create(request);
    }

    @PutMapping("/policies/{policyId}")
    @PreAuthorize("@authz.hasPermission(" + P + "CRM_POLICY_UPDATE)")
    @Operation(summary = "Change a retention rule", description = "Applied to documents by the next retention run. Audited.")
    public RetentionPolicyService.PolicyView updatePolicy(@PathVariable UUID policyId,
                                                          @RequestBody RetentionPolicyService.PolicyRequest request) {
        return policies.update(policyId, request);
    }

    @DeleteMapping("/policies/{policyId}")
    @PreAuthorize("@authz.hasPermission(" + P + "CRM_POLICY_DELETE)")
    @Operation(summary = "Delete a retention rule", description = "Documents it covered fall back to another rule, or are flagged. Audited.")
    public void deletePolicy(@PathVariable UUID policyId) {
        policies.delete(policyId);
    }

    @GetMapping("/settings")
    @PreAuthorize(CAN_SEE_RULES)
    @Operation(summary = "My organisation's retention settings (defaults until an administrator changes them)")
    public RetentionSettingsService.Settings getSettings() {
        return settings.mine();
    }

    @PutMapping("/settings")
    @PreAuthorize("@authz.hasPermission(" + P + "CRM_CONFIGURE_RETENTION)")
    @Operation(summary = "Change the retention settings",
            description = "automationEnabled, precedence (longest | most_specific), whenNoPolicy (flag | ignore), "
                    + "requireStatusReason, versionGraceDays (optional: how long a version removed by retention stays restorable). Audited.")
    public RetentionSettingsService.Settings saveSettings(@RequestBody SettingsRequest request) {
        return settings.save(new RetentionSettingsService.Settings(request.automationEnabled(), request.precedence(),
                request.whenNoPolicy(), request.requireStatusReason(),
                // Left out = unchanged (older clients do not send it).
                request.versionGraceDays() != null ? request.versionGraceDays() : settings.mine().versionGraceDays()));
    }

    @GetMapping("/review")
    @PreAuthorize("@authz.hasPermission(" + P + "CRM_REVIEW_RETENTION)")
    @Operation(summary = "Documents past their total retention, waiting for an administrator's decision")
    public PageResponse<DocumentLifecycleService.ReviewItem> review(@PageableDefault(size = 25) Pageable pageable) {
        return PageResponse.from(lifecycle.awaitingReview(pageable));
    }

    @GetMapping("/without-policy")
    @PreAuthorize("@authz.hasPermission(" + P + "CRM_REVIEW_RETENTION)")
    @Operation(summary = "Documents no retention rule covers, waiting for an administrator to assign one")
    public PageResponse<DocumentLifecycleService.ReviewItem> withoutPolicy(@PageableDefault(size = 25) Pageable pageable) {
        return PageResponse.from(lifecycle.withoutPolicy(pageable));
    }

    @GetMapping("/versions")
    @PreAuthorize("@authz.hasPermission(" + P + "CRM_REVIEW_RETENTION)")
    @Operation(summary = "Old versions removed by retention that can still be restored",
            description = "Hidden from the version history; their files are destroyed for good when the grace period ends.")
    public PageResponse<VersionRetentionService.RemovedVersion> removedVersions(@PageableDefault(size = 25) Pageable pageable) {
        return PageResponse.from(versionRetention.removed(pageable));
    }

    @PostMapping("/versions/{versionId}/restore")
    @PreAuthorize("@authz.hasPermission(" + P + "CRM_REVIEW_RETENTION)")
    @Operation(summary = "Bring a removed version back into the version history",
            description = "Also exempts it from further trimming. 409 VERSION_NOT_RESTORABLE once its files are destroyed. Audited.")
    public void restoreVersion(@PathVariable UUID versionId) {
        versionRetention.restore(versionId);
    }

    @PostMapping("/run")
    @PreAuthorize("@authz.hasPermission(" + P + "CRM_CONFIGURE_RETENTION)")
    @Operation(summary = "Apply the retention rules to my organisation's documents now",
            description = "The same pass the scheduled job makes; safe to repeat. Returns how many documents were archived, "
                    + "flagged for review and found without a rule. Audited.")
    public RetentionEngine.Result runNow() {
        RetentionEngine.Result result = engine.runForTenant(TenantContext.getTenantId());
        auditService.event(AuditActionCatalog.RETENTION_RUN_REQUESTED).objectType(AuditObjectTypes.TENANT)
                .objectId(TenantContext.getTenantId()).outcome(AuditOutcome.SUCCESS)
                .newValue(java.util.Map.of("archived", result.archived(), "flagged", result.flagged(),
                        "withoutPolicy", result.withoutPolicy(), "versionsRemoved", result.versionsRemoved(),
                        "versionsDestroyed", result.versionsDestroyed(), "failed", result.failed())).log();
        return result;
    }
}
