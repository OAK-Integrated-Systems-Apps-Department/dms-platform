package com.oak.dms.search.index;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.oak.dms.search.config.SearchProperties;
import com.oak.dms.search.engine.SearchEngine;
import com.oak.dms.search.engine.SearchEngineException;
import com.oak.dms.search.index.SearchJobRepository.Job;
import com.oak.dms.search.observability.SearchMetrics;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The bulk indexer (D10). One {@link #runOnce()} claims a batch of jobs, builds each index document from the source
 * of truth (extraction runs in parallel on a small private pool), writes everything in one {@code _bulk} request and
 * settles each job individually: a bad document fails only its own job.
 *
 * <p>Idempotent and order-safe: every write carries the job's {@code change_counter} as the external version, so a late
 * job can never overwrite newer data, and a version conflict counts as success. Nothing here logs document text; only
 * ids, counts, durations and error classes appear in logs and in {@code last_error}.
 *
 * <p>The pool is a private field, deliberately not an {@code Executor} bean: declaring one would switch off Boot's
 * default task executor (see the comment on the watermark executor).
 */
@Slf4j
@Component
public class SearchIndexer {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int SUBTREE_PAGE = 500;

    private final SearchProperties props;
    private final SearchEngine engine;
    private final IndexAdmin admin;
    private final SearchJobRepository jobs;
    private final SearchTenantStateRepository tenants;
    private final SearchSourceRepository source;
    private final IndexDocumentBuilder builder;
    private final SearchMetrics metrics;
    private volatile ExecutorService pool;

    public SearchIndexer(SearchProperties props, SearchEngine engine, IndexAdmin admin, SearchJobRepository jobs,
                         SearchTenantStateRepository tenants, SearchSourceRepository source,
                         IndexDocumentBuilder builder, SearchMetrics metrics) {
        this.props = props;
        this.engine = engine;
        this.admin = admin;
        this.jobs = jobs;
        this.tenants = tenants;
        this.source = source;
        this.builder = builder;
        this.metrics = metrics;
    }

    private ExecutorService pool() {
        ExecutorService p = pool;
        if (p == null) {
            synchronized (this) {
                if (pool == null) {
                    AtomicInteger n = new AtomicInteger();
                    pool = Executors.newFixedThreadPool(Math.max(1, props.getIndexer().getWorkers()), r -> {
                        Thread t = new Thread(r, "search-extract-" + n.incrementAndGet());
                        t.setDaemon(true);
                        return t;
                    });
                }
                p = pool;
            }
        }
        return p;
    }

    @PreDestroy
    void shutdown() {
        if (pool != null) {
            pool.shutdownNow();
        }
    }

    /** Claims and processes one batch; returns how many jobs were claimed (0 = idle, engine down, or switched off). */
    public int runOnce() {
        if (!props.isEnabled()) {
            return 0;
        }
        // Do not burn a job's attempts while the engine is down: leave the queue untouched and try again next tick.
        if (engine.health() == SearchEngine.Health.DOWN) {
            return 0;
        }
        List<Job> claimed = jobs.claim(props.getIndexer().getLease(), props.getIndexer().getBatchSize());
        if (claimed.isEmpty()) {
            return 0;
        }
        try {
            admin.ensureIndex();
        } catch (SearchEngineException e) {
            claimed.forEach(j -> jobs.fail(j, "search engine unavailable: " + e.getMessage(), backoff(j)));
            return claimed.size();
        }

        List<Job> documents = new ArrayList<>();
        for (Job j : claimed) {
            switch (j.kind()) {
                case "document" -> documents.add(j);
                case "folder_subtree" -> runSafely(j, () -> expandFolder(j));
                case "tenant_purge" -> runSafely(j, () -> purgeTenant(j));
                default -> jobs.fail(j, "unknown job kind " + j.kind(), backoff(j));
            }
        }
        if (!documents.isEmpty()) {
            indexDocuments(documents);
        }
        return claimed.size();
    }

    // ---- document jobs ---------------------------------------------------------------------------------------

    private record Prepared(Job job, IndexDocumentBuilder.Built built, Throwable error) {
    }

    private void indexDocuments(List<Job> documents) {
        IndexDocumentBuilder.Context ctx = new IndexDocumentBuilder.Context();
        ConcurrentHashMap<UUID, Long> generations = new ConcurrentHashMap<>();
        List<Future<Prepared>> futures = new ArrayList<>();
        for (Job job : documents) {
            futures.add(pool().submit(() -> {
                try {
                    long generation = generations.computeIfAbsent(job.tenantId(), tenants::generation);
                    boolean enabled = tenants.enabled(job.tenantId());
                    IndexDocumentBuilder.Built built = enabled
                            ? builder.build(job.tenantId(), job.documentId(), job.changeCounter(), generation, ctx)
                            : IndexDocumentBuilder.Built.remove();
                    return new Prepared(job, built, null);
                } catch (Throwable t) {
                    return new Prepared(job, null, t);
                }
            }));
        }

        List<String> targets;
        try {
            targets = admin.writeTargets();
        } catch (SearchEngineException e) {
            documents.forEach(j -> jobs.fail(j, "search engine unavailable: " + e.getMessage(), backoff(j)));
            return;
        }

        List<SearchEngine.BulkOp> ops = new ArrayList<>();
        List<Job> opOwner = new ArrayList<>();
        List<Prepared> ready = new ArrayList<>();
        for (Future<Prepared> f : futures) {
            Prepared p;
            try {
                p = f.get();
            } catch (Exception e) {
                // The pool itself failed: nothing to attribute to a single job; retry the whole batch's remainder.
                log.warn("Search extraction worker failed: {}", e.getClass().getSimpleName());
                continue;
            }
            if (p.error() != null) {
                jobs.fail(p.job(), "build failed: " + p.error().getClass().getSimpleName(), backoff(p.job()));
                metrics.jobsProcessed("document", "failed", 1);
                continue;
            }
            ready.add(p);
            Job j = p.job();
            for (String target : targets) {
                ops.add(p.built().delete()
                        ? SearchEngine.BulkOp.delete(target, j.documentId().toString(), j.tenantId().toString(), j.changeCounter())
                        : SearchEngine.BulkOp.index(target, j.documentId().toString(), j.tenantId().toString(),
                                j.changeCounter(), p.built().json()));
                opOwner.add(j);
            }
        }
        if (ops.isEmpty()) {
            return;
        }

        SearchEngine.BulkResult result;
        try {
            result = metrics.bulkTimer().recordCallable(() -> engine.bulk(ops));
        } catch (SearchEngineException e) {
            ready.forEach(p -> jobs.fail(p.job(), "bulk failed: " + e.getMessage(), backoff(p.job())));
            metrics.jobsProcessed("document", "failed", ready.size());
            return;
        } catch (Exception e) {
            ready.forEach(p -> jobs.fail(p.job(), "bulk failed: " + e.getClass().getSimpleName(), backoff(p.job())));
            return;
        }

        java.util.Map<UUID, String> errors = new java.util.HashMap<>();
        List<SearchEngine.BulkItemResult> items = result.items();
        for (int i = 0; i < ops.size() && i < items.size(); i++) {
            if (!items.get(i).ok()) {
                errors.putIfAbsent(opOwner.get(i).jobId(), items.get(i).error());
            }
        }
        java.util.Set<UUID> touched = new java.util.HashSet<>();
        for (Prepared p : ready) {
            touched.add(p.job().tenantId());
            String error = errors.get(p.job().jobId());
            if (error == null) {
                jobs.complete(p.job());
                metrics.jobsProcessed("document", p.built().delete() ? "deleted" : "indexed", 1);
                if (!p.built().delete()) {
                    afterIndexed(p.job().tenantId());
                }
            } else {
                jobs.fail(p.job(), "index rejected: " + error, backoff(p.job()));
                metrics.jobsProcessed("document", "failed", 1);
            }
        }
        touched.forEach(tenants::markReadyIfDrained);
    }

    private final java.util.Set<UUID> aliased = ConcurrentHashMap.newKeySet();

    private void afterIndexed(UUID tenantId) {
        if (aliased.add(tenantId)) {
            try {
                tenants.ensure(tenantId);
                admin.ensureTenantAlias(tenantId);
            } catch (RuntimeException e) {
                aliased.remove(tenantId);
                log.warn("Could not create the search alias of tenant {}: {}", tenantId, e.getClass().getSimpleName());
            }
        }
    }

    // ---- folder subtree and tenant purge ---------------------------------------------------------------------

    private void expandFolder(Job job) {
        UUID after = null;
        while (true) {
            List<UUID> page = source.documentsInSubtree(job.tenantId(), job.folderId(), after, SUBTREE_PAGE);
            if (page.isEmpty()) {
                return;
            }
            for (UUID id : page) {
                jobs.enqueueDocument(job.tenantId(), id, "folder");
            }
            after = page.get(page.size() - 1);
        }
    }

    private void purgeTenant(Job job) {
        ObjectNode query = JSON.createObjectNode();
        query.putObject("term").put("tenant_id", job.tenantId().toString());
        for (String target : admin.writeTargets()) {
            engine.deleteByQuery(target, job.tenantId().toString(), query);
        }
        admin.dropTenantAlias(job.tenantId());
        aliased.remove(job.tenantId());
    }

    private void runSafely(Job job, Runnable action) {
        try {
            action.run();
            jobs.complete(job);
            metrics.jobsProcessed(job.kind(), "done", 1);
        } catch (RuntimeException e) {
            jobs.fail(job, job.kind() + " failed: " + e.getClass().getSimpleName(), backoff(job));
            metrics.jobsProcessed(job.kind(), "failed", 1);
        }
    }

    // ---- retry timing ----------------------------------------------------------------------------------------

    /** Exponential backoff from {@code base-backoff}, doubling per attempt, capped at {@code max-backoff}. */
    Duration backoff(Job job) {
        SearchProperties.Indexer cfg = props.getIndexer();
        long seconds = cfg.getBaseBackoff().toSeconds() << Math.min(20, Math.max(0, job.attempts() - 1));
        return Duration.ofSeconds(Math.min(cfg.getMaxBackoff().toSeconds(), Math.max(1, seconds)));
    }
}
