package com.oak.dms.ownership.service;

import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.folder.DocumentAccessService;
import com.oak.dms.folder.DocumentAccessService.DocumentAccessView;
import com.oak.dms.ownership.dto.DepartmentRef;
import com.oak.dms.ownership.dto.OwnedDocumentItem;
import com.oak.dms.ownership.dto.OwnedDocumentsResponse;
import com.oak.dms.ownership.dto.PersonRef;
import com.oak.dms.ownership.helper.OwnershipException;
import com.oak.dms.ownership.helper.OwnershipScope;
import com.oak.dms.ownership.helper.OwnershipScope.Scope;
import com.oak.dms.security.auth.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The review list (DOT-FR-005, DOT-NFR-004): the documents a user or department owns, as the caller may see
 * them. Admins see everything not purged, Recycle Bin and templates flagged. Anyone else sees only what they can
 * open, for owners in departments they head, plus a count of the rest.
 */
@Service
@RequiredArgsConstructor
public class OwnershipQueryService {

    static final int MAX_PAGE_SIZE = 200;
    /** %s is {@link OwnedDocumentsSql#where()}: fixed fragments only, every value bound as a :parameter. */
    private static final String COUNT = "select count(*) from documents d where %s";

    private final NamedParameterJdbcTemplate jdbc;
    private final OwnershipScope ownershipScope;
    private final DocumentAccessService documentAccess;

    @Transactional(readOnly = true)
    public OwnedDocumentsResponse owned(UUID userId, UUID departmentId, String q, String state, Boolean recycleBin,
                                        int page, int size) {
        UUID tenantId = TenantContext.getTenantId();
        Scope scope = ownershipScope.requireNonEmpty(tenantId, TenantContext.getUserId());
        if (userId == null && departmentId == null) {
            throw OwnershipException.invalid("OWNERSHIP_OWNER_REQUIRED", "Choose a user or a department to list owned documents.");
        }
        requireInScope(tenantId, scope, userId, departmentId);
        int pageSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
        int pageIndex = Math.max(0, page);
        Set<UUID> owners = ownershipScope.usersInScope(tenantId, scope);
        DocumentAccessView view = scope.unrestricted() ? null : documentAccess.forCaller();

        long totalOwned = count(new OwnedDocumentsSql(tenantId).owner(userId).department(departmentId)
                .scope(scope, owners, view, false));
        long visibleOwned = scope.unrestricted() ? totalOwned : count(new OwnedDocumentsSql(tenantId).owner(userId)
                .department(departmentId).scope(scope, owners, view, true));
        OwnedDocumentsSql filtered = new OwnedDocumentsSql(tenantId).owner(userId).department(departmentId).title(q)
                .state(state).recycleBin(recycleBin).scope(scope, owners, view, true);
        long total = count(filtered);
        List<OwnedDocumentItem> items = items(tenantId, filtered, pageSize, pageIndex * pageSize, null);
        return new OwnedDocumentsResponse(items, pageIndex, pageSize, total, totalOwned, visibleOwned,
                totalOwned - visibleOwned);
    }

    private static final String PENDING = "(d.ownership_state = 'holding' or exists (select 1 from users uo"
            + " where uo.tenant_id = d.tenant_id and uo.user_id = d.owner_user_id and (uo.is_deleted or uo.status <> 'active')))";

    /**
     * The pending-reassignment list (D13): documents parked with a holding owner and documents whose owner is a
     * deactivated or deleted user. Same visibility and department rules as {@link #owned}.
     */
    @Transactional(readOnly = true)
    public OwnedDocumentsResponse pending(UUID departmentId, String q, int page, int size) {
        UUID tenantId = TenantContext.getTenantId();
        Scope scope = ownershipScope.requireNonEmpty(tenantId, TenantContext.getUserId());
        requireInScope(tenantId, scope, null, departmentId);
        int pageSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
        int pageIndex = Math.max(0, page);
        Set<UUID> owners = ownershipScope.usersInScope(tenantId, scope);
        DocumentAccessView view = scope.unrestricted() ? null : documentAccess.forCaller();

        long totalPending = count(new OwnedDocumentsSql(tenantId).department(departmentId).and(PENDING)
                .scope(scope, owners, view, false));
        long visiblePending = scope.unrestricted() ? totalPending : count(new OwnedDocumentsSql(tenantId)
                .department(departmentId).and(PENDING).scope(scope, owners, view, true));
        OwnedDocumentsSql filtered = new OwnedDocumentsSql(tenantId).department(departmentId).title(q).and(PENDING)
                .scope(scope, owners, view, true);
        long total = count(filtered);
        List<OwnedDocumentItem> items = items(tenantId, filtered, pageSize, pageIndex * pageSize,
                "case when d.ownership_state = 'holding' then 'holding' else 'owner_deactivated' end");
        return new OwnedDocumentsResponse(items, pageIndex, pageSize, total, totalPending, visiblePending,
                totalPending - visiblePending);
    }

