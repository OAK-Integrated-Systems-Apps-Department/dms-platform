package com.oak.dms.document;

import com.oak.dms.document.event.DocumentVersionStoredEvent;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Makes a version's preview and thumbnail as soon as it is stored (DPRM-FR-001, DPRM-FR-003), without making the
 * upload wait: after the promotion commits, the work goes to a small pool of its own.
 * <p>
 * The pool is bounded (DPRM-NFR-004): a bulk import queues up to {@code dms.preview.queue} versions and works through
 * them {@code dms.preview.workers} at a time; past that, a version simply isn't queued - which costs nothing,
 * because a rendition that is missing is made on demand the first time someone opens it. Deliberately NOT a Spring
 * {@code Executor} bean: declaring one would switch off Boot's {@code applicationTaskExecutor}, which the export
 * worker uses.
 */
@Slf4j
@Component
public class RenditionGenerationListener {

    private final DocumentRenditionService renditions;
    private final ThreadPoolExecutor pool;

    RenditionGenerationListener(DocumentRenditionService renditions,
                                @Value("${dms.preview.workers:2}") int workers,
                                @Value("${dms.preview.queue:500}") int queue) {
        this.renditions = renditions;
        AtomicInteger number = new AtomicInteger();
        this.pool = new ThreadPoolExecutor(workers, workers, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(queue),
                task -> {
                    Thread thread = new Thread(task, "rendition-" + number.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                });
        this.pool.allowCoreThreadTimeOut(true);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onStored(DocumentVersionStoredEvent event) {
        try {
            pool.execute(() -> renditions.generate(event.tenantId(), event.versionId()));
        } catch (RejectedExecutionException full) {
            log.info("Rendition queue is full - version {} will be made when it is first opened", event.versionId());
        }
    }

    @PreDestroy
    void stop() {
        pool.shutdownNow();
    }
}
