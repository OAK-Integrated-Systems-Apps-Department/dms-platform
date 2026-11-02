package com.oak.dms.ownership.service;

import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.catalog.AuditObjectTypes;
import com.oak.dms.audit.enums.AuditOutcome;
import com.oak.dms.audit.service.AuditService;
import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.folder.DocumentAccessService;
import com.oak.dms.folder.DocumentAccessService.DocumentAccessView;
import com.oak.dms.ownership.dto.BatchSummaryResponse;
import com.oak.dms.ownership.dto.BulkTransferRequest;
import com.oak.dms.ownership.dto.DepartmentRef;
import com.oak.dms.ownership.dto.OffboardRequest;
import com.oak.dms.ownership.dto.OffboardResponse;
import com.oak.dms.ownership.dto.OffboardingPreviewResponse;
import com.oak.dms.ownership.dto.OffboardingPreviewResponse.SuggestedTarget;
import com.oak.dms.ownership.dto.OwnedDocumentsResponse;
import com.oak.dms.ownership.dto.PersonRef;
import com.oak.dms.ownership.dto.WorkFlags;
import com.oak.dms.ownership.entity.OwnershipTransfer;
import com.oak.dms.ownership.helper.OwnershipException;
import com.oak.dms.ownership.helper.OwnershipLocks;
import com.oak.dms.ownership.helper.OwnershipNames;
import com.oak.dms.ownership.helper.OwnershipScope;
import com.oak.dms.ownership.helper.OwnershipScope.Scope;
import com.oak.dms.security.auth.AuthorizationService;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.security.auth.UserPermissions;
import com.oak.dms.security.rbac.catalog.PermissionCodes;
import com.oak.dms.user.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Offboarding a user (OTUA-FR-006, DOT-FR-003): transfer everything they own - to a named user or the department's
 * holding owner - and then deactivate the account, in one transaction. Their open approval tasks are flagged, never
 * reassigned (D6): the admin must acknowledge how many will stay stuck. If Keycloak refuses to disable the account
 * the whole thing, transfers included, rolls back.
 */
@Service
@RequiredArgsConstructor
public class OffboardingService {

    private final OwnershipLocks locks;
    private final OwnershipScope ownershipScope;
    private final OwnershipQueryService queryService;
    private final OwnershipBatchService batchService;
    private final OpenWorkItems workItems;
    private final HoldingOwnerResolver holdingResolver;
    private final OwnershipNames names;
    private final DocumentAccessService documentAccess;
    private final AuditService auditService;
    private final UserService userService;
    private final UserPermissions userPermissions;
    private final AuthorizationService authorization;
    private final NamedParameterJdbcTemplate jdbc;

    private record UserRow(UUID userId, UUID departmentId, boolean deleted) {
    }

    @Transactional(readOnly = true)
    public OffboardingPreviewResponse preview(UUID userId) {
        UUID tenantId = TenantContext.getTenantId();
        UUID actorId = TenantContext.getUserId();
        Scope scope = ownershipScope.requireNonEmpty(tenantId, actorId);
        UserRow row = find(tenantId, userId, false);
        if (!ownershipScope.ownerInScope(tenantId, scope, userId)) {
            throw OwnershipException.scopeDenied();
        }
        OwnedDocumentsResponse owned = queryService.owned(userId, null, null, null, null, 0, 1);
        long binned = queryService.owned(userId, null, null, null, true, 0, 1).total();
        DocumentAccessView view = scope.unrestricted() ? null : documentAccess.forCaller();
        WorkFlags flags = workItems.flagsFor(tenantId, userId, view);

        List<String> blockers = new ArrayList<>();
        if (row.deleted()) {
            blockers.add("ALREADY_DEACTIVATED");
        } else if (userPermissions.isTenantAdmin(tenantId, userId)) {
            blockers.add("TENANT_ADMIN_ACCOUNT");
        }
        boolean allowed = authorization.hasPermission(PermissionCodes.DOT_BULK_EXECUTE)
                && authorization.hasPermission(PermissionCodes.OTUA_USERS_DEACTIVATE);
        if (!allowed) {
            blockers.add("MISSING_PERMISSION");
        }
        SuggestedTarget suggested = null;
        if (!row.deleted()) {
            try {
                var chosen = holdingResolver.resolve(tenantId, row.departmentId(), userId);
                DepartmentRef holdingDepartment = chosen.departmentId() == null ? null : names.departments(tenantId,
                        Collections.singletonList(chosen.departmentId())).get(chosen.departmentId());
                suggested = new SuggestedTarget(chosen.userId(), chosen.name(), chosen.source(), holdingDepartment);
            } catch (OwnershipException e) {
                suggested = null;
            }
        }
        Map<UUID, PersonRef> people = names.persons(tenantId, List.of(userId));
        DepartmentRef department = row.departmentId() == null ? null : names.departments(tenantId,
                Collections.singletonList(row.departmentId())).get(row.departmentId());
        return new OffboardingPreviewResponse(people.get(userId), department, owned.totalOwned(), owned.visibleCount(),
                owned.hiddenCount(), binned, suggested, flags, blockers.isEmpty(), blockers);
    }

