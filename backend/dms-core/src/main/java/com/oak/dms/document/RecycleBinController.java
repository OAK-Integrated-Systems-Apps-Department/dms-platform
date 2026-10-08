package com.oak.dms.document;

import com.oak.dms.document.RecycleBinService.RecycleBin;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/** The recycle bin - see {@link RecycleBinService}. Permission codes from V10's RBR module. */
@RestController
@RequestMapping("/api/v1/recycle-bin")
@RequiredArgsConstructor
@Tag(name = "Recycle bin", description = "Deleted documents - restore them, or delete them for good")
public class RecycleBinController {

    private static final String P = "T(com.oak.dms.security.rbac.catalog.PermissionCodes).";

    private final RecycleBinService service;

    @GetMapping
    @PreAuthorize("@authz.hasAnyPermission(" + P + "RBR_RESTORE, " + P + "RBR_PURGE, " + P + "RBR_RETENTION)")
    @Operation(summary = "Deleted documents the caller can open, most recently deleted first, and how long they're kept")
    public RecycleBin list() {
        return service.list();
    }

    @PostMapping("/{id}/restore")
    @PreAuthorize("@authz.hasPermission(" + P + "RBR_RESTORE)")
    @Operation(summary = "Restore a deleted document to where it was")
    public ResponseEntity<Void> restore(@PathVariable("id") UUID id) {
        service.restore(id);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("@authz.hasPermission(" + P + "RBR_PURGE)")
    @Operation(summary = "Delete a document for good - its files and their keys are destroyed")
    public ResponseEntity<Void> purge(@PathVariable("id") UUID id) {
        service.purgeNow(id);
        return ResponseEntity.noContent().build();
    }

    public record RetentionRequest(@Min(1) @Max(3650) int retentionDays) {
    }

    /** @param retentionDays null = follow the organisation's period again */
    public record TypeRetentionRequest(@Min(1) @Max(3650) Integer retentionDays) {
    }

    @PutMapping("/settings/document-types/{documentTypeId}")
    @PreAuthorize("@authz.hasPermission(" + P + "RBR_RETENTION)")
    @Operation(summary = "How many days deleted documents of one type are kept - null to follow the organisation's")
    public ResponseEntity<Void> setTypeRetention(@PathVariable("documentTypeId") UUID documentTypeId,
                                                 @Valid @RequestBody TypeRetentionRequest request) {
        service.setTypeRetentionDays(documentTypeId, request.retentionDays());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/settings")
    @PreAuthorize("@authz.hasPermission(" + P + "RBR_RETENTION)")
    @Operation(summary = "How many days deleted documents are kept before they're purged")
    public Map<String, Integer> setRetention(@Valid @RequestBody RetentionRequest request) {
        return Map.of("retentionDays", service.setRetentionDays(request.retentionDays()));
    }
}
