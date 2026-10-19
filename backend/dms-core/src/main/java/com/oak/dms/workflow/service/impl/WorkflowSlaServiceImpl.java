package com.oak.dms.workflow.service.impl;

import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.catalog.AuditObjectTypes;
import com.oak.dms.workflow.config.WorkflowProperties;
import com.oak.dms.workflow.entity.WorkflowInstance;
import com.oak.dms.workflow.entity.WorkflowStageInstance;
import com.oak.dms.workflow.entity.WorkflowTask;
import com.oak.dms.workflow.enums.EscalationTarget;
import com.oak.dms.workflow.enums.InstanceStatus;
import com.oak.dms.workflow.enums.StageInstanceStatus;
import com.oak.dms.workflow.enums.TaskStatus;
import com.oak.dms.workflow.enums.WorkflowActionType;
import com.oak.dms.workflow.event.WorkflowTaskEscalatedEvent;
import com.oak.dms.workflow.event.WorkflowTaskReminderEvent;
import com.oak.dms.workflow.helper.EscalationTargetResolver;
import com.oak.dms.workflow.helper.WorkflowActionRecorder;
import com.oak.dms.workflow.helper.WorkflowLocks;
import com.oak.dms.workflow.repository.WorkflowInstanceRepository;
import com.oak.dms.workflow.repository.WorkflowSlaRepository.DueTask;
import com.oak.dms.workflow.repository.WorkflowSlaRepository;
import com.oak.dms.workflow.repository.WorkflowStageInstanceRepository;
import com.oak.dms.workflow.repository.WorkflowTaskRepository;
import com.oak.dms.workflow.service.WorkflowSlaService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Phase 2 plan §6. One short transaction per task: lock its instance row
 * FOR UPDATE SKIP LOCKED (skip it if another app instance or a user's
 * approve/cancel holds it), then the stage instance and the task - the
 * global lock order - and re-check everything under the locks. A second
 * runner, or a retry, finds reminder_sent_at/escalated_at already set and
 * does nothing, so each fires once per task (decision 7). No TenantContext:
 * audit rows carry the task's tenant and the SYSTEM actor.
 */
@Slf4j
@Service
public class WorkflowSlaServiceImpl implements WorkflowSlaService {

    private final WorkflowSlaRepository slaRepository;
    private final WorkflowInstanceRepository instanceRepository;
    private final WorkflowStageInstanceRepository stageInstanceRepository;
    private final WorkflowTaskRepository taskRepository;
    private final EscalationTargetResolver targetResolver;
    private final WorkflowActionRecorder actions;
    private final WorkflowEngine engine;
    private final WorkflowLocks locks;
    private final WorkflowProperties properties;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionTemplate transactionTemplate;

