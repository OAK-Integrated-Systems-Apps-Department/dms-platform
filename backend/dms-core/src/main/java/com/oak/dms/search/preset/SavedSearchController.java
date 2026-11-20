package com.oak.dms.search.preset;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * The caller's own saved search presets. Listing, reading and running need only {@code dsr.search-documents} (anyone who
 * can search can use what they saved); creating, updating and deleting need the manage codes. The {@code share} code is
 * declared but deliberately never checked: presets are private.
 */
@RestController
@RequestMapping("/api/v1/search/presets")
@RequiredArgsConstructor
@Tag(name = "Saved search presets")
public class SavedSearchController {

    private static final String PREFIX = "@authz.hasPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).";
    private static final String SEARCH = PREFIX + "DSR_SEARCH_DOCUMENTS)";
    private static final String CREATE = SEARCH + " and " + PREFIX + "DSR_MANAGE_PRESETS_CREATE)";
    private static final String UPDATE = SEARCH + " and " + PREFIX + "DSR_MANAGE_PRESETS_UPDATE)";
    private static final String DELETE = SEARCH + " and " + PREFIX + "DSR_MANAGE_PRESETS_DELETE)";

    // toString() deliberately hides the content: Spring logs request and response bodies at DEBUG, and a name or
    // keyword must never reach a log line.
    public record CreateBody(String name, Map<String, Object> criteria, Boolean overwrite) {
        @Override
        public String toString() {
            return "CreateBody[...]";
        }
    }

    public record UpdateBody(String name, Map<String, Object> criteria) {
        @Override
        public String toString() {
            return "UpdateBody[...]";
        }
    }

    public record RunBody(Integer size) {
    }

    private final SavedSearchService service;

    @GetMapping
    @PreAuthorize(SEARCH)
    @Operation(summary = "My saved searches")
    public SavedSearchService.Listing list() {
        return service.list();
    }

    @GetMapping("/{id}")
    @PreAuthorize(SEARCH)
    @Operation(summary = "One of my saved searches")
    public SavedSearchService.Preset get(@PathVariable UUID id) {
        return service.get(id);
    }

    @PostMapping
    @PreAuthorize(CREATE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Save a search (409 PRESET_NAME_EXISTS unless overwrite is true; 409 PRESET_LIMIT_REACHED at the limit)")
    public SavedSearchService.Preset create(@RequestBody CreateBody body) {
        return service.create(body.name(), body.criteria(), Boolean.TRUE.equals(body.overwrite()));
    }

    @PutMapping("/{id}")
    @PreAuthorize(UPDATE)
    @Operation(summary = "Rename a saved search and/or replace its criteria")
    public SavedSearchService.Preset update(@PathVariable UUID id, @RequestBody UpdateBody body) {
        return service.update(id, body.name(), body.criteria());
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(DELETE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a saved search")
    public void delete(@PathVariable UUID id) {
        service.delete(id);
    }

    @PostMapping("/{id}/run")
    @PreAuthorize(SEARCH)
    @Operation(summary = "Run a saved search now: outdated filters are dropped and named in warnings")
    public SavedSearchService.Run run(@PathVariable UUID id, @RequestBody(required = false) RunBody body) {
        return service.run(id, body == null ? null : body.size());
    }
}
