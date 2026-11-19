package com.oak.dms.search.api;

import com.oak.dms.search.query.SearchFilterOptions;
import com.oak.dms.search.query.SearchRequest;
import com.oak.dms.search.query.SearchResponse;
import com.oak.dms.search.query.SearchService;
import com.oak.dms.security.auth.AuthorizationService;
import com.oak.dms.security.rbac.catalog.PermissionCodes;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Document search. Needs {@code dsr.search-documents}; including inactive or archived documents additionally needs
 * {@code crm.view-archive} (refused with 403 for that request, never silently widened or narrowed). Whatever the filters,
 * the response only ever holds documents the caller may open.
 */
@RestController
@RequestMapping("/api/v1/search")
@RequiredArgsConstructor
@Tag(name = "Search")
public class SearchController {

    private static final String SEARCH =
            "@authz.hasPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).DSR_SEARCH_DOCUMENTS)";

    private final SearchService service;
    private final SearchFilterOptions filterOptions;
    private final AuthorizationService authz;

    @GetMapping
    @PreAuthorize(SEARCH)
    @Operation(summary = "Search documents by keywords, phrases, metadata and text inside them; filter, sort and page with a cursor")
    public SearchResponse search(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) UUID type,
            @RequestParam(required = false) UUID department,
            @RequestParam(required = false) UUID owner,
            @RequestParam(required = false) String createdFrom,
            @RequestParam(required = false) String createdTo,
            @RequestParam(required = false) String modifiedFrom,
            @RequestParam(required = false) String modifiedTo,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) UUID folderId,
            @RequestParam(defaultValue = "false") boolean includeSubfolders,
            @RequestParam(required = false) List<String> meta,
            @RequestParam(defaultValue = "relevance") String sort,
            @RequestParam(defaultValue = "desc") String dir,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String cursor) {
        int pageSize = size == null ? 20 : Math.min(Math.max(size, 1), 50);
        SearchRequest request = new SearchRequest(
                q == null || q.isBlank() ? null : q.strip(), type, department, owner,
                instant(createdFrom, false), instant(createdTo, true), instant(modifiedFrom, false), instant(modifiedTo, true),
                statuses(status), folderId, includeSubfolders, metaFilters(meta), sortKey(sort), !"desc".equalsIgnoreCase(dir),
                pageSize, cursor);
        return service.search(request, authz.hasPermission(PermissionCodes.CRM_VIEW_ARCHIVE));
    }

    @GetMapping("/filters")
    @PreAuthorize(SEARCH)
    @Operation(summary = "The pick-lists for the search filters: types, departments, owners and searchable metadata fields (names only)")
    public SearchFilterOptions.Options filters() {
        return filterOptions.load();
    }

    @GetMapping("/suggest")
    @PreAuthorize(SEARCH)
    @Operation(summary = "Typeahead: up to eight active documents whose title or file name starts with the typed text")
    public SearchResponse.Suggestions suggest(@RequestParam String q) {
        return service.suggestions(q);
    }

    // ---- parsing: every malformed value is the caller's mistake (400), never a 500 ------------------------------

    private static Set<String> statuses(String raw) {
        if (raw == null || raw.isBlank()) {
            return Set.of("active");
        }
        Set<String> out = new LinkedHashSet<>();
        for (String s : raw.split(",")) {
            String v = s.strip().toLowerCase();
            if ("all".equals(v)) {
                out.addAll(SearchRequest.STATES);
            } else if (SearchRequest.STATES.contains(v)) {
                out.add(v);
            } else {
                throw bad("Unknown status '" + s.strip() + "'. Use active, inactive, archived or all.");
            }
        }
        return out;
    }

    private static String sortKey(String s) {
        return switch (s) {
            case "relevance", "title", "created", "modified" -> s;
            default -> throw bad("Unknown sort '" + s + "'. Use relevance, title, created or modified.");
        };
    }

    /** {@code fieldId:op:value}; the value may itself contain colons. */
    private static List<SearchRequest.MetaFilter> metaFilters(List<String> raw) {
        List<SearchRequest.MetaFilter> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        for (String m : raw) {
            String[] parts = m.split(":", 3);
            if (parts.length != 3) {
                throw bad("A metadata filter looks like fieldId:op:value.");
            }
            try {
                out.add(new SearchRequest.MetaFilter(UUID.fromString(parts[0]), parts[1], parts[2]));
            } catch (IllegalArgumentException e) {
                throw bad("A metadata filter starts with a field id.");
            }
        }
        return out;
    }

    /** A date (whole day) or a date-time; an end bound given as a date covers that whole day. */
    private static Instant instant(String raw, boolean end) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(raw.strip()).toInstant();
        } catch (RuntimeException e) {
            try {
                LocalDate d = LocalDate.parse(raw.strip());
                return end ? d.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC).minusMillis(1)
                        : d.atStartOfDay().toInstant(ZoneOffset.UTC);
            } catch (RuntimeException e2) {
                throw bad("'" + raw + "' is not a date. Use a form like 2026-03-31.");
            }
        }
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
