package com.oak.dms.search.index;

import com.oak.dms.search.config.SearchProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Starts (or resumes) the backfill of every tenant that has never been indexed, shortly after startup and then every few
 * minutes. This is what makes search work on an existing installation without anyone running anything: tenants whose
 * documents predate the index are filled in the background while the title search serves them.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "dms.search.indexer", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SearchBackfillScheduler {

    private final SearchProperties props;
    private final SearchTenantStateRepository tenants;
    private final SearchBackfillService backfill;

    public SearchBackfillScheduler(SearchProperties props, SearchTenantStateRepository tenants, SearchBackfillService backfill) {
        this.props = props;
        this.tenants = tenants;
        this.backfill = backfill;
    }

    @Scheduled(initialDelayString = "PT30S", fixedDelayString = "PT5M")
    void tick() {
        if (!props.isEnabled()) {
            return;
        }
        try {
            for (UUID tenantId : tenants.tenantsNeedingBackfill()) {
                backfill.run(tenantId, "backfill");
            }
        } catch (RuntimeException e) {
            log.warn("Search backfill pass failed: {}", e.getClass().getSimpleName());
        }
    }
}
