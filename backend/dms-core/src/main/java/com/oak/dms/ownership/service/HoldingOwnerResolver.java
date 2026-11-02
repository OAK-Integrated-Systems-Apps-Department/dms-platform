package com.oak.dms.ownership.service;

import com.oak.dms.ownership.helper.OwnershipException;
import com.oak.dms.security.auth.UserPermissions;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Who documents are parked with when nobody is named (D5): the department's holding owner, else its head, else
 * the tenant's oldest active admin account. Each candidate must be an active user of the tenant and not the person
 * who is leaving.
 */
@Component
@RequiredArgsConstructor
public class HoldingOwnerResolver {

    public static final String SOURCE_HOLDING_OWNER = "holding_owner";
    public static final String SOURCE_DEPARTMENT_HEAD = "department_head";
    public static final String SOURCE_TENANT_ADMIN = "tenant_admin";

    /** The chosen holding owner, where the choice came from, and the department the documents are filed under (null for the admin fallback without one). */
    public record Resolved(UUID userId, String name, String source, UUID departmentId) {
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final UserPermissions userPermissions;

    public Resolved resolve(UUID tenantId, UUID departmentId, UUID excludeUserId) {
        if (departmentId != null) {
            List<UUID[]> row = jdbc.query("select holding_owner_user_id, head_user_id from departments where tenant_id = :t"
                            + " and department_id = :d and is_deleted = false and status = 'active'",
                    new MapSqlParameterSource("t", tenantId).addValue("d", departmentId),
                    (rs, i) -> new UUID[]{rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)});
            if (!row.isEmpty()) {
                Resolved holder = candidate(tenantId, row.get(0)[0], excludeUserId, SOURCE_HOLDING_OWNER, departmentId);
                if (holder != null) {
                    return holder;
                }
                Resolved head = candidate(tenantId, row.get(0)[1], excludeUserId, SOURCE_DEPARTMENT_HEAD, departmentId);
                if (head != null) {
                    return head;
                }
            } else {
                departmentId = null;
            }
        }
        Set<UUID> admins = userPermissions.tenantAdminIds(tenantId);
        if (!admins.isEmpty()) {
            List<UUID> ordered = jdbc.queryForList("select user_id from users where tenant_id = :t and user_id in (:ids)"
                            + " and is_deleted = false and status = 'active' order by created_at, user_id",
                    new MapSqlParameterSource("t", tenantId).addValue("ids", admins), UUID.class);
            for (UUID id : ordered) {
                Resolved admin = candidate(tenantId, id, excludeUserId, SOURCE_TENANT_ADMIN, departmentId);
                if (admin != null) {
                    return admin;
                }
            }
        }
        throw OwnershipException.invalid("OWNERSHIP_NO_HOLDING_OWNER",
                "There is nobody to hold the documents: set a holding owner for the department, or make sure an active administrator exists.");
    }

    private Resolved candidate(UUID tenantId, UUID userId, UUID excludeUserId, String source, UUID departmentId) {
        if (userId == null || userId.equals(excludeUserId)) {
            return null;
        }
        List<String> name = jdbc.queryForList("select coalesce(nullif(display_name, ''), email) from users where tenant_id = :t"
                        + " and user_id = :u and is_deleted = false and status = 'active'",
                new MapSqlParameterSource("t", tenantId).addValue("u", userId), String.class);
        return name.isEmpty() ? null : new Resolved(userId, name.get(0), source, departmentId);
    }
}
