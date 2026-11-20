package com.oak.dms.search.index;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs the daily reconcile ({@code dms.search.reconcile.cron}, per-instance switch {@code dms.search.reconcile.enabled}). */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "dms.search.reconcile", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SearchReconcileScheduler {

    private final SearchReconciler reconciler;

    public SearchReconcileScheduler(SearchReconciler reconciler) {
        this.reconciler = reconciler;
    }

    @Scheduled(cron = "${dms.search.reconcile.cron:0 30 3 * * *}")
    void run() {
        try {
            reconciler.reconcileAll();
        } catch (RuntimeException e) {
            log.warn("Search reconcile pass failed: {}", e.getClass().getSimpleName());
        }
    }
}
