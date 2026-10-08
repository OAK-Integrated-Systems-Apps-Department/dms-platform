package com.oak.dms.workflow.service.impl;

import com.oak.dms.workflow.service.WorkflowSlaService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the workflow SLA job (reminders, escalation) on a fixed delay.
 * Every app instance runs it; WorkflowSlaServiceImpl makes that safe.
 * Off when dms.workflow.sla.enabled=false (tests drive runOnce directly).
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "dms.workflow.sla", name = "enabled", havingValue = "true", matchIfMissing = true)
public class WorkflowSlaScheduler {

    private final WorkflowSlaService slaService;

    @Scheduled(fixedDelayString = "${dms.workflow.sla.poll-interval:PT1M}",
            initialDelayString = "${dms.workflow.sla.poll-interval:PT1M}")
    public void run() {
        int handled = slaService.runOnce();
        if (handled > 0) {
            log.info("Workflow SLA run: {} reminder(s)/escalation(s) recorded", handled);
        }
    }
}
