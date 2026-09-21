package com.oak.dms.metadata;

import com.oak.dms.metadata.dto.DocumentTypeFieldResponse;
import com.oak.dms.metadata.dto.DocumentTypeSchemaResponse;
import com.oak.dms.metadata.dto.ReplaceDocumentTypeFieldsRequest;
import com.oak.dms.metadata.dto.ValidateMetadataRequest;
import com.oak.dms.metadata.dto.ValidationError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
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
    private final MetadataValidationService metadataValidationService;

    @GetMapping("/fields")
    @PreAuthorize("@authz.hasAnyPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).MDTM_TYPE_FIELD_MAPPING_VIEW, T(com.oak.dms.security.rbac.catalog.PermissionCodes).MDTM_TYPE_FIELD_MAPPING_UPDATE)")
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
    @PreAuthorize("@authz.hasPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).MDTM_TYPE_FIELD_MAPPING_UPDATE)")
    @Operation(summary = "Replace a document type's field mapping, publishing a new schema version",
            description = "The given field list becomes schema_version = current + 1; the prior version's "
                    + "rows are archived, not deleted. A field newly marked mandatory on this publish gets "
                    + "requires_backfill = true so legacy documents can be flagged rather than rejected.")
    public List<DocumentTypeFieldResponse> replaceFields(@PathVariable UUID documentTypeId,
                                                           @Valid @RequestBody ReplaceDocumentTypeFieldsRequest request) {
        return documentTypeFieldService.replaceMapping(documentTypeId, request);
    }

    @GetMapping("/schema")
    @PreAuthorize("@authz.hasAnyPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).MDTM_TYPE_FIELD_MAPPING_VIEW, T(com.oak.dms.security.rbac.catalog.PermissionCodes).MDTM_TYPE_FIELD_MAPPING_UPDATE)")
    @Operation(summary = "Get the resolved form schema for a document type",
            description = "Fields, labels, types, mandatory flags, options, order, and validation rules in "
                    + "one payload (MDTM-FR-006) - for the upload form and for reuse by import, OCR, search "
                    + "and workflow once those modules exist.")
    public DocumentTypeSchemaResponse getSchema(@PathVariable UUID documentTypeId) {
        return documentTypeFieldService.getSchema(documentTypeId);
    }

    @PostMapping("/validate")
    @PreAuthorize("@authz.hasAnyPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).MDTM_TYPE_FIELD_MAPPING_VIEW, T(com.oak.dms.security.rbac.catalog.PermissionCodes).MDTM_TYPE_FIELD_MAPPING_UPDATE)")
    @Operation(
            summary = "Validate a set of candidate field values against a document type's schema",
            description = "Runs the supplied values through every mandatory-field check, "
                    + "option-backed-dropdown check, and per-field validation rule (min_length, "
                    + "max_length, min_value, max_value, regex, date_format, date_range, "
                    + "allowed_values) defined for the resolved schema, without persisting "
                    + "anything. schemaVersion is optional - when omitted, the document type's "
                    + "current version is used. An empty response array means every supplied "
                    + "value passed; it does NOT by itself confirm the document type exists when "
                    + "schemaVersion is explicit (see 404 response note).",
            responses = {
                    @ApiResponse(responseCode = "200",
                            description = "Validation ran; body is zero or more errors/warnings. Empty array = all values valid.",
                            content = @Content(mediaType = "application/json", examples = {
                                    @ExampleObject(name = "No errors", value = "[]"),
                                    @ExampleObject(name = "With errors", value = """
                                            [
                                              {
                                                "fieldKey": "contract_start_date",
                                                "errorCode": "MANDATORY_FIELD_MISSING",
                                                "message": "Field 'Contract Start Date' is required.",
                                                "severity": "error"
                                              },
                                              {
                                                "fieldKey": "contract_value",
                                                "errorCode": "MIN_VALUE",
                                                "message": "Field 'Contract Value' must be at least 1000.0.",
                                                "severity": "error"
                                              }
                                            ]""")
                            })),
                    @ApiResponse(responseCode = "400", description = "Malformed request body, e.g. values exceeds the 200-entry cap"),
                    @ApiResponse(responseCode = "404",
                            description = "documentTypeId not found - only guaranteed when schemaVersion is omitted; "
                                    + "an explicit schemaVersion against an unknown documentTypeId is not currently "
                                    + "detected and returns 200 with an empty array instead")
            }
    )
    public List<ValidationError> validateMetadata(@PathVariable UUID documentTypeId,
            @Valid @RequestBody ValidateMetadataRequest request) {
        return metadataValidationService.validate(documentTypeId, request.schemaVersion(), request.values());
    }
}