    @Transactional
    public OffboardResponse offboard(UUID userId, OffboardRequest request) {
        UUID tenantId = TenantContext.getTenantId();
        UUID actorId = TenantContext.getUserId();
        Scope scope = ownershipScope.requireNonEmpty(tenantId, actorId);
        String mode = request.mode();
        if (!"transfer".equals(mode) && !"holding".equals(mode)) {
            throw OwnershipException.invalid("OFFBOARD_MODE_INVALID", "mode must be \"transfer\" or \"holding\".");
        }
        locks.applyLockTimeout();
        UserRow row = find(tenantId, userId, true);          // locks the user row: a second offboarding waits, then finds it gone
        if (!ownershipScope.ownerInScope(tenantId, scope, userId)) {
            throw OwnershipException.scopeDenied();
        }
        if (userPermissions.isTenantAdmin(tenantId, userId)) {
            throw OwnershipException.invalid("OFFBOARD_TENANT_ADMIN", "The tenant admin account can't be offboarded.");
        }

        DocumentAccessView view = scope.unrestricted() ? null : documentAccess.forCaller();
        WorkFlags flags = workItems.flagsFor(tenantId, userId, view);
        int stuck = flags.stuckTaskCount();
        int acknowledged = request.acknowledgedStuckTasks() == null ? 0 : request.acknowledgedStuckTasks();
        if (stuck > 0 && acknowledged != stuck) {
            throw OwnershipException.conflict("OFFBOARD_CONFIRMATION_REQUIRED",
                    stuck + " open approval task(s) held by this user will stay stuck. Confirm the number to go on.", flags);
        }

        BulkTransferRequest.TargetSpec target = "holding".equals(mode)
                ? new BulkTransferRequest.TargetSpec("holding", null, null, row.departmentId())
                : new BulkTransferRequest.TargetSpec("user", request.newOwnerUserId(), request.newOwnerDepartmentId(), null);
        var transfer = new BulkTransferRequest(userId, null, "all", null, null, null, target,
                OwnershipTransfer.REASON_OFFBOARDING, request.grantAccess(), request.note());
        BatchSummaryResponse batch = batchService.executeFor(tenantId, actorId, transfer, stuck);

        String userName = names.nameOf(tenantId, userId);
        userService.deactivateUser(userId, request.reason());

        Map<String, Object> value = new LinkedHashMap<>();
        value.put("mode", mode);
        value.put("batchId", batch.batchId());
        value.put("transferred", batch.transferredCount());
        value.put("skipped", batch.skippedCount());
        value.put("newOwnerUserId", batch.newOwner() == null ? null : batch.newOwner().userId());
        value.put("flaggedTasks", stuck);
        value.put("startedRuns", flags.startedRunCount());
        auditService.event(AuditActionCatalog.USER_OFFBOARDED)
                .objectType(AuditObjectTypes.USER)
                .objectId(userId)
                .resourceName(userName)
                .outcome(AuditOutcome.SUCCESS)
                .newValue(value)
                .reason(request.reason())
                .log();
        return new OffboardResponse(batch, flags, true);
    }

    /** The user row (tenant-scoped; another tenant's user is 404). {@code lock} also locks it and refuses a deactivated one. */
    private UserRow find(UUID tenantId, UUID userId, boolean lock) {
        List<UserRow> rows = jdbc.query("select user_id, department_id, is_deleted from users where tenant_id = :t and user_id = :u"
                        + (lock ? " for update" : ""), new MapSqlParameterSource("t", tenantId).addValue("u", userId),
                (rs, i) -> new UserRow(rs.getObject("user_id", UUID.class), rs.getObject("department_id", UUID.class),
                        rs.getBoolean("is_deleted")));
        if (rows.isEmpty() || (lock && rows.get(0).deleted())) {
            throw new ResourceNotFoundException("User", userId);
        }
        return rows.get(0);
    }
}
