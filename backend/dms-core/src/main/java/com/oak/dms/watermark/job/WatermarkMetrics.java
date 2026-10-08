package com.oak.dms.watermark.job;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * What watermarking reports to Prometheus.
 *
 * <h2>Tag discipline</h2>
 * Only {@code format} and {@code reason}, both drawn from fixed sets that
 * cannot grow at runtime. Never tenant, user, document or job id.
 * <p>
 * This is not tidiness. Every distinct tag combination is a separate time
 * series held in memory in this process and again in Prometheus; tagging by
 * tenant on a multi-tenant system means the cardinality of the metric is the
 * number of customers, and tagging by document means it is unbounded and
 * grows for as long as the system is used. A metric that falls over the
 * monitoring system is worse than no metric.
 * <p>
 * {@code reason} comes from {@link WatermarkFailure}, which exists partly to
 * guarantee that - a free-text failure reason as a tag would be unbounded by
 * construction, since some of those strings contain file names.
 */
@Component
@ConditionalOnProperty(prefix = "dms.watermark", name = "enabled", havingValue = "true")
public class WatermarkMetrics {

    private final MeterRegistry registry;

    /**
     * Threads lost to a format call that overran and would not be
     * interrupted - see {@code WatermarkExecutorConfig}. A gauge rather than
     * a counter because what matters is how many are gone RIGHT NOW against
     * the pool size: this number reaching the pool size means watermarking
     * has stopped working and only a restart will fix it.
     */
    private final AtomicInteger abandonedThreads = new AtomicInteger();

    public WatermarkMetrics(MeterRegistry registry) {
        this.registry = registry;
        registry.gauge("watermark.format.threads.abandoned", abandonedThreads);
    }

    /**
     * One job picked up by a worker thread. Named for what it measures: a
     * row exists from the moment the request commits, but a row is not work
     * in progress until something claims it, and only this number minus the
     * completed and failed ones answers "how many are in flight right now".
     */
    public void started(String format) {
        Counter.builder("watermark.jobs.started")
                .tag("format", format)
                .description("Watermark jobs claimed by a worker, including any resumed after a restart")
                .register(registry)
                .increment();
    }

    public void completed(String format, Duration took) {
        Timer.builder("watermark.job.duration")
                .tag("format", format)
                .tag("outcome", "completed")
                .description("Time to produce a watermarked copy, end to end in the worker")
                .register(registry)
                .record(took);
    }

    public void failed(String format, WatermarkFailure reason, Duration took) {
        Counter.builder("watermark.jobs.failed")
                .tag("format", format)
                .tag("reason", reason.code())
                .description("Watermark jobs that did not produce a copy")
                .register(registry)
                .increment();
        Timer.builder("watermark.job.duration")
                .tag("format", format)
                .tag("outcome", "failed")
                .register(registry)
                .record(took);
    }

    /** One more thread lost to a format call that would not stop. */
    public int threadAbandoned() {
        return abandonedThreads.incrementAndGet();
    }

    public int abandonedThreads() {
        return abandonedThreads.get();
    }
}