    /** A manager may only name a user or department of their own; an admin anything (a user of another tenant is 404). */
    void requireInScope(UUID tenantId, Scope scope, UUID userId, UUID departmentId) {
        if (userId != null) {
            Integer exists = jdbc.queryForObject("select count(*) from users where tenant_id = :t and user_id = :u",
                    new org.springframework.jdbc.core.namedparam.MapSqlParameterSource("t", tenantId).addValue("u", userId),
                    Integer.class);
            if (exists == null || exists == 0) {
                throw new ResourceNotFoundException("User", userId);
            }
            if (!ownershipScope.ownerInScope(tenantId, scope, userId)) {
                throw OwnershipException.scopeDenied();
            }
        }
        if (departmentId != null) {
            Integer exists = jdbc.queryForObject("select count(*) from departments where tenant_id = :t and department_id = :d",
                    new org.springframework.jdbc.core.namedparam.MapSqlParameterSource("t", tenantId).addValue("d", departmentId),
                    Integer.class);
            if (exists == null || exists == 0) {
                throw new ResourceNotFoundException("Department", departmentId);
            }
            if (!scope.unrestricted() && !scope.headedDepartments().contains(departmentId)) {
                throw OwnershipException.scopeDenied();
            }
        }
    }

    long count(OwnedDocumentsSql sql) {
        Long n = jdbc.queryForObject(COUNT.formatted(sql.where()), sql.params(), Long.class);
        return n == null ? 0 : n;
    }

    /** Rows for a where clause: {@code pendingReasonSql} (optional) is a select expression naming why a pending row is listed. */
    List<OwnedDocumentItem> items(UUID tenantId, OwnedDocumentsSql sql, int limit, int offset, String pendingReasonSql) {
        sql.param("limit", limit).param("offset", offset);
        String reason = pendingReasonSql == null ? "null" : pendingReasonSql;
        List<OwnedDocumentItem> rows = new ArrayList<>(jdbc.query("""
                select d.document_id, d.title, dt.type_name, d.folder_id, f.folder_name, d.is_deleted, d.is_template,
                       d.ownership_state, d.owner_user_id, coalesce(nullif(u.display_name, ''), u.email) as owner_name,
                       u.is_deleted as owner_deleted, u.status as owner_status, d.owner_department_id, dep.name as dept_name,
                       d.created_at, %s as pending_reason
                  from documents d
                  left join users u on u.tenant_id = d.tenant_id and u.user_id = d.owner_user_id
                  left join folders f on f.tenant_id = d.tenant_id and f.folder_id = d.folder_id
                  left join departments dep on dep.tenant_id = d.tenant_id and dep.department_id = d.owner_department_id
                  left join document_types dt on dt.document_type_id = d.document_type_id
                 where %s
                 order by lower(d.title), d.document_id
                 limit :limit offset :offset
                """.formatted(reason, sql.where()), sql.params(), (rs, i) -> {
            UUID owner = rs.getObject("owner_user_id", UUID.class);
            String status = rs.getString("owner_name") == null ? null
                    : rs.getBoolean("owner_deleted") ? "deactivated" : rs.getString("owner_status");
            UUID dept = rs.getObject("owner_department_id", UUID.class);
            return new OwnedDocumentItem(rs.getObject("document_id", UUID.class), rs.getString("title"),
                    rs.getString("type_name"), rs.getObject("folder_id", UUID.class), rs.getString("folder_name"),
                    rs.getBoolean("is_deleted"), rs.getBoolean("is_template"), rs.getString("ownership_state"),
                    new PersonRef(owner, rs.getString("owner_name") == null ? "Unknown user" : rs.getString("owner_name"), status),
                    dept == null ? null : new DepartmentRef(dept, rs.getString("dept_name")),
                    rs.getString("pending_reason"), rs.getObject("created_at", java.time.OffsetDateTime.class));
        }));
        return rows;
    }
}
