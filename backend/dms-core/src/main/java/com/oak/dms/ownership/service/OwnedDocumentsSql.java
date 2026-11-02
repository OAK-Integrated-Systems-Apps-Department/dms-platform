package com.oak.dms.ownership.service;

import com.oak.dms.folder.DocumentAccessService.DocumentAccessView;
import com.oak.dms.ownership.helper.OwnershipScope.Scope;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

import java.util.Set;
import java.util.UUID;

/**
 * The WHERE clause shared by the owned-documents list, the pending list and the "transfer all" candidate query.
 * Purged documents never appear (they are tombstones). For a caller who is not a tenant admin it adds two rules:
 * the owner must belong to a department they head (D3), and - unless {@code applyVisibility} is off, which is only
 * used to <em>count</em> - the document must be one they can open (D8), mirroring
 * {@code DocumentRepository}'s visible-documents predicate.
 */
final class OwnedDocumentsSql {

    private static final UUID NONE = new UUID(0, 0);

    private final StringBuilder where = new StringBuilder("d.tenant_id = :t and d.purged_at is null");
    private final MapSqlParameterSource params = new MapSqlParameterSource();

    OwnedDocumentsSql(UUID tenantId) {
        params.addValue("t", tenantId);
    }

    OwnedDocumentsSql owner(UUID userId) {
        if (userId != null) {
            where.append(" and d.owner_user_id = :ownerUser");
            params.addValue("ownerUser", userId);
        }
        return this;
    }

    OwnedDocumentsSql department(UUID departmentId) {
        if (departmentId != null) {
            where.append(" and d.owner_department_id = :ownerDept");
            params.addValue("ownerDept", departmentId);
        }
        return this;
    }

    OwnedDocumentsSql title(String q) {
        if (q != null && !q.isBlank()) {
            where.append(" and lower(d.title) like lower(:q)");
            params.addValue("q", "%" + q.trim() + "%");
        }
        return this;
    }

    OwnedDocumentsSql state(String state) {
        if (state != null && !state.isBlank()) {
            where.append(" and d.ownership_state = :state");
            params.addValue("state", state);
        }
        return this;
    }

    /** null = both, true = only the Recycle Bin, false = live documents only. */
    OwnedDocumentsSql recycleBin(Boolean only) {
        if (only != null) {
            where.append(only ? " and d.is_deleted = true" : " and d.is_deleted = false");
        }
        return this;
    }

    /** Raw extra condition (already parameterised through {@link #param}). */
    OwnedDocumentsSql and(String condition) {
        where.append(" and ").append(condition);
        return this;
    }

    OwnedDocumentsSql param(String name, Object value) {
        params.addValue(name, value);
        return this;
    }

    OwnedDocumentsSql exclude(Set<UUID> ids) {
        if (ids != null && !ids.isEmpty()) {
            where.append(" and d.document_id not in (:excluded)");
            params.addValue("excluded", ids);
        }
        return this;
    }

    /** A manager's limits; a no-op for an admin. {@code ownersInScope} are the users of the departments they head. */
    OwnedDocumentsSql scope(Scope scope, Set<UUID> ownersInScope, DocumentAccessView view, boolean applyVisibility) {
        if (scope.unrestricted()) {
            return this;
        }
        where.append(" and d.owner_user_id in (:scopeOwners)");
        params.addValue("scopeOwners", ownersInScope.isEmpty() ? Set.of(NONE) : ownersInScope);
        if (applyVisibility) {
            visibleTo(view);
        }
        return this;
    }

    /** Only documents the view can open (alias {@code d}): no-op-free, used on its own by the history export. */
    OwnedDocumentsSql visibleTo(DocumentAccessView view) {
        Set<UUID> hidden = view.folders().hiddenFolderIds();
        where.append(" and ((d.access_mode = 'inherit' and (d.folder_id is null or d.folder_id not in (:hiddenFolders)))"
                + " or (d.access_mode = 'restricted' and d.document_id in (:granted))"
                + " or d.document_id in (:workflowReadable))");
        params.addValue("hiddenFolders", hidden.isEmpty() ? Set.of(NONE) : hidden);
        params.addValue("granted", view.grantedIds());
        params.addValue("workflowReadable", view.workflowReadableIds());
        return this;
    }

    /** A where clause that does not exclude purged documents (history outlives a purge). */
    static OwnedDocumentsSql includingPurged(UUID tenantId) {
        OwnedDocumentsSql sql = new OwnedDocumentsSql(tenantId);
        sql.where.setLength(0);
        sql.where.append("d.tenant_id = :t");
        return sql;
    }

    String where() {
        return where.toString();
    }

    MapSqlParameterSource params() {
        return params;
    }
}
