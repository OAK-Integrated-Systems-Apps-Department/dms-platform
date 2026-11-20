package com.oak.dms.reporting.template;

import com.oak.dms.reporting.dto.TemplateShareRequest.Grant;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Who can see and use which template, and the grants themselves. One SQL decides visibility for the list and for a single lookup, so
 * the two can never disagree:
 * <ul>
 *   <li>the owner: {@code owner};</li>
 *   <li>a shared or official template, through a grant to the person, to one of their roles or to their department:
 *       {@code edit} if any matching grant says so, otherwise {@code run};</li>
 *   <li>an official template with no grants at all: everyone, {@code run}.</li>
 * </ul>
 * A private template is the owner's alone, whatever rows might exist in the grants table.
 */
@Component
public class ReportTemplateStore {

    private static final String MATCHING_GRANT = """
            (g.principal_type = 'user' and g.principal_id = :user)
            or (g.principal_type = 'role' and g.principal_id in
                  (select ur.role_id from user_roles ur where ur.user_id = :user and ur.tenant_id = :tenant))
            or (g.principal_type = 'department' and g.principal_id =
                  (select u.department_id from users u where u.user_id = :user))""";

    private static final String VISIBLE = """
            select template_id, access from (
              select t.template_id,
                     case
                       when t.owner_user_id = :user then 'owner'
                       when t.scope <> 'private' and exists (select 1 from report_template_grants g
                             where g.template_id = t.template_id and g.access_level = 'edit' and (""" + MATCHING_GRANT + """
                             )) then 'edit'
                       when t.scope <> 'private' and exists (select 1 from report_template_grants g
                             where g.template_id = t.template_id and (""" + MATCHING_GRANT + """
                             )) then 'run'
                       when t.scope = 'official' and not exists (select 1 from report_template_grants g
                             where g.template_id = t.template_id) then 'run'
                       when :publisher and t.scope = 'official' then 'run'
                     end as access
              from report_templates t
              where t.tenant_id = :tenant and t.deleted_at is null %s
            ) v where access is not null""";

    private final NamedParameterJdbcTemplate jdbc;

    public ReportTemplateStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Every template the person may see, with what they may do with it. A {@code publisher} (someone who may publish and unpublish
     * organisation templates) also sees every official template, whatever its audience - otherwise publishing to an audience they
     * are not in would leave them unable to change or unpublish it.
     */
    public Map<UUID, String> visible(UUID tenantId, UUID userId, boolean publisher) {
        Map<UUID, String> result = new LinkedHashMap<>();
        jdbc.query(VISIBLE.formatted(""), params(tenantId, userId, publisher),
                rs -> {
                    result.put(rs.getObject("template_id", UUID.class), rs.getString("access"));
                });
        return result;
    }

    /** What the person may do with this one template; empty when they may not even see it. See {@link #visible}. */
    public Optional<String> accessOf(UUID tenantId, UUID templateId, UUID userId, boolean publisher) {
        List<String> found = jdbc.query(VISIBLE.formatted("and t.template_id = :template"),
                params(tenantId, userId, publisher).addValue("template", templateId), (rs, i) -> rs.getString("access"));
        return found.stream().findFirst();
    }

    /** What the person may do with this one template by ownership and grants alone (schedules use this for their owner). */
    public Optional<String> accessOf(UUID tenantId, UUID templateId, UUID userId) {
        return accessOf(tenantId, templateId, userId, false);
    }

    public List<Grant> grantsOf(UUID templateId) {
        return jdbc.query("""
                select principal_type, principal_id, access_level from report_template_grants
                where template_id = :id order by principal_type, principal_id""",
                new MapSqlParameterSource("id", templateId),
                (rs, i) -> new Grant(rs.getString("principal_type"), rs.getObject("principal_id", UUID.class),
                        rs.getString("access_level")));
    }

    /** Replaces every grant of the template with exactly these. */
    public void replaceGrants(UUID tenantId, UUID templateId, List<Grant> grants) {
        jdbc.update("delete from report_template_grants where template_id = :id", new MapSqlParameterSource("id", templateId));
        for (Grant grant : grants) {
            jdbc.update("""
                    insert into report_template_grants (template_id, tenant_id, principal_type, principal_id, access_level)
                    values (:template, :tenant, :type, :principal, :level)""",
                    new MapSqlParameterSource("template", templateId).addValue("tenant", tenantId)
                            .addValue("type", grant.principalType()).addValue("principal", grant.principalId())
                            .addValue("level", grant.accessLevel() == null ? "run" : grant.accessLevel()));
        }
    }

    /** Whether the user, role or department exists in this organisation and is usable. */
    public boolean principalExists(UUID tenantId, String type, UUID id) {
        String sql = switch (type) {
            case "user" -> "select count(*) from users where tenant_id = :tenant and user_id = :id and not is_deleted and status = 'active'";
            case "role" -> "select count(*) from roles where tenant_id = :tenant and role_id = :id and not is_deleted";
            case "department" -> "select count(*) from departments where tenant_id = :tenant and department_id = :id "
                    + "and not is_deleted and status = 'active'";
            default -> null;
        };
        if (sql == null) {
            return false;
        }
        Integer count = jdbc.queryForObject(sql, new MapSqlParameterSource("tenant", tenantId).addValue("id", id), Integer.class);
        return count != null && count > 0;
    }

    /** Active or paused schedules that use the template. */
    public long liveSchedules(UUID tenantId, UUID templateId) {
        Long count = jdbc.queryForObject("""
                select count(*) from report_schedules
                where tenant_id = :tenant and template_id = :id and status in ('active', 'paused', 'paused_failed')""",
                new MapSqlParameterSource("tenant", tenantId).addValue("id", templateId), Long.class);
        return count == null ? 0 : count;
    }

    /** Cancels them all; returns the schedule ids, so each can be audited. */
    public List<UUID> cancelSchedules(UUID tenantId, UUID templateId) {
        return jdbc.queryForList("""
                update report_schedules set status = 'cancelled', next_run_at = null, updated_at = now()
                where tenant_id = :tenant and template_id = :id and status in ('active', 'paused', 'paused_failed')
                returning schedule_id""",
                new MapSqlParameterSource("tenant", tenantId).addValue("id", templateId), UUID.class);
    }

    /** Display names for a page of owners, one query. */
    public Map<UUID, String> userNames(UUID tenantId, java.util.Collection<UUID> userIds) {
        Map<UUID, String> names = new LinkedHashMap<>();
        if (userIds.isEmpty()) {
            return names;
        }
        jdbc.query("select user_id, display_name from users where tenant_id = :tenant and user_id in (:ids)",
                new MapSqlParameterSource("tenant", tenantId).addValue("ids", userIds),
                rs -> {
                    names.put(rs.getObject("user_id", UUID.class), rs.getString("display_name"));
                });
        return names;
    }

    private static MapSqlParameterSource params(UUID tenantId, UUID userId, boolean publisher) {
        return new MapSqlParameterSource("tenant", tenantId).addValue("user", userId).addValue("publisher", publisher);
    }
}
