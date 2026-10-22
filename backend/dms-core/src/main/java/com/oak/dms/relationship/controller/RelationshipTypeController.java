package com.oak.dms.relationship.controller;

import com.oak.dms.relationship.dto.CreateRelationshipTypeRequest;
import com.oak.dms.relationship.dto.RelationshipTypeResponse;
import com.oak.dms.relationship.dto.UpdateRelationshipTypeRequest;
import com.oak.dms.relationship.service.RelationshipTypeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Relationship types (2.8): the add-link dialog reads the enabled ones, the admin screen manages them. */
@RestController
@RequestMapping("/api/v1/relationship-types")
@RequiredArgsConstructor
@Tag(name = "Relationship types")
public class RelationshipTypeController {

    private static final String P = "T(com.oak.dms.security.rbac.catalog.PermissionCodes).";
    private static final String CAN_READ = "@authz.hasAnyPermission(" + P + "DRM_RELATIONSHIPS_CREATE, " + P + "DRM_TYPES_CREATE, "
            + P + "DRM_TYPES_UPDATE, " + P + "DRM_TYPES_DELETE)";

    private final RelationshipTypeService service;

    @GetMapping
    @PreAuthorize(CAN_READ)
    @Operation(summary = "Relationship types",
            description = "Enabled types by default. includeDisabled=true adds disabled ones and the usage count, and needs a configure-types permission.")
    public List<RelationshipTypeResponse> list(@RequestParam(defaultValue = "false") boolean includeDisabled) {
        return service.list(includeDisabled);
    }

    @PostMapping
    @PreAuthorize("@authz.hasPermission(" + P + "DRM_TYPES_CREATE)")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a custom relationship type", description = "409 RELATIONSHIP_TYPE_CODE_TAKEN if the label is already used.")
    public RelationshipTypeResponse create(@Valid @RequestBody CreateRelationshipTypeRequest request) {
        return service.create(request);
    }

    @PutMapping("/{typeId}")
    @PreAuthorize("@authz.hasPermission(" + P + "DRM_TYPES_UPDATE)")
    @Operation(summary = "Rename, relabel, reorder, enable or disable a type",
            description = "Disabling never touches existing links. A built-in type keeps its direction and loop rule.")
    public RelationshipTypeResponse update(@PathVariable("typeId") UUID typeId,
                                           @Valid @RequestBody UpdateRelationshipTypeRequest request) {
        return service.update(typeId, request);
    }

    @DeleteMapping("/{typeId}")
    @PreAuthorize("@authz.hasPermission(" + P + "DRM_TYPES_DELETE)")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete an unused custom type",
            description = "409 RELATIONSHIP_TYPE_SYSTEM for a built-in type, 409 RELATIONSHIP_TYPE_IN_USE once it has been used.")
    public void delete(@PathVariable("typeId") UUID typeId) {
        service.delete(typeId);
    }
}
