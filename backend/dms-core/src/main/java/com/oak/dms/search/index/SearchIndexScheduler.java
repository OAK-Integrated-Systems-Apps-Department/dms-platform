package com.oak.dms.search.index;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Polls the index queue. Per-instance switch {@code dms.search.indexer.enabled} (off in tests, which drive
 * {@link SearchIndexer#runOnce()} directly). {@code fixedDelay} means ticks never overlap inside one instance; across
 * instances the SKIP LOCKED claim keeps them from sharing a row.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "dms.search.indexer", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SearchIndexScheduler {

    private final SearchIndexer indexer;

    public SearchIndexScheduler(SearchIndexer indexer) {
        this.indexer = indexer;
    }

    @Scheduled(fixedDelayString = "${dms.search.indexer.poll-interval:PT1S}", initialDelayString = "PT10S")
    void tick() {
        try {
            // Drain while there is work, so a burst of changes is not paced one batch per poll.
            for (int i = 0; i < 20 && indexer.runOnce() > 0; i++) {
                // keep going
            }
        } catch (RuntimeException e) {
            log.warn("Search indexer tick failed: {}", e.getClass().getSimpleName());
        }
    }
}
