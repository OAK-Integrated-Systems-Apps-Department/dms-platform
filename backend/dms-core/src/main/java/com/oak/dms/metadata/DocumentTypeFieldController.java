package com.oak.dms.metadata;

import com.oak.dms.metadata.dto.DocumentTypeFieldResponse;
import com.oak.dms.metadata.dto.DocumentTypeSchemaResponse;
import com.oak.dms.metadata.dto.ReplaceDocumentTypeFieldsRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * The document-type <-> field mapping and resolved schema (A6).
 */
@RestController
@RequestMapping("/api/v1/document-types/{documentTypeId}")
@RequiredArgsConstructor
@Tag(name = "Document Type Field Mapping", description = "Versioned type<->field mapping and resolved schema (module 2.7)")
public class DocumentTypeFieldController {

    private final DocumentTypeFieldService documentTypeFieldService;

    @GetMapping("/fields")
    @PreAuthorize("@authz.hasAnyPermission(T(com.oak.dms.security.rbac.PermissionCodes).MDTM_TYPE_FIELD_MAPPING_VIEW, T(com.oak.dms.security.rbac.PermissionCodes).MDTM_TYPE_FIELD_MAPPING_UPDATE)")
    @Operation(summary = "Get a document type's field mapping",
            description = "Without ?version, returns the active mapping at the type's current schema "
                    + "version. With ?version=N, returns that historical (possibly archived) version instead.")
    public List<DocumentTypeFieldResponse> getFields(@PathVariable UUID documentTypeId,
                                                       @RequestParam(required = false) Integer version) {
        return version == null
                ? documentTypeFieldService.getCurrentFields(documentTypeId)
                : documentTypeFieldService.getFieldsAtVersion(documentTypeId, version);
    }

    @PutMapping("/fields")
    @PreAuthorize("@authz.hasPermission(T(com.oak.dms.security.rbac.PermissionCodes).MDTM_TYPE_FIELD_MAPPING_UPDATE)")
    @Operation(summary = "Replace a document type's field mapping, publishing a new schema version",
            description = "The given field list becomes schema_version = current + 1; the prior version's "
                    + "rows are archived, not deleted. A field newly marked mandatory on this publish gets "
                    + "requires_backfill = true so legacy documents can be flagged rather than rejected.")
    public List<DocumentTypeFieldResponse> replaceFields(@PathVariable UUID documentTypeId,
                                                           @Valid @RequestBody ReplaceDocumentTypeFieldsRequest request) {
        return documentTypeFieldService.replaceMapping(documentTypeId, request);
    }

    @GetMapping("/schema")
    @PreAuthorize("@authz.hasAnyPermission(T(com.oak.dms.security.rbac.PermissionCodes).MDTM_TYPE_FIELD_MAPPING_VIEW, T(com.oak.dms.security.rbac.PermissionCodes).MDTM_TYPE_FIELD_MAPPING_UPDATE)")
    @Operation(summary = "Get the resolved form schema for a document type",
            description = "Fields, labels, types, mandatory flags, options, order, and validation rules in "
                    + "one payload (MDTM-FR-006) - for the upload form and for reuse by import, OCR, search "
                    + "and workflow once those modules exist.")
    public DocumentTypeSchemaResponse getSchema(@PathVariable UUID documentTypeId) {
        return documentTypeFieldService.getSchema(documentTypeId);
    }
}
