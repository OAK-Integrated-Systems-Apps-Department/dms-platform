package com.oak.dms.relationship.controller;

import com.oak.dms.relationship.dto.BulkCreateRelationshipsRequest;
import com.oak.dms.relationship.dto.BulkRelationshipsResponse;
import com.oak.dms.relationship.dto.CreateRelationshipRequest;
import com.oak.dms.relationship.dto.RelatedDocumentsResponse;
import com.oak.dms.relationship.dto.RelationshipCountsRequest;
import com.oak.dms.relationship.dto.RelationshipCountsResponse;
import com.oak.dms.relationship.dto.RelationshipResponse;
import com.oak.dms.relationship.dto.UpdateRelationshipRequest;
import com.oak.dms.relationship.service.RelationshipPanelService;
import com.oak.dms.relationship.service.RelationshipService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Document relationships (2.8). Reading the panel needs only normal view access to the document;
 * creating or editing a link needs the DRM create permission plus edit access on the source and
 * view access on the target; removing needs the DRM remove permission plus edit access on the source.
 * A document the caller can't open answers 404, as everywhere else.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "Document relationships")
public class RelationshipController {

    private static final String P = "T(com.oak.dms.security.rbac.catalog.PermissionCodes).";
    private static final String VIEW = "@authz.hasPermission(" + P + "DOCM_DOCUMENTS_VIEW)";
    private static final String CREATE = "@authz.hasPermission(" + P + "DRM_RELATIONSHIPS_CREATE)";
    private static final String REMOVE = "@authz.hasPermission(" + P + "DRM_RELATIONSHIPS_REMOVE)";

    private final RelationshipService service;
    private final RelationshipPanelService panelService;

    @GetMapping("/documents/{documentId}/relationships")
    @PreAuthorize(VIEW)
    @Operation(summary = "Related Documents panel", description = "Links grouped by type, read from this document's side. "
            + "A document the caller can't open appears only as {accessible: false}.")
    public RelatedDocumentsResponse panel(@PathVariable("documentId") UUID documentId) {
        return panelService.panel(documentId);
    }

    @PostMapping("/document-relationships/counts")
    @PreAuthorize(VIEW)
    @Operation(summary = "Link counts for up to 200 documents", description = "Documents the caller can't open are left out.")
    public RelationshipCountsResponse counts(@Valid @RequestBody RelationshipCountsRequest request) {
        return panelService.counts(request.documentIds());
    }

    @PostMapping("/document-relationships")
    @PreAuthorize(CREATE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Link two documents",
            description = "409 RELATIONSHIP_DUPLICATE, RELATIONSHIP_CYCLE (details.chain names the loop) or RELATIONSHIP_TYPE_DISABLED; "
                    + "422 SELF_LINK / TEMPLATE; 404 for a document the caller can't open.")
    public RelationshipResponse create(@Valid @RequestBody CreateRelationshipRequest request) {
        return service.create(request);
    }

    @PostMapping("/document-relationships/bulk")
    @PreAuthorize(CREATE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Link several documents with one type, all or nothing",
            description = "422 RELATIONSHIP_BULK_INVALID lists each invalid document in details; nothing is saved then.")
    public BulkRelationshipsResponse createBulk(@Valid @RequestBody BulkCreateRelationshipsRequest request) {
        return service.createBulk(request);
    }

    @PatchMapping("/document-relationships/{relationshipId}")
    @PreAuthorize(CREATE)
    @Operation(summary = "Change a link's type or note", description = "The type may only change to one with the same direction.")
    public RelationshipResponse update(@PathVariable("relationshipId") UUID relationshipId,
                                       @Valid @RequestBody UpdateRelationshipRequest request) {
        return service.update(relationshipId, request);
    }

    @DeleteMapping("/document-relationships/{relationshipId}")
    @PreAuthorize(REMOVE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Remove a link", description = "Removes it from both documents; the audit trail keeps it.")
    public void remove(@PathVariable("relationshipId") UUID relationshipId) {
        service.remove(relationshipId);
    }
}
