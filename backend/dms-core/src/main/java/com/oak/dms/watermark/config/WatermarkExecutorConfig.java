package com.oak.dms.watermark.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * The two pools watermarking runs on, and why there are two.
 *
 * <h2>The job pool</h2>
 * Bounded, small, with a bounded queue and {@link ThreadPoolExecutor.AbortPolicy}.
 * A full queue is a <b>rejection</b>, not a wait: the request side turns it
 * into a 503 with {@code Retry-After} and fails the job row, which is an
 * honest answer. {@code CallerRunsPolicy} would instead run the watermark on
 * the HTTP thread - a request that should have taken 20ms taking 10 seconds,
 * and a tomcat thread tied up producing a document.
 *
 * <h2>The format pool, and why it is WIDER than the job pool</h2>
 * POI and PDFBox do not check {@link Thread#interrupted()}. A job that
 * overruns its timeout cannot be stopped - {@code Future.cancel(true)}
 * interrupts a thread that will not look, so the thread keeps working on a
 * file nobody wants any more until it finishes or the JVM dies. It is
 * ABANDONED, not recovered.
 * <p>
 * If the format pool were the same size as the job pool, two such files -
 * two corrupt PDFs, say - would permanently consume both threads and the
 * feature would stop working until a restart, with no error anywhere saying
 * why. Making it wider means the pool absorbs a few abandonments and keeps
 * going, while {@code watermark.format.threads.abandoned} climbs so somebody
 * can see it happening.
 * <p>
 * Its queue is a {@link SynchronousQueue}: there is no point queueing format
 * work, because the job pool in front already limits how much can be in
 * flight. Anything the format pool cannot take immediately means every
 * thread is abandoned, which is a condition to report, not to queue behind.
 *
 * <h2>Conditional on the feature flag</h2>
 * No flag, no pools - a deployment that never switched watermarking on
 * carries no threads for it. {@code WatermarkDisabledIT} asserts that.
 *
 * <h2>Why both beans are {@code defaultCandidate = false}</h2>
 * Spring Boot's {@code applicationTaskExecutor} is declared
 * {@code @ConditionalOnMissingBean(Executor.class)}: ANY {@code Executor}
 * bean in the context makes it back off. Declaring these two pools without
 * that flag silently removed {@code applicationTaskExecutor}, and
 * {@code ExportJobWorker} and {@code ThreatNotificationListener} - which
 * both inject it by that exact name - stopped starting. Switching
 * watermarking on must not be able to break bulk export.
 * <p>
 * {@code defaultCandidate = false} keeps a bean out of by-type autowiring,
 * and so out of that condition, while an explicit {@code @Qualifier} still
 * resolves it - which is how {@code WatermarkJobWorker} asks for both, so
 * no injection point is lost. {@code WatermarkPipelineIT} asserts
 * {@code applicationTaskExecutor} is still present with the flag on.
 */
@Slf4j
@Configuration
@ConditionalOnProperty(prefix = "dms.watermark", name = "enabled", havingValue = "true")
public class WatermarkExecutorConfig {

    public static final String JOB_EXECUTOR = "watermarkJobExecutor";
    public static final String FORMAT_EXECUTOR = "watermarkFormatExecutor";

    /**
     * Runs whole jobs: read the document, draw, store. One task here holds a
     * thread for the lifetime of the job.
     */
    @Bean(name = JOB_EXECUTOR, destroyMethod = "shutdown", defaultCandidate = false)
    public ThreadPoolTaskExecutor watermarkJobExecutor(WatermarkProperties properties) {
        WatermarkProperties.Executor limits = properties.getExecutor();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(limits.getJobCoreSize());
        executor.setMaxPoolSize(limits.getJobMaxSize());
        executor.setQueueCapacity(limits.getJobQueueCapacity());
        executor.setThreadNamePrefix("wm-job-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        // Don't hold shutdown open for work nobody is waiting for: a job
        // interrupted by a restart is resumed from its row on the way back
        // up, so losing it costs nothing.
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        log.info("Watermark job executor: {} threads, queue {}", limits.getJobMaxSize(),
                limits.getJobQueueCapacity());
        return executor;
    }

    /**
     * Runs only the library call that cannot be interrupted, so that call
     * can be abandoned without abandoning the job thread that is waiting on
     * it - and so the job thread stays free to record the failure.
     */
    @Bean(name = FORMAT_EXECUTOR, destroyMethod = "shutdown", defaultCandidate = false)
    public ThreadPoolTaskExecutor watermarkFormatExecutor(WatermarkProperties properties) {
        WatermarkProperties.Executor limits = properties.getExecutor();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        // Core == max with a SynchronousQueue: ThreadPoolExecutor only grows
        // past the core size once the queue refuses, and a SynchronousQueue
        // refuses immediately, so this is the one arrangement where the max
        // is actually reachable.
        executor.setCorePoolSize(limits.getFormatMaxSize());
        executor.setMaxPoolSize(limits.getFormatMaxSize());
        executor.setQueueCapacity(0);
        executor.setThreadNamePrefix("wm-format-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(false);
        // Abandoned threads must never hold the JVM open at shutdown.
        executor.setDaemon(true);
        executor.initialize();
        log.info("Watermark format executor: {} threads, no queue", limits.getFormatMaxSize());
        return executor;
    }
}
