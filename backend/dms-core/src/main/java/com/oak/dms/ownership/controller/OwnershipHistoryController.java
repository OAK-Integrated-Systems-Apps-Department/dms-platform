package com.oak.dms.ownership.controller;

import com.oak.dms.ownership.dto.OwnershipHistoryPage;
import com.oak.dms.ownership.service.OwnershipHistoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Ownership history of a document and the filtered CSV export. */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "Document ownership")
public class OwnershipHistoryController {

    private static final String P = "T(com.oak.dms.security.rbac.catalog.PermissionCodes).";
    private static final String VIEW = "@authz.hasPermission(" + P + "DOT_HISTORY_VIEW)";
    private static final String EXPORT = "@authz.hasPermission(" + P + "DOT_HISTORY_EXPORT)";

    private final OwnershipHistoryService service;

    @GetMapping("/documents/{documentId}/ownership-history")
    @PreAuthorize(VIEW)
    @Operation(summary = "Who owned this document and when it changed hands",
            description = "Newest first. 404 for a document the caller can't open. Names are resolved even for deactivated or deleted users.")
    public OwnershipHistoryPage history(@PathVariable("documentId") UUID documentId,
                                        @RequestParam(value = "page", defaultValue = "0") int page,
                                        @RequestParam(value = "size", defaultValue = "50") int size) {
        return service.forDocument(documentId, page, size);
    }

    @GetMapping("/ownership/history/export")
    @PreAuthorize(EXPORT)
    @Operation(summary = "Ownership history as CSV",
            description = "Filter by time range, document, person (previous or new owner), batch and reason. Non-admins get only "
                    + "documents they can open. 422 above 100,000 rows.")
    public void export(@RequestParam(value = "from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
                       @RequestParam(value = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
                       @RequestParam(value = "documentId", required = false) UUID documentId,
                       @RequestParam(value = "userId", required = false) UUID userId,
                       @RequestParam(value = "batchId", required = false) UUID batchId,
                       @RequestParam(value = "reason", required = false) String reason,
                       HttpServletResponse response) throws IOException {
        service.export(new OwnershipHistoryService.ExportFilter(from, to, documentId, userId, batchId, reason), response);
    }
}
