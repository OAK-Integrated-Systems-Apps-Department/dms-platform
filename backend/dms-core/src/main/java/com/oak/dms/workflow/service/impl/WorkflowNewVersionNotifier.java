package com.oak.dms.workflow.service.impl;

import com.oak.dms.document.event.DocumentVersionCreatedEvent;
import com.oak.dms.user.User;
import com.oak.dms.workflow.entity.WorkflowInstance;
import com.oak.dms.workflow.entity.WorkflowTask;
import com.oak.dms.workflow.enums.TaskStatus;
import com.oak.dms.workflow.helper.TaskRouting;
import com.oak.dms.workflow.helper.WorkflowNotificationMailer;
import com.oak.dms.workflow.helper.WorkflowNotificationMailer.Doc;
import com.oak.dms.workflow.helper.WorkflowRecipients;
import com.oak.dms.workflow.repository.WorkflowTaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * "New version uploaded mid-approval" (3.2, N10): when a document changes while
 * its approval is running and the workflow carries on with the version that
 * was submitted (flag-only, or a restart that could not happen), tell the
 * people the approval is currently waiting on, so they know what they are
 * approving is no longer the latest. Called from
 * {@link DocumentVersionChangeListener}, inside the promotion transaction.
 * The uploader is not told about their own upload.
 */
@Component
@RequiredArgsConstructor
public class WorkflowNewVersionNotifier {

    private final WorkflowTaskRepository taskRepository;
    private final TaskRouting taskRouting;
    private final WorkflowRecipients recipients;
    private final WorkflowNotificationMailer mailer;
    private final WorkflowEngine engine;

    public void notifyHolders(WorkflowInstance instance, DocumentVersionCreatedEvent event) {
        UUID stageInstanceId = instance.getCurrentStageInstanceId();
        if (stageInstanceId == null) {
            return;
        }
        Set<UUID> holders = new LinkedHashSet<>();
        for (WorkflowTask task : taskRepository.findByStageInstanceIdAndDeletedFalseOrderByCreatedAtAsc(stageInstanceId)) {
            if (TaskStatus.OPEN.contains(task.getStatus())) {
                holders.addAll(taskRouting.currentHolders(event.tenantId(), task, instance.getInitiatedBy()));
            }
        }
        holders.remove(event.uploadedBy());
        Collection<User> to = recipients.activeUsers(holders, event.tenantId()).values();
        if (to.isEmpty()) {
            return;
        }
        String uploader = engine.displayName(event.uploadedBy());
        mailer.sendNewVersion(to, new Doc(event.documentId(), recipients.documentTitle(event.documentId())),
                uploader != null ? uploader : "Someone", instance.getInstanceId());
    }
}
