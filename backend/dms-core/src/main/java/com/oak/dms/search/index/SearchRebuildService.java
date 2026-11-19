package com.oak.dms.search.index;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.oak.dms.search.engine.SearchEngine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The two rebuilds (D2, decision O3):
 * <ul>
 *   <li><b>Tenant rebuild</b> (admin button): a new {@code generation}, re-queue every document, and when the queue has
 *       drained delete the tenant's documents still carrying an older generation. Searches keep working throughout
 *       because documents are overwritten in place and the sweep only removes what was not re-indexed.</li>
 *   <li><b>Global rebuild</b> (mapping/analyser change): a new index is created and loaded from the database while live
 *       changes are written to <i>both</i> indices (the indexer writes to each concrete index; a write alias can have
 *       only one write index), then one atomic alias call swaps everything and the old index is dropped. No downtime.</li>
 * </ul>
 * Both reuse the normal queue and indexer, so extraction, ordering and idempotency are identical to live indexing.
 * Neither ever deletes on a partial result: a sweep or swap is abandoned when jobs failed or counts do not match.
 */
@Slf4j
@Component
public class SearchRebuildService {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final SearchEngine engine;
    private final IndexAdmin admin;
    private final SearchIndexer indexer;
    private final SearchJobRepository jobs;
    private final SearchTenantStateRepository tenants;
    private final SearchBackfillService backfill;
    private final SearchSourceRepository source;
    private volatile ExecutorService background;

    public SearchRebuildService(SearchEngine engine, IndexAdmin admin, SearchIndexer indexer, SearchJobRepository jobs,
                                SearchTenantStateRepository tenants, SearchBackfillService backfill,
                                SearchSourceRepository source) {
        this.engine = engine;
        this.admin = admin;
        this.indexer = indexer;
        this.jobs = jobs;
        this.tenants = tenants;
        this.backfill = backfill;
        this.source = source;
    }

    private ExecutorService background() {
        ExecutorService b = background;
        if (b == null) {
            synchronized (this) {
                if (background == null) {
                    background = Executors.newSingleThreadExecutor(r -> {
                        Thread t = new Thread(r, "search-rebuild");
                        t.setDaemon(true);
                        return t;
                    });
                }
                b = background;
            }
        }
        return b;
    }

    // ---- tenant rebuild -----------------------------------------------------------------------------------------

    /** Starts a tenant rebuild in the background and returns the new generation at once (the admin call returns 202). */
    public long startTenantRebuild(UUID tenantId) {
        long generation = tenants.startRebuild(tenantId);
        background().submit(() -> {
            try {
                finishTenantRebuild(tenantId, generation, Duration.ofHours(6));
            } catch (RuntimeException e) {
                log.warn("Search rebuild of tenant {} failed: {}", tenantId, e.getClass().getSimpleName());
                tenants.setBackfill(tenantId, "failed", null);
            }
        });
        return generation;
    }

    /** The synchronous body of a tenant rebuild (also used directly by tests). Returns true when the sweep ran. */
    public boolean finishTenantRebuild(UUID tenantId, long generation, Duration timeout) {
        backfill.run(tenantId, "rebuild");
        if (!pumpUntilDrained(tenantId, timeout) || jobs.failedCount(tenantId) > 0) {
            log.warn("Search rebuild of tenant {} not swept: jobs are still pending or failed", tenantId);
            return false;
        }
        ObjectNode query = JSON.createObjectNode();
        var bool = query.putObject("bool");
        bool.putArray("filter").addObject().putObject("term").put("tenant_id", tenantId.toString());
        bool.withArray("filter").addObject().putObject("range").putObject("generation").put("lt", generation);
        long deleted = 0;
        for (String target : admin.writeTargets()) {
            deleted += engine.deleteByQuery(target, tenantId.toString(), query);
        }
        log.info("Search rebuild of tenant {} swept {} stale document(s)", tenantId, deleted);
        return true;
    }

    // ---- global rebuild -----------------------------------------------------------------------------------------

    public record GlobalResult(String oldIndex, String newIndex, long documents) {
    }

    /**
     * Rebuilds the whole index into a new version and swaps it in with no downtime. Throws (and removes the half-built
     * target) when anything failed, leaving the current index and aliases exactly as they were.
     */
    public GlobalResult rebuildAll(Duration timeout, boolean dropOld) {
        admin.ensureIndex();
        String old = admin.currentIndex();
        String target = admin.startRebuild();
        try {
            List<UUID> all = tenants.activeTenantIds();
            for (UUID tenant : all) {
                if (tenants.enabled(tenant)) {
                    backfill.run(tenant, "rebuild-all");
                }
            }
            if (!pumpUntilDrained(null, timeout) || jobs.failedCount() > 0) {
                throw new IllegalStateException("global rebuild aborted: jobs are pending or failed");
            }
            admin.finishLoad(target);
            long total = 0;
            for (UUID tenant : all) {
                if (!tenants.enabled(tenant)) {
                    continue;
                }
                long expected = source.eligibleCount(tenant);
                long actual = countIn(target, tenant);
                if (actual < expected) {
                    throw new IllegalStateException("global rebuild aborted: tenant " + tenant + " has " + actual
                            + " of " + expected + " documents in the new index");
                }
                total += actual;
            }
            admin.swap(target);
            admin.forgetTenantAliases();
            if (dropOld) {
                admin.dropIndex(old);
            }
            log.info("Search index rebuilt: {} -> {} ({} documents)", old, target, total);
            return new GlobalResult(old, target, total);
        } catch (RuntimeException e) {
            admin.abortRebuild();
            throw e;
        }
    }

    private long countIn(String index, UUID tenant) {
        ObjectNode q = JSON.createObjectNode();
        q.putObject("term").put("tenant_id", tenant.toString());
        engine.refresh(index);
        return engine.count(index, tenant.toString(), q);
    }

    /**
     * Pumps the indexer (alongside the scheduler: the SKIP LOCKED claim makes that safe) until no job is waiting, for one
     * tenant or for all ({@code tenantId == null}). Returns false on timeout.
     */
    private boolean pumpUntilDrained(UUID tenantId, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            long pending = tenantId == null ? jobs.pendingCount() : jobs.pendingCount(tenantId);
            if (pending == 0) {
                return true;
            }
            if (indexer.runOnce() == 0) {
                try {
                    Thread.sleep(200); // jobs waiting for a retry, or claimed by another instance
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return false;
    }
}
