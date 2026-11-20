package com.oak.dms.reporting.schedule;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The people behind a schedule's recipients: who a role or department is today, and the little a delivery needs to know about a user.
 * A role or department is expanded when the report runs, never when the schedule is saved, so people who join or leave in between
 * are picked up or dropped.
 */
@Component
public class ReportPeople {

    /** What a delivery needs of a person. */
    public record Person(UUID userId, String email, String displayName) {
    }

    private final NamedParameterJdbcTemplate jdbc;

    public ReportPeople(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** One choice in an administrator's picker. */
    public record Option(UUID value, String label) {
    }

    /**
     * Active people (or active roles) of the organisation whose name contains {@code q}, by name, at most {@code limit}. For the
     * administrator's pickers, so they do not need the user-management permissions the general lists ask for.
     */
    public List<Option> search(UUID tenantId, String type, String q, int limit) {
        String pattern = com.oak.dms.common.sql.LikePatterns.contains(q == null ? "" : q.strip().toLowerCase(java.util.Locale.ROOT));
        var p = new MapSqlParameterSource("tenant", tenantId).addValue("q", pattern).addValue("limit", Math.max(1, Math.min(limit, 100)));
        String sql = "role".equals(type)
                ? """
                  select role_id as id, role_name as label from roles
                  where tenant_id = :tenant and not is_deleted and status = 'active' and lower(role_name) like :q
                  order by lower(role_name), role_id limit :limit"""
                : """
                  select user_id as id, display_name as label from users
                  where tenant_id = :tenant and not is_deleted and status = 'active'
                    and (lower(display_name) like :q or lower(email) like :q)
                  order by lower(display_name), user_id limit :limit""";
        return jdbc.query(sql, p, (rs, i) -> new Option(rs.getObject("id", UUID.class), rs.getString("label")));
    }

    /** Active people holding the role, at any scope. */
    public List<UUID> usersWithRole(UUID tenantId, UUID roleId) {
        return jdbc.queryForList("""
                select distinct u.user_id from user_roles ur join users u on u.user_id = ur.user_id
                where ur.tenant_id = :tenant and ur.role_id = :id and u.tenant_id = :tenant
                  and not u.is_deleted and u.status = 'active'
                order by u.user_id""", ids(tenantId, roleId), UUID.class);
    }

    /** Active people whose department it is. */
    public List<UUID> usersInDepartment(UUID tenantId, UUID departmentId) {
        return jdbc.queryForList("""
                select u.user_id from users u
                where u.tenant_id = :tenant and u.department_id = :id and not u.is_deleted and u.status = 'active'
                order by u.user_id""", ids(tenantId, departmentId), UUID.class);
    }

    /** Active users of the organisation only; anyone else (gone, disabled, another organisation's) is empty. */
    public Optional<Person> activePerson(UUID tenantId, UUID userId) {
        return jdbc.query("""
                select user_id, email, display_name from users
                where tenant_id = :tenant and user_id = :id and not is_deleted and status = 'active'""",
                ids(tenantId, userId),
                (rs, i) -> new Person(rs.getObject("user_id", UUID.class), rs.getString("email"), rs.getString("display_name")))
                .stream().findFirst();
    }

    /** Owners who are no longer active, among the active schedules - so they can be paused without waiting for their next slot. */
    public List<UUID> activeSchedulesOfInactiveOwners(int batch) {
        return jdbc.queryForList("""
                select s.schedule_id from report_schedules s join users u on u.user_id = s.owner_user_id
                where s.status = 'active' and (u.is_deleted or u.status <> 'active')
                limit :batch""", new MapSqlParameterSource("batch", batch), UUID.class);
    }

    private static MapSqlParameterSource ids(UUID tenantId, UUID id) {
        return new MapSqlParameterSource("tenant", tenantId).addValue("id", id);
    }
}
