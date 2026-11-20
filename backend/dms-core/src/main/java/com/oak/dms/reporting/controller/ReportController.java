package com.oak.dms.reporting.controller;

import com.oak.dms.reporting.ReportCatalogService;
import com.oak.dms.reporting.ReportFieldValuesService;
import com.oak.dms.reporting.ReportPreviewService;
import com.oak.dms.reporting.dto.CatalogResponse;
import com.oak.dms.reporting.dto.FieldValuesResponse;
import com.oak.dms.reporting.dto.PreviewResponse;
import com.oak.dms.reporting.dto.ReportDefinition;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Custom report generation (module 5.3): the catalogue and the live preview. Permission codes are the CRG module's (V10, V139). */
@RestController
@RequestMapping("/api/v1/reports")
@RequiredArgsConstructor
@Tag(name = "Custom reports", description = "Build, preview, run and schedule configurable reports over documents")
public class ReportController {

    private static final String P = "T(com.oak.dms.security.rbac.catalog.PermissionCodes).";

    private final ReportCatalogService catalogService;
    private final ReportPreviewService previewService;
    private final ReportFieldValuesService valuesService;

    @GetMapping("/catalog")
    @PreAuthorize("@authz.hasAnyPermission(" + P + "CRG_BUILD, " + P + "CRG_RUN)")
    @Operation(summary = "The fields, operators, limits and defaults the caller's organisation has configured",
            description = "Only fields the organisation has left on are listed, with the filter operators each type allows. "
                    + "Settings are already capped at the platform ceilings. 403 REPORTS_DISABLED when reports are off "
                    + "for the organisation.")
    public CatalogResponse catalog() {
        return catalogService.catalog();
    }

    @GetMapping("/catalog/fields/{key}/values")
    @PreAuthorize("@authz.hasAnyPermission(" + P + "CRG_BUILD, " + P + "CRG_RUN)")
    @Operation(summary = "The values a choice or reference field can take, one searchable page at a time",
            description = "For a filter's picker: document types, departments, people, statuses, workflow stages and the options of "
                    + "custom dropdown fields. `q` searches the label; a page holds at most 50 and `hasMore` says whether to ask "
                    + "for the next, so a large organisation's people are never sent in one answer. 404 REPORT_FIELD_NOT_FOUND for "
                    + "a field that is not in the organisation's catalogue; 422 REPORT_FIELD_HAS_NO_CHOICES for a typed field.")
    public FieldValuesResponse fieldValues(@PathVariable String key, @RequestParam(required = false) String q,
                                           @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return valuesService.values(key, q, page, size);
    }

    @PostMapping("/preview")
    @PreAuthorize("@authz.hasPermission(" + P + "CRG_BUILD)")
    @Operation(summary = "A visible-row total and the first 50 rows of a report definition",
            description = "Runs the same query a real run would, limited to documents the caller can open: totals only count "
                    + "visible rows and no count of hidden ones is ever returned (limitedToAccess just says the result is "
                    + "narrowed). A slow query is cut off at the preview timeout and answered with timedOut=true. "
                    + "422 with a stable code and the offending field when the definition is not valid; "
                    + "403 REPORT_DOCUMENT_ACCESS_REQUIRED without access to documents.")
    public PreviewResponse preview(@NotNull @RequestBody ReportDefinition definition) {
        return previewService.preview(definition);
    }
}
