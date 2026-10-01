package com.oak.dms.importexport.controller;

import com.oak.dms.common.dto.PageResponse;
import com.oak.dms.importexport.dto.ImportPreviewResponse;
import com.oak.dms.importexport.dto.MigrationBatchResponse;
import com.oak.dms.importexport.dto.MigrationItemResponse;
import com.oak.dms.importexport.enums.TemplateFormat;
import com.oak.dms.importexport.service.BulkImportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * Template-based bulk import (Module 2.1) - see {@link BulkImportService}.
 * Permissions are the FIE codes seeded by V10: upload-file to fetch a
 * template and follow one's own imports, validate-preview to check a
 * template, execute-import (plus docm.documents.create, since every row
 * becomes a document) to start or cancel one.
 */
@RestController
@RequestMapping("/api/v1/bulk-import")
@RequiredArgsConstructor
@Tag(name = "Bulk import", description = "Import many documents with their metadata from a template")
public class BulkImportController {

    private static final String CAN_UPLOAD =
            "@authz.hasPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).FIE_BULK_IMPORT_UPLOAD_FILE)";
    private static final String CAN_PREVIEW =
            "@authz.hasPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).FIE_BULK_IMPORT_VALIDATE_PREVIEW)";
    private static final String CAN_EXECUTE =
            "@authz.hasPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).FIE_BULK_IMPORT_EXECUTE)"
                    + " and @authz.hasPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).DOCM_DOCUMENTS_CREATE)";

    private final BulkImportService bulkImportService;

    @GetMapping("/templates/{documentTypeId}")
    @PreAuthorize(CAN_UPLOAD)
    @Operation(summary = "Download the import template for a document type",
            description = "format: xlsx (default) or csv. Generated from the type's current schema, with guidance "
                    + "and a version marker - a template for an older schema version is refused on upload.")
    public ResponseEntity<byte[]> template(@PathVariable UUID documentTypeId,
                                           @RequestParam(defaultValue = "xlsx") String format) {
        return file(bulkImportService.template(documentTypeId, TemplateFormat.fromValue(format)));
    }

    @PostMapping(value = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(CAN_PREVIEW)
    @Operation(summary = "Check a filled-in template without importing anything")
    public ImportPreviewResponse preview(@RequestPart("template") MultipartFile template) {
        return bulkImportService.preview(template);
    }

    @PostMapping(value = "/batches", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(CAN_EXECUTE)
    @Operation(summary = "Start an import",
            description = "template plus its files: archive (a ZIP), files (loose files), or uploadSessionId (a "
                    + "completed resumable upload of a large ZIP). resubmitOf reuses an earlier import's files, for "
                    + "fixed failed rows. Rows are validated now; valid ones import in the background. Send an "
                    + "Idempotency-Key header to make retries safe: a repeated key returns the first import (200).")
    public ResponseEntity<MigrationBatchResponse> start(
            @RequestPart("template") MultipartFile template,
            @RequestPart(value = "archive", required = false) MultipartFile archive,
            @RequestPart(value = "files", required = false) List<MultipartFile> files,
            @RequestParam(required = false) UUID uploadSessionId,
            @RequestParam(required = false) UUID resubmitOf,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        BulkImportService.StartResult result = bulkImportService.start(template, archive, files, uploadSessionId,
                resubmitOf, idempotencyKey);
        return ResponseEntity.status(result.created() ? HttpStatus.ACCEPTED : HttpStatus.OK).body(result.batch());
    }

    @GetMapping("/batches")
    @PreAuthorize(CAN_UPLOAD)
    @Operation(summary = "List the caller's own imports, newest first")
    public PageResponse<MigrationBatchResponse> listMine(
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return PageResponse.from(bulkImportService.listMine(pageable));
    }

    @GetMapping("/batches/{batchId}")
    @PreAuthorize(CAN_UPLOAD)
    @Operation(summary = "One import's progress")
    public MigrationBatchResponse get(@PathVariable UUID batchId) {
        return bulkImportService.get(batchId);
    }

    @GetMapping("/batches/{batchId}/items")
    @PreAuthorize(CAN_UPLOAD)
    @Operation(summary = "An import's rows and their results",
            description = "status is optional: pending, success, failed or cancelled.")
    public PageResponse<MigrationItemResponse> items(
            @PathVariable UUID batchId,
            @RequestParam(required = false) String status,
            @PageableDefault(size = 50, sort = "rowNumber", direction = Sort.Direction.ASC) Pageable pageable) {
        return PageResponse.from(bulkImportService.items(batchId, status, pageable));
    }

    @PostMapping("/batches/{batchId}/cancel")
    @PreAuthorize(CAN_EXECUTE)
    @Operation(summary = "Cancel an import",
            description = "Rows not started yet are cancelled; a file already being scanned still finishes.")
    public MigrationBatchResponse cancel(@PathVariable UUID batchId) {
        return bulkImportService.cancel(batchId);
    }

    @GetMapping("/batches/{batchId}/failed-rows")
    @PreAuthorize(CAN_UPLOAD)
    @Operation(summary = "Download the failed rows as a template, with why each failed",
            description = "format: xlsx or csv, defaulting to the format that was uploaded. Fix the rows and start a "
                    + "new import with resubmitOf set to this import - its files don't need uploading again.")
    public ResponseEntity<byte[]> failedRows(@PathVariable UUID batchId,
                                             @RequestParam(required = false) String format) {
        return file(bulkImportService.failedRows(batchId, format == null ? null : TemplateFormat.fromValue(format)));
    }

    private static ResponseEntity<byte[]> file(BulkImportService.TemplateFile file) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, file.contentType())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.fileName(), StandardCharsets.UTF_8).build().toString())
                .body(file.content());
    }
}
