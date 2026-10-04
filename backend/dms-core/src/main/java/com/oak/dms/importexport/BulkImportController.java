package com.oak.dms.importexport;

import com.oak.dms.common.dto.PageResponse;
import com.oak.dms.importexport.dto.BulkImportCreatedResponse;
import com.oak.dms.importexport.dto.BulkImportStatusResponse;
import com.oak.dms.importexport.dto.BulkImportSummary;
import com.oak.dms.importexport.dto.ImportDocumentTypeOption;
import com.oak.dms.importexport.dto.ImportFileReceivedResponse;
import com.oak.dms.importexport.dto.ImportRowFailure;
import com.oak.dms.importexport.dto.ImportTemplateFile;
import com.oak.dms.importexport.dto.ImportTemplatePreview;
import com.oak.dms.importexport.service.BulkImportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.List;
import java.util.UUID;

/**
 * Bulk import intake: download a template, upload the filled-in template
 * to open a batch, upload each file it lists, then submit (or cancel). Every byte goes
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
@Tag(name = "Bulk Import", description = "Bulk document import: templates, manifest upload, file staging, submit")
public class BulkImportController {

    private static final MediaType XLSX =
            MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final BulkImportService bulkImportService;

    @GetMapping("/import-templates/document-types")
    @PreAuthorize("@authz.hasPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).FIE_BULK_IMPORT_UPLOAD_FILE)")
    @Operation(summary = "List the active document types a bulk import template can be downloaded for")
    public List<ImportDocumentTypeOption> listDocumentTypes() {
        return bulkImportService.listDocumentTypes();
    }

    @GetMapping("/import-templates")
    @PreAuthorize("@authz.hasPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).FIE_BULK_IMPORT_UPLOAD_FILE)")
    @Operation(summary = "Download the .xlsx import template for a document type's current schema")
    public ResponseEntity<byte[]> downloadTemplate(@RequestParam("documentTypeId") UUID documentTypeId) {
        ImportTemplateFile template = bulkImportService.generateTemplate(documentTypeId);
        return ResponseEntity.ok()
                .contentType(XLSX)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(template.filename()).build().toString())
                .body(template.content());
    }

    // Read-only, so FIE-only like the other template endpoints. The separate
    // fie.bulk-import-documents.validate-preview code was never checked and
    // is deprecated by V50.
    @PostMapping(value = "/import-templates/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@authz.hasPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).FIE_BULK_IMPORT_UPLOAD_FILE)")
    @Operation(summary = "Check a filled-in import template's rows without starting an import (saves nothing)")
    public ImportTemplatePreview previewTemplate(@RequestPart("template") MultipartFile template) throws IOException {
        try (InputStream content = template.getInputStream()) {
            return bulkImportService.previewTemplate(template.getSize(), content);
        }
    }

    @PostMapping(value = "/bulk-imports", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@authz.hasPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).FIE_BULK_IMPORT_UPLOAD_FILE)"
            + " and @authz.hasPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).DOCM_DOCUMENTS_CREATE)")
    @Operation(summary = "Upload a filled-in import template to start a bulk import")
    public ResponseEntity<BulkImportCreatedResponse> createBatch(@RequestPart("template") MultipartFile template)
            throws IOException {
        try (InputStream content = template.getInputStream()) {
            BulkImportCreatedResponse response = bulkImportService.createBatch(template.getSize(), content);
            return ResponseEntity.created(URI.create("/api/v1/bulk-imports/" + response.batchId())).body(response);
        }
    }

    @PostMapping(value = "/bulk-imports/{batchId}/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@authz.hasPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).FIE_BULK_IMPORT_UPLOAD_FILE)"
            + " and @authz.hasPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).DOCM_DOCUMENTS_CREATE)")
    @Operation(summary = "Upload one file listed in the bulk import's template")
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
    @Operation(summary = "Queue the bulk import for processing (rows without a file will fail)")
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
    @Operation(summary = "List the rows of a bulk import that failed, in row order, with their problems")
    public List<ImportRowFailure> getFailures(@PathVariable("batchId") UUID batchId) {
        return bulkImportService.getFailures(batchId);
    }

    @GetMapping("/bulk-imports/{batchId}/failed-rows-template")
    @PreAuthorize("@authz.hasPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).FIE_BULK_IMPORT_UPLOAD_FILE)")
    @Operation(summary = "Download a finished bulk import's failed rows as a fixable .xlsx template")
    public ResponseEntity<byte[]> downloadFailedRowsTemplate(@PathVariable("batchId") UUID batchId) {
        ImportTemplateFile template = bulkImportService.generateFailedRowsTemplate(batchId);
        return ResponseEntity.ok()
                .contentType(XLSX)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(template.filename()).build().toString())
                .body(template.content());
    }

    @GetMapping("/bulk-imports/{batchId}")
    @PreAuthorize("@authz.hasPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).FIE_BULK_IMPORT_UPLOAD_FILE)")
    @Operation(summary = "Get a bulk import's progress and the files still missing")
    public BulkImportStatusResponse getStatus(@PathVariable("batchId") UUID batchId) {
        return bulkImportService.getStatus(batchId);
    }
}
