package com.oak.dms.observability;

import com.oak.dms.document.event.DocumentVersionCreatedEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * The DMS-specific half of what Prometheus scrapes from
 * {@code /actuator/prometheus}: how much work the system is doing and how it
 * is turning out. Everything else on the monitoring dashboard - JVM, HTTP,
 * connection pools, containers, Postgres, Redis, RabbitMQ - comes free from
 * Actuator or an exporter and is not duplicated here.
 * <p>
 * Counters only ever go up; the dashboard takes {@code rate()} of them.
 * Nothing here is on a hot path and nothing here can fail an upload: every
 * record method is a plain in-memory increment, and callers are not expected
 * to guard them.
 * <p>
 * No tag takes a user id, file name, tenant id or threat name - those are
 * unbounded (or identifying), and one series per distinct value would both
 * leak and blow up Prometheus's memory. Outcomes are enum names, which are a
 * closed set. Per-tenant breakdowns belong in the DMS's own reporting, not
 * here.
 *
 * @see DmsMetricsGauges for the periodically-refreshed backlog gauges
 */
@Slf4j
@Component
public class DmsMetrics {

    static final String INGESTION_TIMER = "dms.document.ingestion";
    static final String DOCUMENTS_COUNTER = "dms.documents.ingested";
    static final String WORKFLOW_TASKS_COUNTER = "dms.workflow.tasks.decided";
    static final String WORKFLOW_OUTCOMES_COUNTER = "dms.workflow.instances.completed";

    private final MeterRegistry registry;

    public DmsMetrics(MeterRegistry registry) {
        this.registry = registry;
        // Registered up front so the dashboard reads 0 rather than "No data"
        // on an instance that hasn't had an upload or an approval yet. A
        // panel that says "No data" is ambiguous - nothing happened, or the
        // scrape is broken? - and that ambiguity is worst during an incident.
        for (String outcome : new String[]{"clean", "infected", "unscannable", "pending"}) {
            ingestionTimer(outcome);
        }
        for (String kind : new String[]{"new", "version"}) {
            documentsCounter(kind);
        }
        for (String decision : new String[]{"approve", "reject"}) {
            Counter.builder(WORKFLOW_TASKS_COUNTER)
                    .description("Workflow tasks decided by an approver")
                    .tag("decision", decision)
                    .register(registry);
        }
    }

    /**
     * One completed malware scan: how long the engine took, tagged with what
     * it decided. Called from the scan gate's processor, which already times
     * the scan for its own audit record.
     *
     * @param outcome the verdict - a {@code ScanVerdictStatus} name, lower-cased
     * @param engine  the scan engine, e.g. {@code ClamAV} - a closed set of deployed engines
     */
    public void recordScan(String outcome, String engine, long durationMs) {
        Timer.builder(INGESTION_TIMER)
                .description("Malware scan of one uploaded file, by verdict")
                .tag("outcome", safe(outcome))
                .tag("engine", safe(engine))
                .register(registry)
                .record(durationMs, TimeUnit.MILLISECONDS);
    }

    /** A workflow task someone approved or rejected. {@code decision} is a {@code TaskDecision} name. */
    public void recordWorkflowDecision(String decision) {
        Counter.builder(WORKFLOW_TASKS_COUNTER)
                .description("Workflow tasks decided by an approver")
                .tag("decision", safe(decision))
                .register(registry)
                .increment();
    }

    /** A workflow instance that reached a terminal state. {@code status} is an {@code InstanceStatus} name. */
    public void recordWorkflowOutcome(String status) {
        Counter.builder(WORKFLOW_OUTCOMES_COUNTER)
                .description("Workflow instances that reached a terminal state")
                .tag("status", safe(status))
                .register(registry)
                .increment();
    }

    /**
     * A document version that passed its scan and became readable - the
     * closest thing the DMS has to "work delivered". Listens rather than
     * being called, so promotion itself stays untouched; the listener is
     * synchronous and inside the promotion transaction, hence the catch:
     * a metrics failure must never roll back a stored document.
     */
    @EventListener
    public void onDocumentVersionCreated(DocumentVersionCreatedEvent event) {
        try {
            documentsCounter(event.previousLatestVersionId() == null ? "new" : "version").increment();
        } catch (RuntimeException e) {
            log.debug("Could not count document version {}", event.newVersionId(), e);
        }
    }

    private Counter documentsCounter(String kind) {
        return Counter.builder(DOCUMENTS_COUNTER)
                .description("Document versions promoted into the DMS")
                .tag("kind", kind)
                .register(registry);
    }

    private Timer ingestionTimer(String outcome) {
        return Timer.builder(INGESTION_TIMER)
                .description("Malware scan of one uploaded file, by verdict")
                .tag("outcome", outcome)
                .tag("engine", "none")
                .register(registry);
    }

    /** Tag values are a closed set by contract; this is the backstop against a null or stray value. */
    private static String safe(String value) {
        return value == null || value.isBlank() ? "unknown" : value.toLowerCase(java.util.Locale.ROOT);
    }
}
