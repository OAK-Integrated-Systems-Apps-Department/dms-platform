package com.oak.dms.workflow.helper;

import com.oak.dms.document.DocumentApprovals;
import com.oak.dms.workflow.enums.InstanceStatus;
import com.oak.dms.workflow.repository.WorkflowInstanceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * See {@link DocumentApprovals}. One SQL union, mirroring
 * {@link TaskRouting} branch for branch - keep the two in step:
 * <ol>
 *   <li>the instance's initiator;</li>
 *   <li>an open information request addressed to the user;</li>
 *   <li>an open task of an active stage the user holds - escalation target,
 *   delegate, claimer, user assignee, or a live member of an unclaimed
 *   role or department pool.</li>
 * </ol>
 * Pool membership is the same rule {@code ApproverResolverImpl#isPoolMember}
 * applies (any grant of an active role, whatever its scope; direct members
 * of an active department), minus the approve/reject permission - reading
 * the document you were asked to approve needs no extra permission, and the
 * task would not exist if the pool were wrong.
 */
@Component
@RequiredArgsConstructor
public class WorkflowDocumentApprovals implements DocumentApprovals {

    /** Open tasks of active stages of in-progress instances, with the document under approval. */
    private static final String OPEN_TASKS = """
            with open_tasks as (
                select t.assignee_type, t.assignee_id, t.claimed_by, t.delegated_to, t.escalated_to, i.document_id
                from workflow_tasks t
                join workflow_stage_instances si on si.stage_instance_id = t.stage_instance_id
                join workflow_instances i on i.instance_id = si.instance_id
                where t.tenant_id = :tenant and not t.is_deleted
                  and t.status in ('pending', 'claimed', 'awaiting_info')
                  and si.status = 'active' and not si.is_deleted
                  and i.status = 'in_progress' and not i.is_deleted
            )
            """;

    private static final String READABLE = OPEN_TASKS + """
            select i.initiated_by as user_id, i.document_id
            from workflow_instances i
            where i.tenant_id = :tenant and i.status = 'in_progress' and not i.is_deleted
              and i.initiated_by in (:users)
            union
            select r.requested_from, i.document_id
            from workflow_info_requests r
            join workflow_instances i on i.instance_id = r.instance_id
            where r.tenant_id = :tenant and not r.is_deleted and r.status = 'open'
              and i.status = 'in_progress' and not i.is_deleted
              and r.requested_from in (:users)
            union
            select t.escalated_to, t.document_id from open_tasks t where t.escalated_to in (:users)
            union
            select t.delegated_to, t.document_id from open_tasks t where t.delegated_to in (:users)
            union
            select t.claimed_by, t.document_id from open_tasks t
            where t.delegated_to is null and t.claimed_by in (:users)
            union
            select t.assignee_id, t.document_id from open_tasks t
            where t.delegated_to is null and t.claimed_by is null and t.assignee_type = 'user'
              and t.assignee_id in (:users)
            union
            select ur.user_id, t.document_id from open_tasks t
            join user_roles ur on ur.role_id = t.assignee_id and ur.tenant_id = :tenant
            join roles r on r.role_id = ur.role_id and not r.is_deleted and r.status = 'active'
            join users u on u.user_id = ur.user_id and u.tenant_id = :tenant and not u.is_deleted
                 and u.status = 'active'
            where t.delegated_to is null and t.claimed_by is null and t.assignee_type = 'role'
              and ur.user_id in (:users)
            union
            select u.user_id, t.document_id from open_tasks t
            join departments d on d.department_id = t.assignee_id and d.tenant_id = :tenant
                 and not d.is_deleted and d.status = 'active'
            join users u on u.department_id = d.department_id and u.tenant_id = :tenant
                 and not u.is_deleted and u.status = 'active'
            where t.delegated_to is null and t.claimed_by is null and t.assignee_type = 'department'
              and u.user_id in (:users)
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final WorkflowInstanceRepository instanceRepository;

    @Override
    @Transactional(readOnly = true)
    public Set<UUID> readableDocumentIds(UUID tenantId, UUID userId) {
        return readableDocumentIdsByUser(tenantId, Set.of(userId)).getOrDefault(userId, Set.of());
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, Set<UUID>> readableDocumentIdsByUser(UUID tenantId, Collection<UUID> userIds) {
        Set<UUID> users = new LinkedHashSet<>(userIds);
        users.remove(null);
        if (users.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Set<UUID>> byUser = new HashMap<>();
        jdbc.query(READABLE, new MapSqlParameterSource().addValue("tenant", tenantId).addValue("users", users),
                rs -> {
                    byUser.computeIfAbsent(rs.getObject("user_id", UUID.class), k -> new HashSet<>())
                            .add(rs.getObject("document_id", UUID.class));
                });
        return byUser;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean inProgress(UUID tenantId, UUID documentId) {
        return instanceRepository.findFirstByTenantIdAndDocumentIdAndStatus(tenantId, documentId,
                InstanceStatus.in_progress).isPresent();
    }
}
