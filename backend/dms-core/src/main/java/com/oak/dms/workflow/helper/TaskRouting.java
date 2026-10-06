package com.oak.dms.workflow.helper;

import com.oak.dms.workflow.entity.WorkflowTask;
import com.oak.dms.workflow.enums.TaskStatus;
import com.oak.dms.workflow.repository.WorkflowStageParticipantRepository;
import com.oak.dms.workflow.repository.WorkflowTaskRepository;
import com.oak.dms.workflow.service.ApproverResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * The single Java answer to "who may act on this task" (Phase 2 plan §5).
 * The My Tasks JPQL in {@code WorkflowTaskRepository} mirrors these rules;
 * keep the two in step.
 * <ol>
 *   <li>The escalation target always may - escalation adds a route and the
 *   original holders keep theirs (decision 6).</li>
 *   <li>A delegated task belongs to its delegate only; the original holder
 *   and a claimer no longer may (decisions 1, A4).</li>
 *   <li>A claimed task - including one paused by its claimer's information
 *   request - belongs to its claimer.</li>
 *   <li>A user task belongs to its assignee; an unclaimed pool task to every
 *   live member of the pool.</li>
 * </ol>
 * Who may act is not whether they may decide now: a task awaiting
 * information is still theirs but can't be decided (checked by the caller).
 */
@Component
@RequiredArgsConstructor
public class TaskRouting {

    /** How the acting user reached a task - recorded on the decision for the timeline. */
    public enum Route {
        assigned, pool, claimed, delegated, escalated
    }

    private final ApproverResolver approverResolver;
    private final WorkflowStageParticipantRepository participantRepository;
    private final WorkflowTaskRepository taskRepository;

    public boolean canAct(UUID tenantId, WorkflowTask task, UUID userId) {
        return route(tenantId, task, userId) != null;
    }

    /** The route by which {@code userId} may act on the task, or null when they may not. */
    public Route route(UUID tenantId, WorkflowTask task, UUID userId) {
        if (userId.equals(task.getEscalatedTo())) {
            return Route.escalated;
        }
        if (task.getDelegatedTo() != null) {
            return userId.equals(task.getDelegatedTo()) ? Route.delegated : null;
        }
        if (task.getClaimedBy() != null) {
            return userId.equals(task.getClaimedBy()) ? Route.claimed : null;
        }
        if (!task.isPool()) {
            return userId.equals(task.getAssigneeId()) ? Route.assigned : null;
        }
        return approverResolver.isPoolMember(tenantId, task, userId) ? Route.pool : null;
    }

    /**
     * The one person holding the task: its delegate, else its claimer, else
     * the assignee of a user task. Null for an unclaimed pool task, which
     * belongs to the whole pool and so can't be delegated (A4).
     */
    public static UUID holder(WorkflowTask task) {
        if (task.getDelegatedTo() != null) {
            return task.getDelegatedTo();
        }
        if (task.getClaimedBy() != null) {
            return task.getClaimedBy();
        }
        return task.isPool() ? null : task.getAssigneeId();
    }

    /**
     * Everyone who currently holds the task (reminders, escalation copies;
     * A6): its delegate, else its claimer, else its user assignee, else
     * every eligible live member of its pool - with the approve/reject
     * permission, not the initiator, not already decided in this stage. The
     * escalation route is not included; callers add it where they need it.
     */
    public Set<UUID> currentHolders(UUID tenantId, WorkflowTask task, UUID initiatorId) {
        UUID holder = holder(task);
        if (holder != null) {
            return Set.of(holder);
        }
        Set<UUID> members = new LinkedHashSet<>(participantRepository.findById(task.getParticipantId())
                .map(p -> approverResolver.resolve(tenantId, p, initiatorId))
                .orElse(Set.of()));
        taskRepository.findByStageInstanceIdAndDeletedFalseOrderByCreatedAtAsc(task.getStageInstanceId()).stream()
                .filter(t -> TaskStatus.DECIDED.contains(t.getStatus()))
                .forEach(t -> members.remove(t.getActedBy()));
        return members;
    }
}
