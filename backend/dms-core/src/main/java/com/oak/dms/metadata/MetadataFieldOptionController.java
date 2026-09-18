package com.oak.dms.metadata;

import com.oak.dms.metadata.dto.CreateMetadataFieldOptionRequest;
import com.oak.dms.metadata.dto.MetadataFieldOptionResponse;
import com.oak.dms.metadata.dto.UpdateMetadataFieldOptionRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.UUID;

/**
 * Dropdown/multi_select option lists for a single metadata field, including
 * {@code parentOptionId} for cascading lists (A4). Gated on the same
 * mdtm.manage-metadata-schema.set-validation-rules permission as validation
 * rules below - both are part of configuring a field's behavior, not the
 * field's own create/update lifecycle.
 */
@RestController
@RequestMapping("/api/v1/metadata-fields/{fieldId}/options")
@RequiredArgsConstructor
@Tag(name = "Metadata Field Options", description = "Dropdown / multi_select option lists (module 2.7)")
public class MetadataFieldOptionController {

    private final MetadataFieldOptionService optionService;

    @PostMapping
    @PreAuthorize("@authz.hasPermission(T(com.oak.dms.security.rbac.PermissionCodes).MDTM_METADATA_SCHEMA_SET_VALIDATION_RULES)")
    @Operation(summary = "Add an option to a dropdown/multi_select metadata field")
    public ResponseEntity<MetadataFieldOptionResponse> createOption(@PathVariable UUID fieldId,
            @Valid @RequestBody CreateMetadataFieldOptionRequest request) {
        MetadataFieldOptionResponse response = optionService.createOption(fieldId, request);
        return ResponseEntity.created(
                URI.create("/api/v1/metadata-fields/" + fieldId + "/options/" + response.optionId())).body(response);
    }

    @GetMapping
    @PreAuthorize("@authz.hasAnyPermission(T(com.oak.dms.security.rbac.PermissionCodes).MDTM_METADATA_SCHEMA_VIEW_FIELD, T(com.oak.dms.security.rbac.PermissionCodes).MDTM_METADATA_SCHEMA_UPDATE_FIELD, T(com.oak.dms.security.rbac.PermissionCodes).MDTM_METADATA_SCHEMA_SET_VALIDATION_RULES)")
    @Operation(summary = "List a metadata field's options, ordered by display_order")
    public List<MetadataFieldOptionResponse> listOptions(@PathVariable UUID fieldId) {
        return optionService.listOptions(fieldId);
    }

    @PutMapping("/{optionId}")
    @PreAuthorize("@authz.hasPermission(T(com.oak.dms.security.rbac.PermissionCodes).MDTM_METADATA_SCHEMA_SET_VALIDATION_RULES)")
    @Operation(summary = "Update a metadata field option")
    public MetadataFieldOptionResponse updateOption(@PathVariable UUID fieldId, @PathVariable UUID optionId,
            @Valid @RequestBody UpdateMetadataFieldOptionRequest request) {
        return optionService.updateOption(fieldId, optionId, request);
    }

    @DeleteMapping("/{optionId}")
    @PreAuthorize("@authz.hasPermission(T(com.oak.dms.security.rbac.PermissionCodes).MDTM_METADATA_SCHEMA_SET_VALIDATION_RULES)")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete (soft-delete) a metadata field option")
    public void deleteOption(@PathVariable UUID fieldId, @PathVariable UUID optionId) {
        optionService.deleteOption(fieldId, optionId);
    }
}
