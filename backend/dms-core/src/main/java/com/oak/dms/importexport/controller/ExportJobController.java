package com.oak.dms.importexport.controller;

import com.oak.dms.common.dto.PageResponse;
import com.oak.dms.importexport.dto.CreateExportRequest;
import com.oak.dms.importexport.dto.ExportJobResponse;
import com.oak.dms.importexport.dto.ExportPreviewResponse;
import com.oak.dms.importexport.dto.ImportDocumentTypeOption;
import com.oak.dms.importexport.export.BulkExportService;
import com.oak.dms.importexport.service.ExportJobService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.InputStream;
import java.util.List;
import java.util.UUID;

/**
 * Bulk export (Module 2.1). "My exports" is open to any signed-in user -
 * only their own jobs, scoped from the session (ExportJobService#listMine).
 * Starting an export needs fie.bulk-export-documents.execute-export and
 * docm.documents.view (it reads documents); downloading one's own finished
 * export needs docm.documents.view, so losing document access also ends
 * access to exports made with it.
 */
@RestController
@RequestMapping("/api/v1/export-jobs")
@RequiredArgsConstructor
@Tag(name = "Export jobs", description = "Bulk export of documents and their metadata")
public class ExportJobController {

    private static final String CAN_EXPORT =
            "@authz.hasPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).FIE_BULK_EXPORT_EXECUTE)"
                    + " and @authz.hasPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).DOCM_DOCUMENTS_VIEW)";
    private static final String CAN_VIEW_DOCUMENTS =
            "@authz.hasPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).DOCM_DOCUMENTS_VIEW)";

    private final ExportJobService exportJobService;
    private final BulkExportService bulkExportService;

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

    @GetMapping("/document-types")
    @PreAuthorize(CAN_EXPORT)
    @Operation(summary = "Document types an export can be filtered by")
    public List<ImportDocumentTypeOption> documentTypes() {
        return bulkExportService.documentTypes();
    }

    @PostMapping("/preview")
    @PreAuthorize(CAN_EXPORT)
    @Operation(summary = "How many documents an export would contain - nothing is started")
    public ExportPreviewResponse preview(@Valid @RequestBody CreateExportRequest request) {
        return bulkExportService.preview(request);
    }

    @PostMapping
    @PreAuthorize(CAN_EXPORT)
    @Operation(summary = "Start a bulk export",
            description = "documentIds, or documentTypeId with an optional createdFrom/createdTo (inclusive). "
                    + "metadataFormat xlsx (default) or csv; includeFiles false for the metadata sheet only. "
                    + "Built in the background - follow it in My exports.")
    public ResponseEntity<ExportJobResponse> request(@Valid @RequestBody CreateExportRequest request) {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(bulkExportService.request(request));
    }

    @GetMapping("/{exportJobId}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "One of the caller's export jobs")
    public ExportJobResponse get(@PathVariable UUID exportJobId) {
        return bulkExportService.get(exportJobId);
    }

    @GetMapping("/{exportJobId}/download")
    @PreAuthorize(CAN_VIEW_DOCUMENTS)
    @Operation(summary = "Download a finished export (a ZIP) before it expires")
    public ResponseEntity<StreamingResponseBody> download(@PathVariable UUID exportJobId) {
        BulkExportService.DownloadedExport export = bulkExportService.download(exportJobId);
        StreamingResponseBody body = out -> {
            try (InputStream content = export.content()) {
                content.transferTo(out);
            }
        };
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(export.sizeBytes()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(export.filename()).build().toString())
                .body(body);
    }
}
