package com.oak.dms.workflow.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Binds {@code dms.workflow.*}. Mirrors ScanProperties' notifications block. */
@Component
@ConfigurationProperties(prefix = "dms.workflow")
@Getter
@Setter
public class WorkflowProperties {

    private Notifications notifications = new Notifications();

    private Sla sla = new Sla();

    @Getter
    @Setter
    public static class Notifications {
        /** Task-assigned, outcome and unassignable-stage mails. */
        private boolean enabled = true;
        private String mailFrom = "no-reply@oakdms.local";
    }

    /** The reminder/escalation scheduler (WorkflowSlaScheduler, Phase 2). */
    @Getter
    @Setter
    public static class Sla {
        /** Off in tests, which drive {@code WorkflowSlaService.runOnce()} directly. */
        private boolean enabled = true;
        /** Tasks handled per run; each in its own short transaction. */
        private int batchSize = 50;
    }
}
