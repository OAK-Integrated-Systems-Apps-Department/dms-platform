package com.oak.dms.ownership.helper;

import com.oak.dms.security.auth.AuthorizationService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Who may move whose documents (D3). A tenant admin is unrestricted. Anyone else holding the DOT codes acts as a
 * manager: only on documents owned by people whose department they head. A non-admin who heads no department
 * can do nothing (403).
 */
@Component
@RequiredArgsConstructor
public class OwnershipScope {

    private final AuthorizationService authorization;
    private final NamedParameterJdbcTemplate jdbc;

    public record Scope(boolean unrestricted, Set<UUID> headedDepartments) {

        /** Nothing to act on: not an admin and no department headed. */
        public boolean empty() {
            return !unrestricted && headedDepartments.isEmpty();
        }
    }

    public Scope forCaller(UUID tenantId, UUID userId) {
        if (authorization.isTenantAdmin()) {
            return new Scope(true, Set.of());
        }
        List<UUID> headed = jdbc.queryForList("select department_id from departments where tenant_id = :t"
                        + " and head_user_id = :u and is_deleted = false and status = 'active'",
                new MapSqlParameterSource("t", tenantId).addValue("u", userId), UUID.class);
        return new Scope(false, new HashSet<>(headed));
    }

    /** The scope or 403: the caller is neither an admin nor the head of any department. */
    public Scope requireNonEmpty(UUID tenantId, UUID userId) {
        Scope scope = forCaller(tenantId, userId);
        if (scope.empty()) {
            throw OwnershipException.scopeDenied();
        }
        return scope;
    }

    /** Every user of the departments the caller heads (the owners a manager may act for). Empty for an admin - unused there. */
    public Set<UUID> usersInScope(UUID tenantId, Scope scope) {
        if (scope.unrestricted() || scope.headedDepartments().isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(jdbc.queryForList("select user_id from users where tenant_id = :t and department_id in (:d)",
                new MapSqlParameterSource("t", tenantId).addValue("d", scope.headedDepartments()), UUID.class));
    }

    /** Whether the owner belongs to a department the caller heads (always true for an admin). */
    public boolean ownerInScope(UUID tenantId, Scope scope, UUID ownerUserId) {
        if (scope.unrestricted()) {
            return true;
        }
        List<UUID> department = jdbc.queryForList("select department_id from users where tenant_id = :t and user_id = :u"
                + " and department_id is not null", new MapSqlParameterSource("t", tenantId).addValue("u", ownerUserId), UUID.class);
        return !department.isEmpty() && scope.headedDepartments().contains(department.get(0));
    }
}
