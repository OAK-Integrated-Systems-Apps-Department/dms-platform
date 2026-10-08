package com.oak.dms.metadata.controller;

import com.oak.dms.common.dto.PageResponse;
import com.oak.dms.metadata.dto.CreateDocumentTypeRequest;
import com.oak.dms.metadata.dto.DocumentTypeResponse;
import com.oak.dms.metadata.dto.SelectableDocumentTypeResponse;
import com.oak.dms.metadata.dto.UpdateDocumentTypeRequest;
import com.oak.dms.metadata.service.DocumentTypeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.UUID;

/**
 * REST endpoints for tenant-scoped document types (A5).
 */
@RestController
@RequestMapping("/api/v1/document-types")
@RequiredArgsConstructor
@Tag(name = "Document Types", description = "Content type definitions (module 2.7)")
public class DocumentTypeController {

    private final DocumentTypeService documentTypeService;

    @PostMapping
    @PreAuthorize("@authz.hasPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).MDTM_CONTENT_TYPES_CREATE)")
    @Operation(summary = "Create a document type in the caller's tenant")
    public ResponseEntity<DocumentTypeResponse> createDocumentType(@Valid @RequestBody CreateDocumentTypeRequest request) {
        DocumentTypeResponse response = documentTypeService.createDocumentType(request);
        return ResponseEntity.created(URI.create("/api/v1/document-types/" + response.documentTypeId())).body(response);
    }

    @GetMapping("/{documentTypeId}")
    @PreAuthorize("@authz.hasAnyPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).MDTM_CONTENT_TYPES_VIEW, T(com.oak.dms.security.rbac.catalog.PermissionCodes).MDTM_CONTENT_TYPES_UPDATE)")
    @Operation(summary = "Get a document type by id")
    public DocumentTypeResponse getDocumentType(@PathVariable UUID documentTypeId) {
        return documentTypeService.getDocumentType(documentTypeId);
    }

    @GetMapping("/selectable")
    @PreAuthorize("@authz.hasPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).DOCM_DOCUMENTS_CREATE)")
    @Operation(summary = "Active document types a document can be uploaded as",
            description = "Id, code and name only, in display order. For upload screens: needs only the "
                    + "document-create permission, unlike the full admin list below.")
    public List<SelectableDocumentTypeResponse> listSelectable() {
        return documentTypeService.listSelectableDocumentTypes();
    }

    @GetMapping
    @PreAuthorize("@authz.hasAnyPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).MDTM_CONTENT_TYPES_VIEW, T(com.oak.dms.security.rbac.catalog.PermissionCodes).MDTM_CONTENT_TYPES_UPDATE, T(com.oak.dms.security.rbac.catalog.PermissionCodes).MDTM_TYPE_FIELD_MAPPING_VIEW, T(com.oak.dms.security.rbac.catalog.PermissionCodes).MDTM_TYPE_FIELD_MAPPING_UPDATE)")
    @Operation(summary = "List document types in the caller's tenant",
            description = "Also readable by mdtm.manage-type-field-mapping.* - the document-type column "
                    + "picker on the Mapping matrix screen is populated straight from this list.")
    public PageResponse<DocumentTypeResponse> listDocumentTypes(Pageable pageable) {
        return PageResponse.from(documentTypeService.listDocumentTypes(pageable));
    }

    @PutMapping("/{documentTypeId}")
    @PreAuthorize("@authz.hasPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).MDTM_CONTENT_TYPES_UPDATE)")
    @Operation(summary = "Update a document type's descriptive/behavioral attributes")
    public DocumentTypeResponse updateDocumentType(@PathVariable UUID documentTypeId,
                                                    @Valid @RequestBody UpdateDocumentTypeRequest request) {
        return documentTypeService.updateDocumentType(documentTypeId, request);
    }

    @PostMapping("/{documentTypeId}/activate")
    @PreAuthorize("@authz.hasPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).MDTM_CONTENT_TYPES_ACTIVATE_DEACTIVATE)")
    @Operation(summary = "Activate a document type")
    public DocumentTypeResponse activateDocumentType(@PathVariable UUID documentTypeId) {
        return documentTypeService.activateDocumentType(documentTypeId);
    }

    @PostMapping("/{documentTypeId}/deactivate")
    @PreAuthorize("@authz.hasPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).MDTM_CONTENT_TYPES_ACTIVATE_DEACTIVATE)")
    @Operation(summary = "Deactivate a document type")
    public DocumentTypeResponse deactivateDocumentType(@PathVariable UUID documentTypeId) {
        return documentTypeService.deactivateDocumentType(documentTypeId);
    }
}
