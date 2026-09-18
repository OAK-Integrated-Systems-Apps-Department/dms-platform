package com.oak.dms.metadata;

import com.oak.dms.metadata.dto.CreateMetadataFieldValidationRequest;
import com.oak.dms.metadata.dto.MetadataFieldValidationResponse;
import com.oak.dms.metadata.dto.UpdateMetadataFieldValidationRequest;
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

@RestController
@RequestMapping("/api/v1/metadata-fields/{fieldId}/validations")
@RequiredArgsConstructor
@Tag(name = "Metadata Field Validations", description = "Per-field validation rules (module 2.7)")
public class MetadataFieldValidationController {

    private final MetadataFieldValidationService validationService;

    @PostMapping
    @PreAuthorize("@authz.hasPermission(T(com.oak.dms.security.rbac.PermissionCodes).MDTM_METADATA_SCHEMA_SET_VALIDATION_RULES)")
    @Operation(summary = "Add a validation rule to a metadata field")
    public ResponseEntity<MetadataFieldValidationResponse> createValidation(@PathVariable UUID fieldId,
            @Valid @RequestBody CreateMetadataFieldValidationRequest request) {
        MetadataFieldValidationResponse response = validationService.createValidation(fieldId, request);
        return ResponseEntity.created(
                URI.create("/api/v1/metadata-fields/" + fieldId + "/validations/" + response.validationId())).body(response);
    }

    @GetMapping
    @PreAuthorize("@authz.hasAnyPermission(T(com.oak.dms.security.rbac.PermissionCodes).MDTM_METADATA_SCHEMA_VIEW_FIELD, T(com.oak.dms.security.rbac.PermissionCodes).MDTM_METADATA_SCHEMA_UPDATE_FIELD, T(com.oak.dms.security.rbac.PermissionCodes).MDTM_METADATA_SCHEMA_SET_VALIDATION_RULES)")
    @Operation(summary = "List a metadata field's validation rules")
    public List<MetadataFieldValidationResponse> listValidations(@PathVariable UUID fieldId) {
        return validationService.listValidations(fieldId);
    }

    @PutMapping("/{validationId}")
    @PreAuthorize("@authz.hasPermission(T(com.oak.dms.security.rbac.PermissionCodes).MDTM_METADATA_SCHEMA_SET_VALIDATION_RULES)")
    @Operation(summary = "Update a metadata field validation rule")
    public MetadataFieldValidationResponse updateValidation(@PathVariable UUID fieldId, @PathVariable UUID validationId,
            @Valid @RequestBody UpdateMetadataFieldValidationRequest request) {
        return validationService.updateValidation(fieldId, validationId, request);
    }

    @DeleteMapping("/{validationId}")
    @PreAuthorize("@authz.hasPermission(T(com.oak.dms.security.rbac.PermissionCodes).MDTM_METADATA_SCHEMA_SET_VALIDATION_RULES)")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete (soft-delete) a metadata field validation rule")
    public void deleteValidation(@PathVariable UUID fieldId, @PathVariable UUID validationId) {
        validationService.deleteValidation(fieldId, validationId);
    }
}
