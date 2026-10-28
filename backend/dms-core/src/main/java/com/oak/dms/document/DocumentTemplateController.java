package com.oak.dms.document;

import com.oak.dms.scanning.controller.IngestionTicketResponses;
import com.oak.dms.scanning.dto.IngestionTicketResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
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
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.UUID;

/**
 * Document templates (see {@link DocumentTemplateService}). Anyone who can browse documents sees
 * and downloads them (download/preview go through {@code /api/v1/documents/{id}/...}); starting
 * a document from one needs docm.documents.create plus edit access to the folder. Managing them has
 * its own permissions (V70): mdtm.manage-document-templates.create to add, .update to rename or
 * replace the file, .delete to remove.
 */
@RestController
@RequestMapping("/api/v1/document-templates")
@RequiredArgsConstructor
@Tag(name = "Document templates", description = "Ready-made files a document type offers to start from")
public class DocumentTemplateController {

    private final DocumentTemplateService service;

    public record RenameTemplateRequest(@NotBlank @Size(max = 255) String name) {
    }

    /** @param folderId where the new document goes; null = the top level. @param title its title; blank = the template's name */
    public record CopyTemplateRequest(UUID folderId, @Size(max = 500) String title) {
    }

    @GetMapping
    @PreAuthorize("@authz.hasAnyPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).DOCM_DOCUMENTS_VIEW, T(com.oak.dms.security.rbac.catalog.PermissionCodes).MDTM_DOCUMENT_TEMPLATES_CREATE, "
            + "T(com.oak.dms.security.rbac.catalog.PermissionCodes).MDTM_DOCUMENT_TEMPLATES_UPDATE, T(com.oak.dms.security.rbac.catalog.PermissionCodes).MDTM_DOCUMENT_TEMPLATES_DELETE)")
    @Operation(summary = "Templates - every document type's, or one's")
    public List<DocumentTemplateService.TemplateItem> list(
            @RequestParam(value = "documentTypeId", required = false) UUID documentTypeId) {
        return service.list(documentTypeId);
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@authz.hasPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).MDTM_DOCUMENT_TEMPLATES_CREATE)")
    @Operation(summary = "Add a template to a document type - scanned for malware before it appears")
    public ResponseEntity<IngestionTicketResponse> add(@RequestPart("file") MultipartFile file,
                                                       @RequestParam("documentTypeId") UUID documentTypeId,
                                                       @RequestParam(value = "name", required = false) String name)
            throws IOException {
        try (InputStream content = file.getInputStream()) {
            return IngestionTicketResponses.toResponseEntity(service.add(documentTypeId, name,
                    file.getOriginalFilename(), file.getSize(), content));
        }
    }

    @PostMapping(value = "/{templateId}/file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("@authz.hasPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).MDTM_DOCUMENT_TEMPLATES_UPDATE)")
    @Operation(summary = "Replace a template's file - the old one stays in its version history")
    public ResponseEntity<IngestionTicketResponse> replace(@PathVariable UUID templateId,
                                                           @RequestPart("file") MultipartFile file) throws IOException {
        try (InputStream content = file.getInputStream()) {
            return IngestionTicketResponses.toResponseEntity(service.replace(templateId,
                    file.getOriginalFilename(), file.getSize(), content));
        }
    }

    @PutMapping("/{templateId}")
    @PreAuthorize("@authz.hasPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).MDTM_DOCUMENT_TEMPLATES_UPDATE)")
    @Operation(summary = "Rename a template")
    public DocumentTemplateService.TemplateItem rename(@PathVariable UUID templateId,
                                                       @Valid @RequestBody RenameTemplateRequest request) {
        return service.rename(templateId, request.name());
    }

    @DeleteMapping("/{templateId}")
    @PreAuthorize("@authz.hasPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).MDTM_DOCUMENT_TEMPLATES_DELETE)")
    @Operation(summary = "Stop offering a template")
    public ResponseEntity<Void> remove(@PathVariable UUID templateId) {
        service.remove(templateId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{templateId}/copy")
    @PreAuthorize("@authz.hasPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).DOCM_DOCUMENTS_CREATE)")
    @Operation(summary = "Start a new document from a template, in a folder")
    public ResponseEntity<IngestionTicketResponse> copy(@PathVariable UUID templateId,
                                                        @Valid @RequestBody CopyTemplateRequest request) {
        return IngestionTicketResponses.toResponseEntity(service.copy(templateId, request.folderId(), request.title()));
    }
}
