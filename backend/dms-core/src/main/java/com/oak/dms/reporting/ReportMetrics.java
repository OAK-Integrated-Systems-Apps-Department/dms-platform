package com.oak.dms.reporting;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Micrometer metrics for Custom Report Generation. Kept here, not in the platform's shared {@code DmsMetrics}, so the feature adds
 * nothing outside its own package. Tags are low-cardinality outcomes only - never a tenant, a user, a template or a filter.
 */
@Component
public class ReportMetrics {

    private final MeterRegistry registry;

    public ReportMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** One finished run. @param outcome completed | empty | failed | cancelled; @param format csv | xlsx | pdf */
    public void run(Duration took, String outcome, String format) {
        Timer.builder("dms.reports.run")
                .description("Time to generate a report file")
                .tag("outcome", outcome)
                .tag("format", format)
                .register(registry)
                .record(took);
    }

    /** Rows written to a finished report file. */
    public void rows(String format, long rows) {
        io.micrometer.core.instrument.DistributionSummary.builder("dms.reports.run.rows")
                .description("Rows in a generated report file")
                .tag("format", format)
                .register(registry)
                .record(rows);
    }

    /** A run refused at request time. @param reason rate_limited | invalid */
    public void refused(String reason) {
        registry.counter("dms.reports.run.refused", "reason", reason).increment();
    }

    /** @param outcome ok | timeout | refused | error */
    public void preview(Duration took, String outcome) {
        Timer.builder("dms.reports.preview")
                .description("Time to count and sample a report preview")
                .tag("outcome", outcome)
                .register(registry)
                .record(took);
    }
}
