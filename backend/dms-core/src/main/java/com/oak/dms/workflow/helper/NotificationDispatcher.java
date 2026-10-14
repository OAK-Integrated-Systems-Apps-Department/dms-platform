package com.oak.dms.workflow.helper;

import com.oak.dms.user.User;
import com.oak.dms.workflow.config.WorkflowProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * The after-commit mail pattern of the workflow and comment listeners (the
 * one ThreatNotificationListener established): hand the work to
 * applicationTaskExecutor so the request never waits on SMTP, and try/catch
 * every recipient so one bad address never stops the rest. Best effort, no
 * retry (decision 24). Off when dms.workflow.notifications.enabled=false.
 */
@Slf4j
@Component
public class NotificationDispatcher {

    private final WorkflowProperties properties;
    private final TaskExecutor taskExecutor;

    public NotificationDispatcher(WorkflowProperties properties,
                                  @Qualifier("applicationTaskExecutor") TaskExecutor taskExecutor) {
        this.properties = properties;
        this.taskExecutor = taskExecutor;
    }

    public void dispatch(String kind, UUID subjectId, Runnable work) {
        if (!properties.getNotifications().isEnabled()) {
            return;
        }
        try {
            taskExecutor.execute(() -> {
                try {
                    work.run();
                } catch (RuntimeException e) {
                    log.warn("Workflow {} notification for {} failed: {}", kind, subjectId, e.getMessage());
                }
            });
        } catch (RuntimeException e) {
            log.warn("Could not schedule workflow {} notification for {}: {}", kind, subjectId, e.getMessage());
        }
    }

    /** One recipient; returns whether the mail went out. */
    public static boolean send(Runnable sender, String kind, User to, UUID subjectId) {
        try {
            sender.run();
            log.info("Workflow {} mail for {} sent to user {}", kind, subjectId, to.getUserId());
            return true;
        } catch (RuntimeException e) {
            log.warn("Workflow {} mail for {} to user {} failed: {}", kind, subjectId, to.getUserId(), e.getMessage());
            return false;
        }
    }
}
