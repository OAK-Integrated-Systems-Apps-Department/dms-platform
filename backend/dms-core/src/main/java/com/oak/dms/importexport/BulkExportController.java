package com.oak.dms.importexport;

import com.oak.dms.common.dto.PageResponse;
import com.oak.dms.importexport.dto.BulkExportDownload;
import com.oak.dms.importexport.dto.BulkExportFilters;
import com.oak.dms.importexport.dto.BulkExportJobResponse;
import com.oak.dms.importexport.dto.BulkExportPreviewResponse;
import com.oak.dms.importexport.dto.BulkExportSelection;
import com.oak.dms.importexport.dto.CreateBulkExportRequest;
import com.oak.dms.importexport.dto.ExportableDocumentResponse;
import com.oak.dms.importexport.dto.ImportDocumentTypeOption;
import com.oak.dms.importexport.service.BulkExportService;
import com.oak.dms.importexport.service.BulkImportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.InputStream;
import java.net.URI;
import java.util.List;
import java.util.UUID;

/**
 * Bulk export: list the documents the caller may export, preview a
 * selection, request an export, follow it and download its ZIP. Every byte
 * goes through {@link BulkExportService}.
 * <p>
 * An export moves documents out of the system, so it must not bypass
 * document-view: every endpoint requires both
 * {@code FIE_BULK_EXPORT_EXECUTE} and {@code DOCM_DOCUMENTS_VIEW}. The
 * worker builds the ZIP later with no request, and re-checks the same two
 * codes for the requester itself (see {@code BulkExportAccessPolicy}).
 * <p>
 * Paths are under {@code /bulk-exports}, deliberately not develop's
 * {@code /export-jobs}, whose controller is a different design.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "Bulk Export", description = "Bulk document export: pick documents, request a ZIP, download it")
public class BulkExportController {

    private static final String CAN_EXPORT =
            "@authz.hasPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).FIE_BULK_EXPORT_EXECUTE)"
                    + " and @authz.hasPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).DOCM_DOCUMENTS_VIEW)";

    private final BulkExportService bulkExportService;
    private final BulkImportService bulkImportService;

    /**
     * The type filter's options. The other type lists need the import or
     * metadata-management permissions, which an exporter may not hold, so
     * this serves the same active-types list under the export permissions.
     */
    @GetMapping("/bulk-exports/document-types")
    @PreAuthorize(CAN_EXPORT)
    @Operation(summary = "List the active document types, for the export screen's type filter")
    public List<ImportDocumentTypeOption> listDocumentTypes() {
        return bulkImportService.listDocumentTypes();
    }

    @GetMapping("/bulk-exports/documents")
    @PreAuthorize(CAN_EXPORT)
    @Operation(summary = "List the documents the caller may export, newest first (page size capped at 50)")
    public PageResponse<ExportableDocumentResponse> listDocuments(@ModelAttribute BulkExportFilters filters,
                                                                  Pageable pageable) {
        return PageResponse.from(bulkExportService.listDocuments(filters, pageable));
    }

    @PostMapping(value = "/bulk-exports/preview", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize(CAN_EXPORT)
    @Operation(summary = "Count the documents and bytes an export of the selection would contain, against the limits")
    public BulkExportPreviewResponse preview(@RequestBody BulkExportSelection selection) {
        return bulkExportService.preview(selection);
    }

    @PostMapping(value = "/bulk-exports", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize(CAN_EXPORT)
    @Operation(summary = "Request an export of the selection (built in the background; one active export per user)")
    public ResponseEntity<BulkExportJobResponse> request(@Valid @RequestBody CreateBulkExportRequest request) {
        BulkExportJobResponse response = bulkExportService.request(request);
        return ResponseEntity.created(URI.create("/api/v1/bulk-exports/" + response.exportJobId())).body(response);
    }

    @GetMapping("/bulk-exports")
    @PreAuthorize(CAN_EXPORT)
    @Operation(summary = "List the caller's exports in every status, newest first (page size capped at 50)")
    public PageResponse<BulkExportJobResponse> listMine(Pageable pageable) {
        return PageResponse.from(bulkExportService.listMine(pageable));
    }

    @GetMapping("/bulk-exports/{exportJobId}")
    @PreAuthorize(CAN_EXPORT)
    @Operation(summary = "Get one of the caller's exports")
    public BulkExportJobResponse getMine(@PathVariable("exportJobId") UUID exportJobId) {
        return bulkExportService.getMine(exportJobId);
    }

    @GetMapping(value = "/bulk-exports/{exportJobId}/download", produces = "application/zip")
    @PreAuthorize(CAN_EXPORT)
    @Operation(summary = "Download the ZIP of the caller's completed export (until it expires)")
    public ResponseEntity<StreamingResponseBody> download(@PathVariable("exportJobId") UUID exportJobId) {
        BulkExportDownload download = bulkExportService.openDownload(exportJobId);
        StreamingResponseBody body = out -> {
            try (InputStream in = download.content()) {
                in.transferTo(out);
            }
        };
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .contentLength(download.sizeBytes())
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(download.filename()).build().toString())
                .body(body);
    }
}