    public WorkflowSlaServiceImpl(WorkflowSlaRepository slaRepository, WorkflowInstanceRepository instanceRepository,
                                  WorkflowStageInstanceRepository stageInstanceRepository,
                                  WorkflowTaskRepository taskRepository,
                                  EscalationTargetResolver targetResolver, WorkflowActionRecorder actions,
                                  WorkflowEngine engine, WorkflowLocks locks, WorkflowProperties properties,
                                  ApplicationEventPublisher eventPublisher,
                                  PlatformTransactionManager transactionManager) {
        this.slaRepository = slaRepository;
        this.instanceRepository = instanceRepository;
        this.stageInstanceRepository = stageInstanceRepository;
        this.taskRepository = taskRepository;
        this.targetResolver = targetResolver;
        this.actions = actions;
        this.engine = engine;
        this.locks = locks;
        this.properties = properties;
        this.eventPublisher = eventPublisher;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public int runOnce() {
        int handled = 0;
        for (DueTask due : slaRepository.findDue(Math.max(1, properties.getSla().getBatchSize()))) {
            try {
                Boolean done = transactionTemplate.execute(status -> process(due));
                if (Boolean.TRUE.equals(done)) {
                    handled++;
                }
            } catch (RuntimeException e) {
                // One bad row never stops the batch; it is retried on the next run.
                log.warn("Workflow SLA handling failed for task {}: {}", due.taskId(), e.getMessage(), e);
            }
        }
        return handled;
    }

    private boolean process(DueTask due) {
        locks.applyLockTimeout();
        if (!slaRepository.lockInstanceSkipLocked(due.instanceId(), due.tenantId())) {
            return false;
        }
        // Read only after the row lock above, so nothing is stale.
        WorkflowInstance instance = instanceRepository.findById(due.instanceId()).orElseThrow();
        WorkflowStageInstance stage = stageInstanceRepository
                .lockByStageInstanceIdAndTenantId(due.stageInstanceId(), due.tenantId()).orElseThrow();
        WorkflowTask task = taskRepository.lockByTaskIdAndTenantId(due.taskId(), due.tenantId()).orElseThrow();

        if (instance.getStatus() != InstanceStatus.in_progress || stage.getStatus() != StageInstanceStatus.active
                || !TaskStatus.DECIDABLE.contains(task.getStatus())) {
            return false;   // decided, cancelled or paused for information (decision 8) since the scan
        }
        // due_at may be null (inactivity-only task); each branch below checks it where needed.
        // Every threshold and the escalation target are resolved fresh in the scan SQL (due-date reminder and
        // escalation target from the stage only; inactivity hours the stage's own value, else the template
        // default), so a stale L2-cached template entity can never shadow a just-changed default (N-NFR-004).
        Integer reminderBeforeHours = due.reminderBeforeHours();
        Integer inactivityHours = due.inactivityReminderHours();
        Integer escalateAfterHours = due.escalateAfterReminderHours();
        EscalationTarget escalationTarget = EscalationTarget.valueOf(due.escalationTarget());
        OffsetDateTime now = OffsetDateTime.now();
        boolean canEscalate = task.getEscalatedAt() == null && escalationTarget != EscalationTarget.none;

        // Escalation fires once, at the first trigger - due-date, or (after its reminder) inactivity.
        if (canEscalate && task.getDueAt() != null && !task.getDueAt().isAfter(now)) {
            escalate(instance, stage, due, escalationTarget, task, now);
            return true;
        }
        if (canEscalate && task.getInactivityReminderSentAt() != null && escalateAfterHours != null
                && !task.getInactivityReminderSentAt().plusHours(escalateAfterHours).isAfter(now)) {
            escalate(instance, stage, due, escalationTarget, task, now);
            return true;
        }
        // Due-date reminder (before due) and inactivity reminder each fire at most once (separate stamps).
        if (task.getReminderSentAt() == null && reminderBeforeHours != null && task.getDueAt() != null
                && task.getDueAt().isAfter(now)
                && !task.getDueAt().minusHours(reminderBeforeHours).isAfter(now)) {
            remind(instance, stage, reminderBeforeHours, task, now);
            return true;
        }
        if (task.getInactivityReminderSentAt() == null && inactivityHours != null) {
            OffsetDateTime base = task.getLastActivityAt() != null ? task.getLastActivityAt() : task.getCreatedAt();
            if (base != null && !base.plusHours(inactivityHours).isAfter(now)) {
                remindInactivity(instance, stage, task, now, inactivityHours);
                return true;
            }
        }
        return false;
    }

    /** Inactivity reminder (N-FR-003): no activity for N hours. Stamps its own fire-once column. */
    private void remindInactivity(WorkflowInstance instance, WorkflowStageInstance stage, WorkflowTask task,
                                  OffsetDateTime now, int inactivityHours) {
        task.setInactivityReminderSentAt(now);
        taskRepository.saveAndFlush(task);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("stageOrder", stage.getStageOrder());
        detail.put("reason", "inactivity");
        detail.put("inactivityReminderHours", inactivityHours);
        actions.record(instance, stage.getStageInstanceId(), task.getTaskId(), WorkflowActionType.reminded, null, null,
                detail);
        audit(AuditActionCatalog.WORKFLOW_TASK_REMINDER_SENT, instance, task, detail);
        eventPublisher.publishEvent(new WorkflowTaskReminderEvent(instance.getTenantId(), instance.getInstanceId(),
                task.getTaskId()));
    }

    private void remind(WorkflowInstance instance, WorkflowStageInstance stage, int reminderBeforeHours,
                        WorkflowTask task, OffsetDateTime now) {
        task.setReminderSentAt(now);
        taskRepository.saveAndFlush(task);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("stageOrder", stage.getStageOrder());
        if (task.getDueAt() != null) {
            detail.put("dueAt", task.getDueAt().toString());
        }
        detail.put("reminderBeforeHours", reminderBeforeHours);
        actions.record(instance, stage.getStageInstanceId(), task.getTaskId(), WorkflowActionType.reminded, null, null,
                detail);
        audit(AuditActionCatalog.WORKFLOW_TASK_REMINDER_SENT, instance, task, detail);
        eventPublisher.publishEvent(new WorkflowTaskReminderEvent(instance.getTenantId(), instance.getInstanceId(),
                task.getTaskId()));
    }

    /**
     * Escalation adds a route; the task stays open and expired is never
     * written (decision 6). An unusable target is stamped too, so it fires
     * once, and admins are alerted instead (A2).
     */
    private void escalate(WorkflowInstance instance, WorkflowStageInstance stage, DueTask due,
                          EscalationTarget escalationTarget, WorkflowTask task, OffsetDateTime now) {
        EscalationTargetResolver.Resolution target = targetResolver.resolve(escalationTarget,
                due.escalationUserId(), task, instance);
        task.setEscalatedAt(now);
        task.setEscalatedTo(target.userId());
        taskRepository.saveAndFlush(task);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("stageOrder", stage.getStageOrder());
        if (task.getDueAt() != null) {
            detail.put("dueAt", task.getDueAt().toString());
        }
        detail.put("escalationTarget", escalationTarget.name());
        detail.put("source", target.source());
        if (target.userId() != null) {
            detail.put("target", target.userId().toString());
            detail.put("targetName", engine.displayName(target.userId()));
        } else {
            detail.put("skipped", target.skippedReason());
        }
        actions.record(instance, stage.getStageInstanceId(), task.getTaskId(), WorkflowActionType.escalated, null, null,
                detail);
        audit(AuditActionCatalog.WORKFLOW_TASK_ESCALATED, instance, task, detail);
        eventPublisher.publishEvent(new WorkflowTaskEscalatedEvent(instance.getTenantId(), instance.getInstanceId(),
                task.getTaskId(), target.userId(), target.skippedReason()));
    }

    private void audit(String action, WorkflowInstance instance, WorkflowTask task, Map<String, Object> extra) {
        Map<String, Object> detail = new LinkedHashMap<>(extra);
        detail.put("taskId", task.getTaskId());
        detail.put("stageInstanceId", task.getStageInstanceId());
        detail.put("assigneeType", task.getAssigneeType().name());
        detail.put("assigneeId", task.getAssigneeId());
        engine.audit(action, instance)
                .systemActor()
                .objectType(AuditObjectTypes.WORKFLOW_TASK)
                .objectId(task.getTaskId())
                .newValue(engine.instanceSummary(instance, detail))
                .log();
    }
}
