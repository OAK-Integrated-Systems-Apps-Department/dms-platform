package com.oak.dms.ownership.helper;

import com.oak.dms.ownership.dto.DepartmentRef;
import com.oak.dms.ownership.dto.PersonRef;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Names for ownership screens. Reads {@code users} without the soft-delete filter, so a deactivated user still
 * shows by name; a permanently deleted user is simply absent and the caller falls back to the name snapshot
 * stored on the history row.
 */
@Component
@RequiredArgsConstructor
public class OwnershipNames {

    private final NamedParameterJdbcTemplate jdbc;

    public Map<UUID, PersonRef> persons(UUID tenantId, Collection<UUID> ids) {
        Set<UUID> wanted = clean(ids);
        Map<UUID, PersonRef> result = new HashMap<>();
        if (wanted.isEmpty()) {
            return result;
        }
        jdbc.query("select user_id, coalesce(nullif(display_name, ''), email) as name, is_deleted, status from users"
                        + " where tenant_id = :t and user_id in (:ids)",
                new MapSqlParameterSource("t", tenantId).addValue("ids", wanted), rs -> {
                    UUID id = rs.getObject("user_id", UUID.class);
                    String status = rs.getBoolean("is_deleted") ? "deactivated" : rs.getString("status");
                    result.put(id, new PersonRef(id, rs.getString("name"), status));
                });
        return result;
    }

    /** A person, using {@code snapshot} as the name when the account is gone. */
    public PersonRef person(Map<UUID, PersonRef> known, UUID id, String snapshot) {
        if (id == null) {
            return null;
        }
        PersonRef live = known.get(id);
        if (live != null) {
            return live;
        }
        return new PersonRef(id, snapshot != null ? snapshot : "Unknown user", null);
    }

    public String nameOf(UUID tenantId, UUID userId) {
        PersonRef ref = persons(tenantId, java.util.List.of(userId)).get(userId);
        return ref == null ? null : ref.name();
    }

    /** Departments including soft-deleted ones: history keeps pointing at them. */
    public Map<UUID, DepartmentRef> departments(UUID tenantId, Collection<UUID> ids) {
        Set<UUID> wanted = clean(ids);
        Map<UUID, DepartmentRef> result = new HashMap<>();
        if (wanted.isEmpty()) {
            return result;
        }
        jdbc.query("select department_id, name from departments where tenant_id = :t and department_id in (:ids)",
                new MapSqlParameterSource("t", tenantId).addValue("ids", wanted), rs -> {
                    UUID id = rs.getObject("department_id", UUID.class);
                    result.put(id, new DepartmentRef(id, rs.getString("name")));
                });
        return result;
    }

    private static Set<UUID> clean(Collection<UUID> ids) {
        Set<UUID> wanted = new LinkedHashSet<>(ids);
        wanted.remove(null);
        return wanted;
    }
}
