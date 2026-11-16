package com.oak.dms.search.admin;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import com.oak.dms.search.preset.PresetLimitService;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Search administration for the caller's own organisation. Tenant administrators only ({@code @authz.isTenantAdmin()},
 * the existing check), deliberately not a permission code: rebuilding the index is an operational action, not a
 * delegable business capability.
 */
@RestController
@RequestMapping("/api/v1/admin/search")
@RequiredArgsConstructor
@Tag(name = "Search administration")
public class SearchAdminController {

    private static final String ADMIN = "@authz.isTenantAdmin()";

    private final SearchAdminService service;

    @PostMapping("/reindex")
    @PreAuthorize(ADMIN)
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Rebuild this organisation's search index (runs in the background; search keeps working)")
    public Map<String, Long> reindex() {
        return Map.of("generation", service.reindex());
    }

    @GetMapping("/status")
    @PreAuthorize(ADMIN)
    @Operation(summary = "Search engine and index status for this organisation, including queue lag and the last reconcile gap")
    public SearchAdminService.Status status() {
        return service.status();
    }

    @PostMapping("/reconcile")
    @PreAuthorize(ADMIN)
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Check this organisation's index against the database now (409 while one runs, 429 inside the interval)")
    public void reconcile() {
        service.reconcileNow();
    }

    public record LimitBody(int maxPerUser) {
    }

    @GetMapping("/presets/limit")
    @PreAuthorize(ADMIN)
    @Operation(summary = "How many saved searches each person may keep in this organisation")
    public PresetLimitService.Limit presetLimit() {
        return service.presetLimit();
    }

    @PutMapping("/presets/limit")
    @PreAuthorize(ADMIN)
    @Operation(summary = "Change the saved-search limit per person (audited)")
    public PresetLimitService.Limit setPresetLimit(@RequestBody LimitBody body) {
        return service.setPresetLimit(body.maxPerUser());
    }
}
