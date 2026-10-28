package com.oak.dms.workflow.helper;

import com.oak.dms.department.entity.Department;
import com.oak.dms.department.repository.DepartmentRepository;
import com.oak.dms.security.auth.UserPermissions;
import com.oak.dms.security.rbac.catalog.PermissionCodes;
import com.oak.dms.user.User;
import com.oak.dms.user.UserRepository;
import com.oak.dms.workflow.entity.WorkflowInstance;
import com.oak.dms.workflow.entity.WorkflowStage;
import com.oak.dms.workflow.entity.WorkflowTask;
import com.oak.dms.workflow.enums.AssigneeType;
import com.oak.dms.workflow.enums.EscalationTarget;
import com.oak.dms.workflow.enums.TaskStatus;
import com.oak.dms.workflow.repository.WorkflowTaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Who a task escalates to when its due time passes (decision 6, A1, A2).
 * <ul>
 *   <li>{@code named_user}: the stage's escalation user.</li>
 *   <li>{@code department_head}: for a department-pool task, that
 *   department's head; for a user or role task, the head of the
 *   initiator's department (A1).</li>
 * </ul>
 * The target is unusable - {@link Resolution#skippedReason()} set - when
 * there is none, it isn't an active user of the tenant, it is the initiator,
 * it already holds the task, it already decided in this stage, or it lacks
 * the approve/reject permission (A2).
 */
@Component
@RequiredArgsConstructor
public class EscalationTargetResolver {

    /** userId is null when skippedReason is set. source: named_user | pool_department_head | initiator_department_head. */
    public record Resolution(UUID userId, String source, String skippedReason) {
        static Resolution skipped(String source, String reason) {
            return new Resolution(null, source, reason);
        }
    }

    private final DepartmentRepository departmentRepository;
    private final UserRepository userRepository;
    private final WorkflowTaskRepository taskRepository;
    private final TaskRouting taskRouting;
    private final UserPermissions userPermissions;

    public Resolution resolve(WorkflowStage stage, WorkflowTask task, WorkflowInstance instance) {
        return resolve(stage.getEscalationTarget(), stage.getEscalationUserId(), task, instance);
    }

    /**
     * The same, for the target that actually applies to the task: the stage's own, or the template default a
     * stage with no target of its own inherits (3.2). {@code namedUserId} is only read for named_user.
     */
    public Resolution resolve(EscalationTarget escalationTarget, UUID namedUserId, WorkflowTask task,
                              WorkflowInstance instance) {
        UUID tenantId = instance.getTenantId();
        String source;
        UUID target;
        switch (escalationTarget) {
            case named_user -> {
                source = "named_user";
                target = namedUserId;
            }
            case department_head -> {
                if (task.getAssigneeType() == AssigneeType.department) {
                    source = "pool_department_head";
                    target = headOf(task.getAssigneeId(), tenantId);
                } else {
                    source = "initiator_department_head";
                    UUID departmentId = userRepository.findByUserIdAndTenantIdAndDeletedFalse(instance.getInitiatedBy(),
                            tenantId).map(User::getDepartmentId).orElse(null);
                    target = departmentId == null ? null : headOf(departmentId, tenantId);
                }
            }
            default -> {
                return Resolution.skipped("none", "no_escalation_target");
            }
        }
        if (target == null) {
            return Resolution.skipped(source, "no_target");
        }
        boolean active = userRepository.findByUserIdAndTenantIdAndDeletedFalse(target, tenantId)
                .map(u -> u.getStatus() == User.UserStatus.active).orElse(false);
        if (!active) {
            return Resolution.skipped(source, "target_inactive");
        }
        if (target.equals(instance.getInitiatedBy())) {
            return Resolution.skipped(source, "target_is_initiator");
        }
        if (taskRouting.currentHolders(tenantId, task, instance.getInitiatedBy()).contains(target)) {
            return Resolution.skipped(source, "target_is_holder");
        }
        if (taskRepository.existsByStageInstanceIdAndActedByAndStatusIn(task.getStageInstanceId(), target,
                TaskStatus.DECIDED)) {
            return Resolution.skipped(source, "target_already_decided");
        }
        if (!userPermissions.holdsAny(tenantId, target, PermissionCodes.WA_TASK_APPROVE,
                PermissionCodes.WA_TASK_REJECT)) {
            return Resolution.skipped(source, "target_lacks_permission");
        }
        return new Resolution(target, source, null);
    }

    private UUID headOf(UUID departmentId, UUID tenantId) {
        return departmentRepository.findByDepartmentIdAndTenantIdAndDeletedFalse(departmentId, tenantId)
                .filter(d -> d.getStatus() == Department.DepartmentStatus.active)
                .map(Department::getHeadUserId)
                .orElse(null);
    }
}
