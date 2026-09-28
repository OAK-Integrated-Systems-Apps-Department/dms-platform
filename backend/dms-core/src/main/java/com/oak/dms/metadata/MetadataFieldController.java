package com.oak.dms.metadata;

import com.oak.dms.common.dto.PageResponse;
import com.oak.dms.metadata.dto.CreateMetadataFieldRequest;
import com.oak.dms.metadata.dto.MetadataFieldResponse;
import com.oak.dms.metadata.dto.UpdateMetadataFieldRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.UUID;

/**
 * REST endpoints for the tenant-scoped metadata field registry (A3).
 */
@RestController
@RequestMapping("/api/v1/metadata-fields")
@RequiredArgsConstructor
@Tag(name = "Metadata Fields", description = "Centralized metadata schema registry (module 2.7)")
public class MetadataFieldController {

    private final MetadataFieldService metadataFieldService;

    @PostMapping
    @PreAuthorize("@authz.hasPermission(T(com.oak.dms.security.rbac.PermissionCodes).MDTM_METADATA_SCHEMA_CREATE_FIELD)")
    @Operation(summary = "Add a new field to the caller's tenant metadata schema")
    public ResponseEntity<MetadataFieldResponse> createField(@Valid @RequestBody CreateMetadataFieldRequest request) {
        MetadataFieldResponse response = metadataFieldService.createField(request);
        return ResponseEntity.created(URI.create("/api/v1/metadata-fields/" + response.fieldId())).body(response);
    }

    @GetMapping("/{fieldId}")
    @PreAuthorize("@authz.hasAnyPermission(T(com.oak.dms.security.rbac.PermissionCodes).MDTM_METADATA_SCHEMA_VIEW_FIELD, T(com.oak.dms.security.rbac.PermissionCodes).MDTM_METADATA_SCHEMA_UPDATE_FIELD)")
    @Operation(summary = "Get a metadata field by id")
    public MetadataFieldResponse getField(@PathVariable UUID fieldId) {
        return metadataFieldService.getField(fieldId);
    }

    @GetMapping
    @PreAuthorize("@authz.hasAnyPermission(T(com.oak.dms.security.rbac.PermissionCodes).MDTM_METADATA_SCHEMA_VIEW_FIELD, T(com.oak.dms.security.rbac.PermissionCodes).MDTM_METADATA_SCHEMA_UPDATE_FIELD, T(com.oak.dms.security.rbac.PermissionCodes).MDTM_TYPE_FIELD_MAPPING_VIEW, T(com.oak.dms.security.rbac.PermissionCodes).MDTM_TYPE_FIELD_MAPPING_UPDATE)")
    @Operation(summary = "List metadata fields in the caller's tenant",
            description = "Also readable by mdtm.manage-type-field-mapping.* - the field picker on the "
                    + "Document Type / Mapping screens is populated straight from this list.")
    public PageResponse<MetadataFieldResponse> listFields(@RequestParam(required = false) String status,
                                                            @RequestParam(required = false) String dataType,
                                                            @RequestParam(required = false) String search,
                                                            Pageable pageable) {
        return PageResponse.from(metadataFieldService.listFields(status, dataType, search, pageable));
    }

    @PutMapping("/{fieldId}")
    @PreAuthorize("@authz.hasPermission(T(com.oak.dms.security.rbac.PermissionCodes).MDTM_METADATA_SCHEMA_UPDATE_FIELD)")
    @Operation(summary = "Update a metadata field's descriptive/behavioral attributes",
            description = "labelAliases (the field's global OCR label hints, in priority order) is the one "
                    + "exception to full replacement: null/omitted leaves them unchanged, [] clears them. Hints "
                    + "for a single document type are set via PUT /api/v1/document-types/{documentTypeId}/fields/"
                    + "{fieldId}/label-aliases instead.")
    public MetadataFieldResponse updateField(@PathVariable UUID fieldId,
                                              @Valid @RequestBody UpdateMetadataFieldRequest request) {
        return metadataFieldService.updateField(fieldId, request);
    }

    @PostMapping("/{fieldId}/activate")
    @PreAuthorize("@authz.hasPermission(T(com.oak.dms.security.rbac.PermissionCodes).MDTM_METADATA_SCHEMA_ACTIVATE_DEACTIVATE_FIELD)")
    @Operation(summary = "Activate a metadata field")
    public MetadataFieldResponse activateField(@PathVariable UUID fieldId) {
        return metadataFieldService.activateField(fieldId);
    }

    @PostMapping("/{fieldId}/deactivate")
    @PreAuthorize("@authz.hasPermission(T(com.oak.dms.security.rbac.PermissionCodes).MDTM_METADATA_SCHEMA_ACTIVATE_DEACTIVATE_FIELD)")
    @Operation(summary = "Deactivate a metadata field")
    public MetadataFieldResponse deactivateField(@PathVariable UUID fieldId) {
        return metadataFieldService.deactivateField(fieldId);
    }

    @PostMapping("/{fieldId}/deprecate")
    @PreAuthorize("@authz.hasPermission(T(com.oak.dms.security.rbac.PermissionCodes).MDTM_METADATA_SCHEMA_DEPRECATE_FIELD)")
    @Operation(summary = "Deprecate a metadata field",
            description = "The offered alternative when deletion is blocked because the field is still in "
                    + "use (MDTM alternate flow 2) - a deprecated field stays usable on documents that "
                    + "already reference it, but is excluded from new document-type field pickers.")
    public MetadataFieldResponse deprecateField(@PathVariable UUID fieldId) {
        return metadataFieldService.deprecateField(fieldId);
    }

    @DeleteMapping("/{fieldId}")
    @PreAuthorize("@authz.hasPermission(T(com.oak.dms.security.rbac.PermissionCodes).MDTM_METADATA_SCHEMA_DELETE_FIELD)")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a metadata field",
            description = "409 if the field is still referenced by any document type mapping or document "
                    + "value - deprecate it instead (see POST /{fieldId}/deprecate).")
    public void deleteField(@PathVariable UUID fieldId) {
        metadataFieldService.deleteField(fieldId);
    }
}
