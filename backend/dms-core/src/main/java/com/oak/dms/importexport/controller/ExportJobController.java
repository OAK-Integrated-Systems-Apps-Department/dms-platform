package com.oak.dms.importexport.controller;

import com.oak.dms.common.dto.PageResponse;
import com.oak.dms.importexport.dto.ExportJobResponse;
import com.oak.dms.importexport.service.ExportJobService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only "My exports" (Module 2.1). Any signed-in user may see the export
 * jobs THEY requested - scoping comes from the session, see
 * ExportJobService#listMine. Starting an export (and its FIE-FR-012
 * permission), downloading and purging arrive in Phase 4.
 */
@RestController
@RequestMapping("/api/v1/export-jobs")
@RequiredArgsConstructor
@Tag(name = "Export jobs", description = "The caller's own bulk export jobs")
public class ExportJobController {

    private final ExportJobService exportJobService;

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "List the caller's own export jobs",
            description = "Only jobs the caller requested, in the caller's tenant. status is optional "
                    + "(pending, processing, completed, failed, expired); anything else is a 400. Newest first by default.")
    public PageResponse<ExportJobResponse> listMine(
            @RequestParam(required = false) String status,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return PageResponse.from(exportJobService.listMine(status, pageable));
    }
}
