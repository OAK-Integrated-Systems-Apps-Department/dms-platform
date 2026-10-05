package com.oak.dms.importexport;

import com.oak.dms.common.dto.PageResponse;
import com.oak.dms.importexport.dto.BulkImportCreatedResponse;
import com.oak.dms.importexport.dto.BulkImportStatusResponse;
import com.oak.dms.importexport.dto.BulkImportSummary;
import com.oak.dms.importexport.dto.CreateBulkImportRequest;
import com.oak.dms.importexport.dto.ImportDefaultsResponse;
import com.oak.dms.importexport.dto.ImportDocumentTypeOption;
import com.oak.dms.importexport.dto.ImportFileReceivedResponse;
import com.oak.dms.importexport.dto.ImportRowFailure;
import com.oak.dms.importexport.service.BulkImportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.List;
import java.util.UUID;

/**
 * Bulk import intake, files first: open a batch for a document type, upload
 * its files one at a time, then submit (or cancel). Every document gets the
 * type's default metadata - nothing is entered per file. Every byte goes
 * through {@link BulkImportService}.
 * <p>
 * An import creates documents, so it must not bypass document-create: starting
 * a batch, uploading its files and submitting it also require
 * {@code DOCM_DOCUMENTS_CREATE}. The worker later creates the documents with no
 * request (and so no caller to check), which is why the check sits here, up to
 * and including submit. Reading (including the recent-imports list), failures
 * and cancel stay FIE-only, so a user who
 * loses document-create mid-import can still see and cancel it.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "Bulk Import", description = "Bulk document import: open a batch, upload files, submit")
public class BulkImportController {

    private final BulkImportService bulkImportService;

    // The path predates the files-first flow (there are no templates any
    // more); kept because the frontend calls it.
    @GetMapping("/import-templates/document-types")
    @PreAuthorize("@authz.hasPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).FIE_BULK_IMPORT_UPLOAD_FILE)")
    @Operation(summary = "List the active document types a bulk import can be started for")
    public List<ImportDocumentTypeOption> listDocumentTypes() {
        return bulkImportService.listDocumentTypes();
    }

    // Read-only, so FIE-only like the other reads.
    @GetMapping("/bulk-imports/document-types/{documentTypeId}/defaults")
    @PreAuthorize("@authz.hasPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).FIE_BULK_IMPORT_UPLOAD_FILE)")
    @Operation(summary = "The default metadata an import into the document type would apply, and the mandatory"
            + " fields that have no default")
    public ImportDefaultsResponse getDefaults(@PathVariable("documentTypeId") UUID documentTypeId) {
        return bulkImportService.getDefaults(documentTypeId);
    }

    @PostMapping(value = "/bulk-imports", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("@authz.hasPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).FIE_BULK_IMPORT_UPLOAD_FILE)"
            + " and @authz.hasPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).DOCM_DOCUMENTS_CREATE)")
    @Operation(summary = "Start a bulk import into a document type (its files are uploaded next)")
    public ResponseEntity<BulkImportCreatedResponse> createBatch(@Valid @RequestBody CreateBulkImportRequest request) {
        BulkImportCreatedResponse response = bulkImportService.createBatch(request.documentTypeId());
        return ResponseEntity.created(URI.create("/api/v1/bulk-imports/" + response.batchId())).body(response);
    }

    @PostMapping(value = "/bulk-imports/{batchId}/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@authz.hasPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).FIE_BULK_IMPORT_UPLOAD_FILE)"
            + " and @authz.hasPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).DOCM_DOCUMENTS_CREATE)")
    @Operation(summary = "Upload one file into the bulk import - each upload becomes one document")
    public ImportFileReceivedResponse uploadFile(@PathVariable("batchId") UUID batchId,
                                                 @RequestPart("file") MultipartFile file) throws IOException {
        try (InputStream content = file.getInputStream()) {
            return bulkImportService.receiveFile(batchId, file.getOriginalFilename(), file.getContentType(),
                    file.getSize(), content);
        }
    }

    @PostMapping("/bulk-imports/{batchId}/submit")
    @PreAuthorize("@authz.hasPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).FIE_BULK_IMPORT_UPLOAD_FILE)"
            + " and @authz.hasPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).DOCM_DOCUMENTS_CREATE)")
    @Operation(summary = "Queue the bulk import for processing (needs at least one accepted file)")
    public ResponseEntity<Void> submit(@PathVariable("batchId") UUID batchId) {
        bulkImportService.submit(batchId);
        return ResponseEntity.accepted().build();
    }

    @GetMapping("/bulk-imports")
    @PreAuthorize("@authz.hasPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).FIE_BULK_IMPORT_UPLOAD_FILE)")
    @Operation(summary = "List the caller's bulk imports in every status, newest first (page size capped at 50)")
    public PageResponse<BulkImportSummary> listBatches(Pageable pageable) {
        return PageResponse.from(bulkImportService.listBatches(pageable));
    }

    @GetMapping("/bulk-imports/active")
    @PreAuthorize("@authz.hasPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).FIE_BULK_IMPORT_UPLOAD_FILE)")
    @Operation(summary = "Get the caller's in-progress bulk import (204 if there is none)")
    public ResponseEntity<BulkImportStatusResponse> getActive() {
        return bulkImportService.getActive()
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/bulk-imports/{batchId}/cancel")
    @PreAuthorize("@authz.hasPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).FIE_BULK_IMPORT_UPLOAD_FILE)")
    @Operation(summary = "Cancel a bulk import that is still uploading or queued, discarding its staged files")
    public BulkImportStatusResponse cancel(@PathVariable("batchId") UUID batchId) {
        return bulkImportService.cancel(batchId);
    }

    @GetMapping("/bulk-imports/{batchId}/failures")
    @PreAuthorize("@authz.hasPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).FIE_BULK_IMPORT_UPLOAD_FILE)")
    @Operation(summary = "List the files of a bulk import that failed, in upload order, with their problems")
    public List<ImportRowFailure> getFailures(@PathVariable("batchId") UUID batchId) {
        return bulkImportService.getFailures(batchId);
    }

    @GetMapping("/bulk-imports/{batchId}")
    @PreAuthorize("@authz.hasPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).FIE_BULK_IMPORT_UPLOAD_FILE)")
    @Operation(summary = "Get a bulk import's progress and the files that were rejected")
    public BulkImportStatusResponse getStatus(@PathVariable("batchId") UUID batchId) {
        return bulkImportService.getStatus(batchId);
    }
}
