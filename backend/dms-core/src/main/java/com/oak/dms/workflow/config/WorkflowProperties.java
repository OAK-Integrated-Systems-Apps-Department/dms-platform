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

    @Getter
    @Setter
    public static class Notifications {
        /** Task-assigned, outcome and unassignable-stage mails. */
        private boolean enabled = true;
        private String mailFrom = "no-reply@oakdms.local";
    }
}
