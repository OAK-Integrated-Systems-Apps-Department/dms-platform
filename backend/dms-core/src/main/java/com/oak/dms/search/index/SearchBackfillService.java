package com.oak.dms.search.index;

import com.oak.dms.search.engine.SearchEngineException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * Queues every document that belongs in the index for one tenant, through the same queue and indexer as live changes
 * (so extraction, versioning and dual-writes during a rebuild behave identically). Resumable: the keyset cursor is
 * saved per page, so an interrupted backfill continues where it stopped, and re-queuing a document is harmless
 * (one coalesced job per document). The tenant becomes {@code index_ready} when the backfill is done and its queue is
 * drained; until then searches use the title-search fallback.
 */
@Slf4j
@Component
public class SearchBackfillService {

    private static final int PAGE = 500;

    private final SearchSourceRepository source;
    private final SearchJobRepository jobs;
    private final SearchTenantStateRepository tenants;
    private final IndexAdmin admin;

    public SearchBackfillService(SearchSourceRepository source, SearchJobRepository jobs, SearchTenantStateRepository tenants,
                                 IndexAdmin admin) {
        this.source = source;
        this.jobs = jobs;
        this.tenants = tenants;
        this.admin = admin;
    }

    /** Queues the tenant's documents (continuing from the saved cursor); returns how many were queued by this call. */
    public long run(UUID tenantId, String reason) {
        SearchTenantStateRepository.State state = tenants.ensure(tenantId);
        UUID after = "running".equals(state.backfillState()) ? state.backfillCursor() : null;
        tenants.setBackfill(tenantId, "running", after);
        long queued = 0;
        try {
            while (true) {
                List<UUID> page = source.eligibleIds(tenantId, after, PAGE);
                if (page.isEmpty()) {
                    break;
                }
                for (UUID id : page) {
                    jobs.enqueueDocument(tenantId, id, reason);
                }
                queued += page.size();
                after = page.get(page.size() - 1);
                tenants.setBackfill(tenantId, "running", after);
            }
        } catch (RuntimeException e) {
            tenants.setBackfill(tenantId, "failed", after);
            throw e;
        }
        tenants.setBackfill(tenantId, "done", null);
        // A tenant with no documents (yet) has nothing to index, so nothing would ever create its read alias, and its
        // searches would hit a missing alias and fall back as if the engine were broken. Create the index and the alias
        // now, before the tenant can be marked ready. If the engine is down the tenant simply stays not ready (searches use
        // the title search) and the next backfill pass, which re-checks every not-ready tenant, finishes the job.
        try {
            admin.ensureIndex();
            admin.ensureTenantAlias(tenantId);
        } catch (SearchEngineException e) {
            log.warn("Search backfill of tenant {} could not prepare the index yet: {}", tenantId, e.getMessage());
            tenants.setBackfill(tenantId, "running", null);
            return queued;
        }
        tenants.markReadyIfDrained(tenantId);
        log.info("Search backfill queued {} document(s) for tenant {}", queued, tenantId);
        return queued;
    }
}
