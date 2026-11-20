package com.oak.dms.search.index;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.oak.dms.search.config.SearchProperties;
import com.oak.dms.search.engine.SearchEngine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The safety net behind the triggers: a daily check per tenant that the index holds exactly the documents that belong in
 * it. Walks the ids of eligible documents in PostgreSQL and of the tenant's index entries, queues what is missing (the
 * indexer then adds it) and what should not be there (the indexer then removes it); documents that already have a job
 * waiting are not counted as gaps. The gap found is
 * stored in {@code search_tenant_state} and shown by {@code GET /api/v1/admin/search/status}. Ids and counts only: no
 * document text is read or logged.
 */
@Slf4j
@Component
public class SearchReconciler {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int PAGE = 1000;
    private static final long LOCK_KEY = 0x53524348524543L; // "SRCHREC"

    public record Gap(long missing, long orphans) {
    }

    private final SearchProperties props;
    private final SearchEngine engine;
    private final IndexAdmin admin;
    private final SearchSourceRepository source;
    private final SearchJobRepository jobs;
    private final SearchTenantStateRepository tenants;
    private final JdbcTemplate jdbc;

    public SearchReconciler(SearchProperties props, SearchEngine engine, IndexAdmin admin, SearchSourceRepository source,
                            SearchJobRepository jobs, SearchTenantStateRepository tenants, JdbcTemplate jdbc) {
        this.props = props;
        this.engine = engine;
        this.admin = admin;
        this.source = source;
        this.jobs = jobs;
        this.tenants = tenants;
        this.jdbc = jdbc;
    }

    /** Every tenant, or nothing at all when another server is already doing it. */
    public void reconcileAll() {
        if (!props.isEnabled() || engine.health() == SearchEngine.Health.DOWN) {
            return;
        }
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            if (!lock(connection, "select pg_try_advisory_lock(?)")) {
                return null;
            }
            try {
                for (UUID tenantId : tenants.activeTenantIds()) {
                    try {
                        reconcile(tenantId);
                    } catch (RuntimeException e) {
                        log.warn("Search reconcile of tenant {} failed: {}", tenantId, e.getClass().getSimpleName());
                    }
                }
            } finally {
                lock(connection, "select pg_advisory_unlock(?)");
            }
            return null;
        });
    }

    /** Reconciles one tenant; returns the gap found (and queued for repair), or null when the tenant is not eligible yet. */
    public Gap reconcile(UUID tenantId) {
        SearchTenantStateRepository.State state = tenants.find(tenantId).orElse(null);
        if (state == null || !state.enabled() || !state.indexReady()) {
            return null;
        }
        if (admin.isReady() && !admin.tenantAliasExists(tenantId)) {
            admin.ensureTenantAlias(tenantId);
        }
        String alias = admin.readAlias(tenantId);
        engine.refresh(admin.writeAlias()); // count what has really been written, not what the last refresh happened to show
        // Always compare the ids, never just the counts: one missing document and one orphan cancel out in a count. The
        // walk is two keyset passes of 1,000 ids per page (about 1,000 cheap queries for 500,000 documents, once a day).
        Gap gap = new Gap(queueMissing(tenantId, alias), queueOrphans(tenantId, alias));
        tenants.recordReconcile(tenantId, "{\"missing\":" + gap.missing() + ",\"orphans\":" + gap.orphans() + "}");
        if (gap.missing() > 0 || gap.orphans() > 0) {
            log.warn("Search reconcile of tenant {}: {} missing, {} orphan document(s) queued for repair", tenantId, gap.missing(), gap.orphans());
        }
        return gap;
    }

    /** Documents that belong in the index but are not in it: queued so the indexer adds them. */
    private long queueMissing(UUID tenantId, String alias) {
        long missing = 0;
        UUID after = null;
        while (true) {
            List<UUID> page = source.eligibleIds(tenantId, after, PAGE);
            if (page.isEmpty()) {
                return missing;
            }
            Set<String> present = idsPresent(alias, tenantId, page);
            Set<UUID> queued = jobs.pendingDocumentIds(tenantId, page);
            for (UUID id : page) {
                // a document with a job already waiting is about to be written by the indexer: not a gap
                if (!present.contains(id.toString()) && !queued.contains(id)) {
                    jobs.enqueueDocument(tenantId, id, "reconcile");
                    missing++;
                }
            }
            after = page.get(page.size() - 1);
        }
    }

    private Set<String> idsPresent(String alias, UUID tenantId, List<UUID> ids) {
        ObjectNode body = JSON.createObjectNode();
        ArrayNode values = body.putObject("query").putObject("ids").putArray("values");
        ids.forEach(id -> values.add(id.toString()));
        body.put("size", ids.size());
        body.put("_source", false);
        JsonNode hits = engine.search(alias, tenantId.toString(), body).path("hits").path("hits");
        Set<String> out = new HashSet<>();
        hits.forEach(h -> out.add(h.path("_id").asText()));
        return out;
    }

    /** Index entries that no longer belong (deleted, purged, template): queued so the indexer removes them. */
    private long queueOrphans(UUID tenantId, String alias) {
        long orphans = 0;
        List<Object> searchAfter = null;
        while (true) {
            ObjectNode body = JSON.createObjectNode();
            body.put("size", PAGE);
            body.put("_source", false);
            body.putObject("query").putObject("match_all");
            body.putArray("sort").addObject().put("document_id", "asc");
            if (searchAfter != null) {
                ArrayNode sa = body.putArray("search_after");
                for (Object v : searchAfter) {
                    sa.add(String.valueOf(v));
                }
            }
            JsonNode hits = engine.search(alias, tenantId.toString(), body).path("hits").path("hits");
            if (hits.isEmpty()) {
                return orphans;
            }
            List<UUID> ids = new ArrayList<>();
            hits.forEach(h -> ids.add(UUID.fromString(h.path("_id").asText())));
            Set<UUID> keep = source.eligibleAmong(tenantId, ids);
            Set<UUID> queued = jobs.pendingDocumentIds(tenantId, ids);
            for (UUID id : ids) {
                if (!keep.contains(id) && !queued.contains(id)) {
                    jobs.enqueueDocument(tenantId, id, "reconcile");
                    orphans++;
                }
            }
            List<Object> next = new ArrayList<>();
            hits.get(hits.size() - 1).path("sort").forEach(v -> next.add(v.asText()));
            searchAfter = next;
        }
    }

    private static boolean lock(java.sql.Connection connection, String sql) throws java.sql.SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, LOCK_KEY);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getBoolean(1);
            }
        }
    }
}
