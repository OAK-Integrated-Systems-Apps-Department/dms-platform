package com.oak.dms.workflow;

import com.oak.dms.workflow.entity.WorkflowTask;
import com.oak.dms.workflow.enums.AssigneeType;
import com.oak.dms.workflow.enums.TaskStatus;
import com.oak.dms.workflow.helper.TaskRouting.Route;
import com.oak.dms.workflow.helper.TaskRouting;
import com.oak.dms.workflow.service.ApproverResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Phase 2 plan §5: every route x status, including the Phase 1 awaiting_info hole (§1-2). */
class TaskRoutingTest {

    private static final UUID TENANT = UUID.randomUUID();
    private final UUID assignee = UUID.randomUUID();
    private final UUID member = UUID.randomUUID();
    private final UUID claimer = UUID.randomUUID();
    private final UUID delegate = UUID.randomUUID();
    private final UUID target = UUID.randomUUID();
    private final UUID stranger = UUID.randomUUID();

    private TaskRouting routing;

    @BeforeEach
    void setUp() {
        ApproverResolver resolver = mock(ApproverResolver.class);
        when(resolver.isPoolMember(eq(TENANT), any(), any())).thenAnswer(inv -> {
            UUID user = inv.getArgument(2);
            return user.equals(member) || user.equals(claimer);
        });
        routing = new TaskRouting(resolver, null, null);
    }

    private static WorkflowTask task(AssigneeType type, UUID assigneeId, TaskStatus status) {
        return WorkflowTask.builder().taskId(UUID.randomUUID()).tenantId(TENANT).assigneeType(type)
                .assigneeId(assigneeId).status(status).build();
    }

    @ParameterizedTest
    @EnumSource(value = TaskStatus.class, names = {"pending", "awaiting_info"})
    void userTask_onlyItsAssignee(TaskStatus status) {
        WorkflowTask t = task(AssigneeType.user, assignee, status);
        assertThat(routing.route(TENANT, t, assignee)).isEqualTo(Route.assigned);
        assertThat(routing.canAct(TENANT, t, member)).isFalse();
        assertThat(TaskRouting.holder(t)).isEqualTo(assignee);
    }

    @Test
    void unclaimedPool_everyLiveMember_andNoHolder() {
        WorkflowTask t = task(AssigneeType.role, UUID.randomUUID(), TaskStatus.pending);
        assertThat(routing.route(TENANT, t, member)).isEqualTo(Route.pool);
        assertThat(routing.canAct(TENANT, t, stranger)).isFalse();
        assertThat(TaskRouting.holder(t)).isNull();
    }

    /** The hole: claimed -> awaiting_info must stay the claimer's, not fall back to the whole pool. */
    @ParameterizedTest
    @EnumSource(value = TaskStatus.class, names = {"claimed", "awaiting_info"})
    void heldPool_onlyItsClaimer(TaskStatus status) {
        WorkflowTask t = task(AssigneeType.department, UUID.randomUUID(), status);
        t.setClaimedBy(claimer);
        assertThat(routing.route(TENANT, t, claimer)).isEqualTo(Route.claimed);
        assertThat(routing.canAct(TENANT, t, member)).as("another pool member").isFalse();
        assertThat(TaskRouting.holder(t)).isEqualTo(claimer);
    }

    @Test
    void delegated_onlyTheDelegate_notTheOriginalOrTheClaimer() {
        WorkflowTask user = task(AssigneeType.user, assignee, TaskStatus.pending);
        delegateTo(user, assignee);
        assertThat(routing.route(TENANT, user, delegate)).isEqualTo(Route.delegated);
        assertThat(routing.canAct(TENANT, user, assignee)).isFalse();

        WorkflowTask pool = task(AssigneeType.role, UUID.randomUUID(), TaskStatus.claimed);
        pool.setClaimedBy(claimer);
        delegateTo(pool, claimer);
        assertThat(routing.route(TENANT, pool, delegate)).isEqualTo(Route.delegated);
        assertThat(routing.canAct(TENANT, pool, claimer)).isFalse();
        assertThat(routing.canAct(TENANT, pool, member)).isFalse();
        assertThat(TaskRouting.holder(pool)).isEqualTo(delegate);
    }

    @Test
    void escalation_addsARoute_andTheOriginalsKeepTheirs() {
        WorkflowTask user = task(AssigneeType.user, assignee, TaskStatus.pending);
        escalate(user);
        assertThat(routing.route(TENANT, user, target)).isEqualTo(Route.escalated);
        assertThat(routing.route(TENANT, user, assignee)).isEqualTo(Route.assigned);

        WorkflowTask pool = task(AssigneeType.department, UUID.randomUUID(), TaskStatus.pending);
        escalate(pool);
        assertThat(routing.route(TENANT, pool, target)).isEqualTo(Route.escalated);
        assertThat(routing.route(TENANT, pool, member)).isEqualTo(Route.pool);

        WorkflowTask delegated = task(AssigneeType.user, assignee, TaskStatus.pending);
        delegateTo(delegated, assignee);
        escalate(delegated);
        assertThat(routing.route(TENANT, delegated, target)).isEqualTo(Route.escalated);
        assertThat(routing.route(TENANT, delegated, delegate)).isEqualTo(Route.delegated);
        assertThat(routing.canAct(TENANT, delegated, assignee)).isFalse();
        // The escalation target never becomes the holder - delegation stays with the holder.
        assertThat(TaskRouting.holder(delegated)).isEqualTo(delegate);
    }

    private void delegateTo(WorkflowTask t, UUID from) {
        t.setDelegatedFrom(from);
        t.setDelegatedTo(delegate);
        t.setDelegatedAt(OffsetDateTime.now());
    }

    private void escalate(WorkflowTask t) {
        t.setEscalatedAt(OffsetDateTime.now());
        t.setEscalatedTo(target);
    }
}
