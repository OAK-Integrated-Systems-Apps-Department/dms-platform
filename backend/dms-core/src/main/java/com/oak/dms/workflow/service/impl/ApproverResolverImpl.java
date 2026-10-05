package com.oak.dms.workflow.service.impl;

import com.oak.dms.department.entity.Department;
import com.oak.dms.department.repository.DepartmentRepository;
import com.oak.dms.security.rbac.catalog.PermissionCodes;
import com.oak.dms.security.rbac.repository.RolePermissionRepository;
import com.oak.dms.user.User;
import com.oak.dms.user.UserRepository;
import com.oak.dms.workflow.entity.WorkflowStageParticipant;
import com.oak.dms.workflow.entity.WorkflowTask;
import com.oak.dms.workflow.enums.TaskStatus;
import com.oak.dms.workflow.repository.WorkflowMembershipRepository;
import com.oak.dms.workflow.service.ApproverResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * See {@link ApproverResolver}. Role pools count any grant of the role,
 * whatever its user_roles scope (Q3); department pools are direct members
 * only, of an active department. Resolution keeps only users who hold
 * approve or reject through a DB role grant - TENANT_ADMIN's implicit
 * access is a Keycloak role, so an admin counts toward a pool only if they
 * also hold such a grant (plan R4).
 */
@Service
@RequiredArgsConstructor
public class ApproverResolverImpl implements ApproverResolver {

    private final WorkflowMembershipRepository membershipRepository;
    private final UserRepository userRepository;
    private final DepartmentRepository departmentRepository;
    private final RolePermissionRepository rolePermissionRepository;

    @Override
    public Set<UUID> resolve(UUID tenantId, WorkflowStageParticipant participant, UUID initiatorId) {
        Set<UUID> members = new LinkedHashSet<>(switch (participant.getAssigneeType()) {
            case user -> userRepository.findByUserIdAndTenantIdAndDeletedFalse(participant.getAssigneeId(), tenantId)
                    .filter(u -> u.getStatus() == User.UserStatus.active)
                    .map(u -> List.of(u.getUserId()))
                    .orElse(List.of());
            case role -> membershipRepository.findActiveRoleMemberIds(tenantId, participant.getAssigneeId());
            case department -> isActiveDepartment(tenantId, participant.getAssigneeId())
                    ? membershipRepository.findActiveDepartmentMemberIds(tenantId, participant.getAssigneeId())
                    : List.of();
        });
        members.remove(initiatorId);
        if (members.isEmpty()) {
            return members;
        }
        Set<UUID> holders = new HashSet<>(
                rolePermissionRepository.findUserIdsWithPermission(tenantId, PermissionCodes.WA_TASK_APPROVE));
        holders.addAll(rolePermissionRepository.findUserIdsWithPermission(tenantId, PermissionCodes.WA_TASK_REJECT));
        members.retainAll(holders);
        return members;
    }

    @Override
    public boolean canAct(UUID tenantId, WorkflowTask task, UUID userId) {
        if (!task.isPool()) {
            return userId.equals(task.getAssigneeId());
        }
        if (task.getStatus() == TaskStatus.claimed) {
            return userId.equals(task.getClaimedBy());
        }
        return isPoolMember(tenantId, task, userId);
    }

    @Override
    public boolean isPoolMember(UUID tenantId, WorkflowTask task, UUID userId) {
        return switch (task.getAssigneeType()) {
            case user -> userId.equals(task.getAssigneeId());
            case role -> membershipRepository.findActiveRoleIds(tenantId, userId).contains(task.getAssigneeId());
            case department -> isActiveDepartment(tenantId, task.getAssigneeId())
                    && userRepository.findByUserIdAndTenantIdAndDeletedFalse(userId, tenantId)
                    .map(u -> task.getAssigneeId().equals(u.getDepartmentId()))
                    .orElse(false);
        };
    }

    private boolean isActiveDepartment(UUID tenantId, UUID departmentId) {
        return departmentRepository.findByDepartmentIdAndTenantIdAndDeletedFalse(departmentId, tenantId)
                .map(d -> d.getStatus() == Department.DepartmentStatus.active)
                .orElse(false);
    }
}
