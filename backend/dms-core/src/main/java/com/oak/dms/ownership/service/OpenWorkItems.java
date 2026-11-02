package com.oak.dms.ownership.service;

import com.oak.dms.folder.DocumentAccessService.DocumentAccessView;
import com.oak.dms.ownership.dto.WorkFlags;
import com.oak.dms.ownership.dto.WorkFlags.OpenTask;
import com.oak.dms.ownership.dto.WorkFlags.StartedRun;
import com.oak.dms.ownership.helper.OwnedDocument;
import com.oak.dms.security.auth.AuthorizationService;
import com.oak.dms.security.rbac.catalog.PermissionCodes;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * The approval work a departing user leaves behind (D6): the open tasks they hold (pending, claimed or awaiting
 * information, in an active stage of a running approval - whether assigned, claimed, delegated to or escalated
 * to them) and the runs they started that are still in progress. Pool tasks nobody has claimed are not theirs.
 */
@Component
@RequiredArgsConstructor
public class OpenWorkItems {

    private static final String TASKS = """
            select t.task_id, si.instance_id, wi.document_id, d.title, d.tenant_id, d.owner_user_id, d.owner_department_id,
                   d.ownership_state, d.folder_id, d.access_mode, d.is_template, d.is_deleted,
                   ws.name as stage_name, t.status, t.due_at,
                   case when t.delegated_to = :u then 'delegated' when t.escalated_to = :u then 'escalated'
                        when t.claimed_by = :u then 'claimed' else 'assigned' end as route
              from workflow_tasks t
              join workflow_stage_instances si on si.tenant_id = t.tenant_id and si.stage_instance_id = t.stage_instance_id
                   and si.status = 'active' and si.is_deleted = false
              join workflow_instances wi on wi.tenant_id = si.tenant_id and wi.instance_id = si.instance_id
                   and wi.status = 'in_progress' and wi.is_deleted = false
              join documents d on d.tenant_id = wi.tenant_id and d.document_id = wi.document_id
              left join workflow_stages ws on ws.tenant_id = si.tenant_id and ws.stage_id = si.stage_id
             where t.tenant_id = :t and t.is_deleted = false and t.status in ('pending', 'claimed', 'awaiting_info')
               and (t.delegated_to = :u or t.escalated_to = :u
                    or (t.delegated_to is null and (t.claimed_by = :u
                        or (t.claimed_by is null and t.assignee_type = 'user' and t.assignee_id = :u))))
             order by t.due_at nulls last, t.task_id
            """;

    private static final String RUNS = """
            select wi.instance_id, wi.document_id, wi.started_at, d.title, d.tenant_id, d.owner_user_id, d.owner_department_id,
                   d.ownership_state, d.folder_id, d.access_mode, d.is_template, d.is_deleted
              from workflow_instances wi
              join documents d on d.tenant_id = wi.tenant_id and d.document_id = wi.document_id
             where wi.tenant_id = :t and wi.initiated_by = :u and wi.status = 'in_progress' and wi.is_deleted = false
             order by wi.started_at, wi.instance_id
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final AuthorizationService authorization;

    /** {@code view} is the caller's access (null for a tenant admin, who sees every document). */
    public WorkFlags flagsFor(UUID tenantId, UUID userId, DocumentAccessView view) {
        MapSqlParameterSource params = new MapSqlParameterSource("t", tenantId).addValue("u", userId);
        boolean canReassign = authorization.hasPermission(PermissionCodes.WA_TASK_REASSIGN);
        boolean canCancel = authorization.hasPermission(PermissionCodes.DLM_SUBMIT_DOCUMENTS_FOR_REVIEW);
        List<OpenTask> tasks = jdbc.query(TASKS, params, (rs, i) -> {
            OwnedDocument document = document(rs);
            boolean visible = view == null || view.canSee(document.accessStub());
            UUID instanceId = rs.getObject("instance_id", UUID.class);
            return new OpenTask(rs.getObject("task_id", UUID.class), instanceId,
                    visible ? document.documentId() : null, visible ? document.title() : null,
                    rs.getString("stage_name"), rs.getString("status"), rs.getString("route"),
                    rs.getObject("due_at", java.time.OffsetDateTime.class), "/workflows/instances/" + instanceId,
                    visible ? "/documents/" + document.documentId() : null, canReassign);
        });
        List<StartedRun> runs = jdbc.query(RUNS, params, (rs, i) -> {
            OwnedDocument document = document(rs);
            boolean visible = view == null || view.canSee(document.accessStub());
            UUID instanceId = rs.getObject("instance_id", UUID.class);
            return new StartedRun(instanceId, visible ? document.documentId() : null, visible ? document.title() : null,
                    rs.getObject("started_at", java.time.OffsetDateTime.class), "/workflows/instances/" + instanceId,
                    canCancel);
        });
        return new WorkFlags(tasks, runs, tasks.size(), runs.size());
    }

    private static OwnedDocument document(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new OwnedDocument(rs.getObject("document_id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getString("title"), rs.getObject("owner_user_id", UUID.class),
                rs.getObject("owner_department_id", UUID.class), rs.getString("ownership_state"),
                rs.getObject("folder_id", UUID.class), rs.getString("access_mode"), rs.getBoolean("is_template"),
                rs.getBoolean("is_deleted"));
    }
}
