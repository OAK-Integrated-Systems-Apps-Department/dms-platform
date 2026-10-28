package com.oak.dms.importexport.controller;

import com.oak.dms.importexport.dto.ImportTemplateFieldResponse;
import com.oak.dms.importexport.dto.ImportTemplateRequest;
import com.oak.dms.importexport.dto.ImportTemplateResponse;
import com.oak.dms.importexport.service.ImportTemplateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * The tenant's saved import templates - see {@link ImportTemplateService}.
 * Anyone who can bulk import (or manage templates) can list and use them;
 * creating, editing and deleting need the matching
 * fie.manage-import-templates.* code. Downloading one is
 * GET /api/v1/import-templates/saved/{id}/download (BulkImportController).
 */
@RestController
@RequestMapping("/api/v1/import-templates")
@RequiredArgsConstructor
@Tag(name = "Import templates", description = "Saved, tenant-wide bulk import templates")
public class ImportTemplateController {

    private static final String P = "T(com.oak.dms.security.rbac.catalog.PermissionCodes).";
    private static final String CAN_CREATE = "@authz.hasPermission(" + P + "FIE_IMPORT_TEMPLATES_CREATE)";
    private static final String CAN_UPDATE = "@authz.hasPermission(" + P + "FIE_IMPORT_TEMPLATES_UPDATE)";
    private static final String CAN_DELETE = "@authz.hasPermission(" + P + "FIE_IMPORT_TEMPLATES_DELETE)";
    private static final String CAN_EDIT = CAN_CREATE + " or " + CAN_UPDATE;
    private static final String CAN_USE = "@authz.hasPermission(" + P + "FIE_BULK_IMPORT_UPLOAD_FILE) or "
            + CAN_EDIT + " or " + CAN_DELETE;

    private final ImportTemplateService importTemplateService;

    @GetMapping("/saved")
    @PreAuthorize(CAN_USE)
    @Operation(summary = "List the tenant's saved import templates, by name",
            description = "documentTypeId is optional - only that document type's templates.")
    public List<ImportTemplateResponse> list(@RequestParam(required = false) UUID documentTypeId) {
        return importTemplateService.list(documentTypeId);
    }

    @GetMapping("/saved/{importTemplateId}")
    @PreAuthorize(CAN_USE)
    @Operation(summary = "One saved import template")
    public ImportTemplateResponse get(@PathVariable UUID importTemplateId) {
        return importTemplateService.get(importTemplateId);
    }

    @GetMapping("/fields")
    @PreAuthorize(CAN_EDIT)
    @Operation(summary = "The fields a template for a document type can use, in form order")
    public List<ImportTemplateFieldResponse> fields(@RequestParam UUID documentTypeId) {
        return importTemplateService.fields(documentTypeId);
    }

    @PostMapping("/saved")
    @PreAuthorize(CAN_CREATE)
    @Operation(summary = "Save a new import template")
    public ResponseEntity<ImportTemplateResponse> create(@Valid @RequestBody ImportTemplateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(importTemplateService.create(request));
    }

    @PutMapping("/saved/{importTemplateId}")
    @PreAuthorize(CAN_UPDATE)
    @Operation(summary = "Edit a saved import template")
    public ImportTemplateResponse update(@PathVariable UUID importTemplateId,
                                         @Valid @RequestBody ImportTemplateRequest request) {
        return importTemplateService.update(importTemplateId, request);
    }

    @DeleteMapping("/saved/{importTemplateId}")
    @PreAuthorize(CAN_DELETE)
    @Operation(summary = "Delete a saved import template")
    public ResponseEntity<Void> delete(@PathVariable UUID importTemplateId) {
        importTemplateService.delete(importTemplateId);
        return ResponseEntity.noContent().build();
    }
}
