package com.oak.dms.search.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.oak.dms.document.DocumentService;
import com.oak.dms.document.dto.DocumentListItem;
import com.oak.dms.search.config.SearchProperties;
import com.oak.dms.search.engine.SearchEngine;
import com.oak.dms.search.engine.SearchEngineException;
import com.oak.dms.search.index.SearchTenantStateRepository;
import com.oak.dms.search.observability.SearchMetrics;
import com.oak.dms.search.query.SearchResponse.Highlights;
import com.oak.dms.search.query.SearchResponse.Item;
import com.oak.dms.search.query.SearchResponse.Notice;
import com.oak.dms.search.query.SearchResponse.Total;
import com.oak.dms.security.auth.TenantContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Search, with two independent layers of security (D3).
 *
 * <p><b>Layer 1</b> is the engine query: tenant, not deleted/purged/template, and only the lifecycle states the caller may
 * see and asked for. <b>Layer 2</b> verifies every candidate page in PostgreSQL with the very rules browsing uses
 * ({@link DocumentService#visibleItems}: folder access, document access, workflow-readable, deleted, templates) plus a
 * fresh lifecycle check, so a stale or wrong index can never reveal a document: it can only fail to show one.
 *
 * <p>Because layer 2 removes candidates, the loop over-fetches and continues with {@code search_after} until the page is
 * full or a cap is reached (rounds, candidates, time). Hidden documents never leave this class: not as items, highlights,
 * counts, suggestions, errors or timing differences (highlights are computed in a second request restricted to verified
 * ids; the engine's own total is never read; notices are generic).
 */
@Slf4j
@Service
public class SearchService {

    private static final int MIN_BATCH = 20;

    private final SearchProperties props;
    private final SearchEngine engine;
    private final com.oak.dms.search.index.IndexAdmin admin;
    private final SearchTenantStateRepository tenants;
    private final SearchQueryBuilder builder;
    private final SearchFilterValidator validator;
    private final SearchCursor cursors;
    private final DocumentService documents;
    private final SearchFallbackService fallback;
    private final SearchMetrics metrics;
    private final SearchResultMarks marks;

    public SearchService(SearchProperties props, SearchEngine engine, com.oak.dms.search.index.IndexAdmin admin,
                         SearchTenantStateRepository tenants, SearchQueryBuilder builder, SearchFilterValidator validator,
                         SearchCursor cursors, DocumentService documents, SearchFallbackService fallback, SearchMetrics metrics,
                         SearchResultMarks marks) {
        this.props = props;
        this.engine = engine;
        this.admin = admin;
        this.tenants = tenants;
        this.builder = builder;
        this.validator = validator;
        this.cursors = cursors;
        this.documents = documents;
        this.fallback = fallback;
        this.metrics = metrics;
        this.marks = marks;
    }

    /** @param canViewArchive whether the caller holds {@code crm.view-archive} (needed to include inactive/archived) */
    public SearchResponse search(SearchRequest raw, boolean canViewArchive) {
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();
        if (raw.wantsArchived() && !canViewArchive) {
            // Never silently widen or narrow: asking for what you may not see is refused for that filter.
            throw new AccessDeniedException("Viewing inactive or archived documents needs the archive permission.");
        }
        List<String> states = List.copyOf(new java.util.TreeSet<>(raw.statuses()));
        SearchFilterValidator.Validated v = validator.validate(tenantId, raw);
        SearchRequest r = v.request();
        String fingerprint = SearchCursor.fingerprint(tenantId, userId, r);
        SearchCursor.State cursor = raw.cursor() == null || raw.cursor().isBlank() ? null : cursors.decode(raw.cursor(), fingerprint);

        String reason = engineUnavailableReason(tenantId, cursor);
        if (reason == null) {
            Instant start = Instant.now();
            try {
                SearchResponse response = viaIndex(tenantId, userId, r, states, v, cursor, fingerprint);
                boolean capped = response.notices().stream().anyMatch(n -> "REFINE".equals(n.code()));
                metrics.queryTimer("search", capped ? "capped" : "ok").record(Duration.between(start, Instant.now()));
                return response;
            } catch (SearchEngineException e) {
                log.warn("Search engine could not answer ({}); using the title search", e.getMessage());
                metrics.queryTimer("search", "error").record(Duration.between(start, Instant.now()));
                reason = "engine";
            }
        }
        metrics.fallback(reason);
        Instant start = Instant.now();
        SearchCursor.State fallbackCursor = cursor != null && "basic".equals(cursor.mode()) ? cursor : null;
        SearchResponse response = fallback.search(tenantId, userId, r, states, fallbackCursor, v.notices());
        metrics.queryTimer("basic").record(Duration.between(start, Instant.now()));
        return response;
    }

    /** Why the engine should not be asked (null = ask it). Checked per request from PostgreSQL: cheap and always current. */
    private String engineUnavailableReason(UUID tenantId, SearchCursor.State cursor) {
        if (!props.isEnabled()) {
            return "disabled";
        }
        if (cursor != null && "basic".equals(cursor.mode())) {
            return "continuing"; // a fallback page sequence continues in fallback so its pages stay consistent
        }
        var state = tenants.find(tenantId);
        if (state.isEmpty() || !state.get().enabled()) {
            return "disabled";
        }
        return state.get().indexReady() ? null : "not_ready";
    }

    // ---- the index path ------------------------------------------------------------------------------------------

    private record Verified(DocumentListItem item, JsonNode sort) {
    }

    private SearchResponse viaIndex(UUID tenantId, UUID userId, SearchRequest r, List<String> states,
                                    SearchFilterValidator.Validated v, SearchCursor.State cursor, String fingerprint) {
        SearchProperties.Query cfg = props.getQuery();
        String alias = admin.readAlias(tenantId);
        ObjectNode query = builder.query(tenantId, r, states, v.fields());
        long verifiedBefore = cursor == null ? 0 : cursor.verifiedBefore();
        JsonNode after = cursor == null ? null : cursor.searchAfter();

        List<Verified> verified = new ArrayList<>();
        JsonNode consumedAfter = null; // sort values of the hit that completed the page
        boolean exhausted = false;
        int considered = 0;
        int rounds = 0;
        Instant deadline = Instant.now().plus(cfg.getTimeBudget());

        while (verified.size() < r.size() && rounds < cfg.getMaxRounds() && considered < cfg.getMaxCandidates()
                && Instant.now().isBefore(deadline)) {
            // The window doubles each round: the first is cheap for the common case, and a user who can see little (most
            // candidates dropped by layer 2) reaches their results in a few rounds instead of crawling through 20 at a time.
            int base = Math.max(MIN_BATCH, (r.size() - verified.size()) * cfg.getOverfetchFactor());
            int batch = (int) Math.min(cfg.getMaxBatch(), (long) base << Math.min(rounds, 6));
            JsonNode hits = engine.search(alias, tenantId.toString(), builder.candidates(query, r, batch, after))
                    .path("hits").path("hits");
            rounds++;
            if (hits.isEmpty()) {
                exhausted = true;
                break;
            }
            List<UUID> ids = new ArrayList<>();
            hits.forEach(h -> ids.add(UUID.fromString(h.path("_id").asText())));
            // Layer 2: the only source of truth for "may this user see it, and is it live".
            Map<UUID, DocumentListItem> visible = new HashMap<>();
            for (DocumentListItem d : documents.visibleItems(ids)) {
                if (states.contains(d.lifecycleState())) {
                    visible.put(d.documentId(), d);
                }
            }
            int dropped = 0;
            for (JsonNode hit : hits) {
                considered++;
                DocumentListItem item = visible.get(UUID.fromString(hit.path("_id").asText()));
                if (item == null) {
                    dropped++;
                    continue;
                }
                verified.add(new Verified(item, hit.path("sort")));
                if (verified.size() == r.size() && consumedAfter == null) {
                    consumedAfter = hit.path("sort");
                }
            }
            metrics.verifyDropped(dropped);
            after = hits.get(hits.size() - 1).path("sort");
            if (hits.size() < batch) {
                exhausted = true;
                break;
            }
        }

        metrics.verifyRounds(rounds);
        List<Verified> page = verified.subList(0, Math.min(r.size(), verified.size()));
        boolean pageFilled = verified.size() >= r.size();
        boolean more = pageFilled ? (verified.size() > r.size() || !exhausted) : !exhausted;
        JsonNode nextAfter = pageFilled ? consumedAfter : after;
        long shown = verifiedBefore + page.size();
        String nextCursor = more
                ? cursors.encode(new SearchCursor.State(nextAfter, shown, 0, "search"), fingerprint) : null;
        // Exact only when every candidate was looked at; otherwise "this many or more". Never the engine's own total.
        Total total = new Total(verifiedBefore + verified.size(), exhausted);

        List<Notice> notices = new ArrayList<>(v.notices());
        if (!pageFilled && !exhausted) {
            notices.add(Notice.of("REFINE", "There are a lot of possible matches. Add a keyword or a filter to narrow them down."));
        }

        List<Item> items = page.isEmpty() ? List.of() : assemble(alias, tenantId, query, r, page);
        String didYouMean = null;
        if (items.isEmpty()) {
            notices.add(Notice.of("NO_RESULTS", "No results found."));
            if (cursor == null && r.hasKeywords()) {
                didYouMean = suggest(alias, tenantId, r, states, v);
            }
        }
        return new SearchResponse("search", items, total, nextCursor, notices, didYouMean);
    }

    /** Second request: highlights and a few index-only fields, for verified ids only. */
    private List<Item> assemble(String alias, UUID tenantId, ObjectNode query, SearchRequest r, List<Verified> page) {
        List<String> ids = page.stream().map(p -> p.item().documentId().toString()).toList();
        Map<String, JsonNode> byId = new LinkedHashMap<>();
        try {
            JsonNode hits = engine.search(alias, tenantId.toString(), builder.highlights(query, ids, r)).path("hits").path("hits");
            hits.forEach(h -> byId.put(h.path("_id").asText(), h));
        } catch (SearchEngineException e) {
            // Highlights are decoration: the verified rows are still returned.
            log.debug("Could not fetch highlights ({})", e.getMessage());
        }
        // Marks and thumbnail links are decided for the verified page only (never for candidates, never indexed).
        SearchResultMarks.Marks m = marks.forPage(ids.stream().map(UUID::fromString).toList());
        List<Item> items = new ArrayList<>();
        for (Verified p : page) {
            DocumentListItem d = p.item();
            JsonNode hit = byId.get(d.documentId().toString());
            JsonNode src = hit == null ? null : hit.path("_source");
            Highlights highlights = Highlights.NONE;
            if (hit != null && hit.has("highlight")) {
                JsonNode h = hit.get("highlight");
                List<String> body = new ArrayList<>();
                h.path("body").forEach(f -> body.add(f.asText()));
                highlights = new Highlights(first(h.path("title")), first(h.path("file_name")), body);
            }
            items.add(new Item(d.documentId(), d.title(), d.fileName(), d.documentTypeId(), d.documentTypeName(), d.folderId(),
                    d.ownerUserId(), d.ownerDepartmentId(), d.createdAt(),
                    src != null && src.hasNonNull("updated_at") ? OffsetDateTime.parse(src.get("updated_at").asText()) : null,
                    d.lifecycleState(), src != null && src.path("legal_hold").asBoolean(false), d.mimeType(),
                    src != null ? src.path("body_status").asText(null) : null, highlights, d.versionNumber(), d.sizeBytes(),
                    m.thumbnailUrl(d.documentId()), m.pinned(d.documentId()), m.bookmarked(d.documentId())));
        }
        return items;
    }

    private static String first(JsonNode array) {
        return array.isArray() && !array.isEmpty() ? array.get(0).asText() : null;
    }

    /**
     * Only used when a search found nothing. The fuzzy probe's candidates are verified exactly like search results and the
     * vocabulary is built from the verified titles and file names alone: hidden documents contribute no word.
     */
    private String suggest(String alias, UUID tenantId, SearchRequest r, List<String> states, SearchFilterValidator.Validated v) {
        try {
            ObjectNode body = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
            body.set("query", builder.fuzzy(tenantId, r, states, v.fields()));
            body.put("size", 30);
            body.put("_source", false);
            body.put("track_total_hits", false);
            JsonNode hits = engine.search(alias, tenantId.toString(), body).path("hits").path("hits");
            List<UUID> ids = new ArrayList<>();
            hits.forEach(h -> ids.add(UUID.fromString(h.path("_id").asText())));
            List<String> vocabulary = new ArrayList<>();
            for (DocumentListItem d : documents.visibleItems(ids)) {
                if (states.contains(d.lifecycleState())) {
                    vocabulary.add(d.title());
                    vocabulary.add(d.fileName());
                }
            }
            return DidYouMean.suggest(r.q(), vocabulary);
        } catch (SearchEngineException e) {
            return null;
        }
    }

    // ---- typeahead -------------------------------------------------------------------------------------------------

    /** Prefix matches on title and file name, verified like any search; at most eight. Falls back to an empty list. */
    public SearchResponse.Suggestions suggestions(String q) {
        UUID tenantId = TenantContext.getTenantId();
        if (q == null || q.strip().length() < 2 || engineUnavailableReason(tenantId, null) != null) {
            return new SearchResponse.Suggestions(List.of());
        }
        SearchRequest r = new SearchRequest(q, null, null, null, null, null, null, null, java.util.Set.of("active"), null, false,
                List.of(), "relevance", false, 8, null);
        try {
            ObjectNode query = builder.query(tenantId, r, List.of("active"), Map.of());
            JsonNode hits = engine.search(admin.readAlias(tenantId), tenantId.toString(),
                    builder.candidates(query, r, 50, null)).path("hits").path("hits");
            List<UUID> ids = new ArrayList<>();
            hits.forEach(h -> ids.add(UUID.fromString(h.path("_id").asText())));
            Map<UUID, DocumentListItem> visible = new HashMap<>();
            documents.visibleItems(ids).stream().filter(d -> "active".equals(d.lifecycleState()))
                    .forEach(d -> visible.put(d.documentId(), d));
            List<SearchResponse.Suggestion> out = new ArrayList<>();
            for (UUID id : ids) {
                DocumentListItem d = visible.get(id);
                if (d != null && out.size() < 8) {
                    out.add(new SearchResponse.Suggestion(d.documentId(), d.title()));
                }
            }
            return new SearchResponse.Suggestions(out);
        } catch (SearchEngineException e) {
            return new SearchResponse.Suggestions(List.of());
        }
    }
}
