package com.oak.dms.search.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oak.dms.audit.service.AuditService;
import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.catalog.AuditObjectTypes;
import com.oak.dms.audit.enums.AuditOutcome;
import com.oak.dms.search.config.SearchProperties;
import com.oak.dms.search.api.SearchApiException;
import com.oak.dms.search.engine.SearchEngine;
import com.oak.dms.search.index.SearchReconciler;
import com.oak.dms.search.preset.PresetLimitService;
import org.springframework.http.HttpStatus;
import com.oak.dms.search.index.SearchJobRepository;
import com.oak.dms.search.index.SearchRebuildService;
import com.oak.dms.search.index.SearchTenantStateRepository;
import com.oak.dms.security.auth.TenantContext;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Tenant-admin view of, and control over, the organisation's search index. Everything here is about the caller's own
 * tenant; nothing reveals another tenant or any document.
 */
@Service
public class SearchAdminService {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** The admin status page's payload. */
    public record Status(String engine, boolean searchEnabled, boolean indexReady, String backfillState, long pendingJobs,
                         long failedJobs, long lagSeconds, long generation, OffsetDateTime lastReconciledAt, Gap gap,
                         boolean reindexRecommended, boolean reindexRunning, boolean reconcileRunning) {
    }

    public record Gap(long missing, long orphans) {
    }

    private final SearchProperties props;
    private final SearchEngine engine;
    private final SearchJobRepository jobs;
    private final SearchTenantStateRepository tenants;
    private final SearchRebuildService rebuild;
    private final AuditService auditService;
    private final SearchReconciler reconciler;
    private final PresetLimitService presetLimits;
    private final ExecutorService background = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "search-reconcile-now");
        t.setDaemon(true);
        return t;
    });

    public SearchAdminService(SearchProperties props, SearchEngine engine, SearchJobRepository jobs,
                              SearchTenantStateRepository tenants, SearchRebuildService rebuild, AuditService auditService,
                              SearchReconciler reconciler, PresetLimitService presetLimits) {
        this.reconciler = reconciler;
        this.presetLimits = presetLimits;
        this.props = props;
        this.engine = engine;
        this.jobs = jobs;
        this.tenants = tenants;
        this.rebuild = rebuild;
        this.auditService = auditService;
    }

    public Status status() {
        UUID tenantId = TenantContext.getTenantId();
        var state = tenants.find(tenantId);
        String engineState = switch (engine.health()) {
            case GREEN, YELLOW -> "up"; // yellow is normal on a one-node cluster
            case RED -> "red";
            case DOWN -> "down";
        };
        long lag = jobs.oldestDueAt(tenantId)
                .map(t -> Math.max(0, Duration.between(t, OffsetDateTime.now()).toSeconds())).orElse(0L);
        Gap gap = null;
        if (state.isPresent() && state.get().lastGapJson() != null) {
            try {
                JsonNode n = JSON.readTree(state.get().lastGapJson());
                gap = new Gap(n.path("missing").asLong(), n.path("orphans").asLong());
            } catch (Exception ignored) {
                // an unreadable gap is simply not shown
            }
        }
        return new Status(engineState,
                props.isEnabled() && state.map(SearchTenantStateRepository.State::enabled).orElse(true),
                state.map(SearchTenantStateRepository.State::indexReady).orElse(false),
                state.map(SearchTenantStateRepository.State::backfillState).orElse("none"),
                jobs.pendingCount(tenantId), jobs.failedCount(tenantId), lag,
                state.map(SearchTenantStateRepository.State::generation).orElse(1L),
                state.map(SearchTenantStateRepository.State::lastReconciledAt).orElse(null), gap,
                tenants.reindexRecommended(tenantId),
                "running".equals(state.map(SearchTenantStateRepository.State::backfillState).orElse("none")),
                tenants.isReconcileRunning(tenantId));
    }

    /** Starts a rebuild of the caller's tenant; returns the new generation at once. */
    public long reindex() {
        UUID tenantId = TenantContext.getTenantId();
        SearchTenantStateRepository.Claim claim = tenants.claimReindex(tenantId, props.getAdmin().getReindexMinInterval());
        if (claim == SearchTenantStateRepository.Claim.RUNNING) {
            throw new SearchApiException(HttpStatus.CONFLICT, "REINDEX_IN_PROGRESS",
                    "A reindex is already running for your organisation.");
        }
        if (claim == SearchTenantStateRepository.Claim.RATE_LIMITED) {
            throw new SearchApiException(HttpStatus.TOO_MANY_REQUESTS, "REINDEX_RATE_LIMITED",
                    "A reindex was started a moment ago. Try again in a few minutes.",
                    Map.of("retryAfterSeconds", props.getAdmin().getReindexMinInterval().toSeconds()));
        }
        long generation = rebuild.startTenantRebuild(tenantId);
        auditService.event(AuditActionCatalog.SEARCH_REINDEX_REQUESTED)
                .objectType(AuditObjectTypes.SEARCH_INDEX)
                .objectId(tenantId)
                .outcome(AuditOutcome.SUCCESS)
                .newValue(Map.of("generation", generation))
                .log();
        return generation;
    }

    /** Starts a reconcile of the caller's tenant in the background (the nightly one, on demand). One at a time, rate limited. */
    public void reconcileNow() {
        UUID tenantId = TenantContext.getTenantId();
        SearchTenantStateRepository.Claim claim = tenants.claimReconcile(tenantId,
                props.getAdmin().getReconcileMinInterval(), props.getAdmin().getReconcileLease());
        if (claim == SearchTenantStateRepository.Claim.RUNNING) {
            throw new SearchApiException(HttpStatus.CONFLICT, "RECONCILE_IN_PROGRESS",
                    "A check is already running for your organisation.");
        }
        if (claim == SearchTenantStateRepository.Claim.RATE_LIMITED) {
            throw new SearchApiException(HttpStatus.TOO_MANY_REQUESTS, "RECONCILE_RATE_LIMITED",
                    "A check was started a moment ago. Try again in a few minutes.",
                    Map.of("retryAfterSeconds", props.getAdmin().getReconcileMinInterval().toSeconds()));
        }
        auditService.event(AuditActionCatalog.SEARCH_RECONCILE_REQUESTED)
                .objectType(AuditObjectTypes.SEARCH_INDEX)
                .objectId(tenantId)
                .outcome(AuditOutcome.SUCCESS)
                .log();
        background.submit(() -> {
            try {
                reconciler.reconcile(tenantId);
            } catch (RuntimeException e) {
                org.slf4j.LoggerFactory.getLogger(SearchAdminService.class)
                        .warn("On-demand search reconcile failed: {}", e.getClass().getSimpleName());
            } finally {
                tenants.finishReconcile(tenantId);
            }
        });
    }

    public PresetLimitService.Limit presetLimit() {
        return presetLimits.current();
    }

    public PresetLimitService.Limit setPresetLimit(int max) {
        return presetLimits.set(max);
    }
}
