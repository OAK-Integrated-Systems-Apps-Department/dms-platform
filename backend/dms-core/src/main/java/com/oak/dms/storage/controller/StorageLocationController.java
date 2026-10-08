package com.oak.dms.storage.controller;

import com.oak.dms.storage.dto.CreateStorageLocationRequest;
import com.oak.dms.storage.dto.StorageLocationResponse;
import com.oak.dms.storage.service.StorageLocationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.UUID;

/**
 * Registering a tenant's own object-storage backend (their AWS S3, their
 * MinIO, their SeaweedFS gateway, an on-prem filesystem mount, ...) is a
 * tenant-infrastructure concern, gated the same way {@code TenantController}
 * gates tenant-level administration - not a per-document {@code DOCM}
 * permission. Deliberately create/get/list only - see
 * {@link StorageLocationService}'s own doc comment.
 */
@RestController
@RequestMapping("/api/v1/storage-locations")
@RequiredArgsConstructor
@Tag(name = "Storage Locations", description = "Tenant-configurable object storage destinations")
public class StorageLocationController {

    private final StorageLocationService storageLocationService;

    @PostMapping
    @PreAuthorize("@authz.isTenantAdmin() or hasRole('PLATFORM_ADMIN')")
    @Operation(summary = "Register a new storage location for the caller's tenant")
    public ResponseEntity<StorageLocationResponse> createLocation(@Valid @RequestBody CreateStorageLocationRequest request) {
        StorageLocationResponse response = storageLocationService.createLocation(request);
        return ResponseEntity.created(URI.create("/api/v1/storage-locations/" + response.storageLocationId()))
                .body(response);
    }

    @GetMapping("/{id}")
    @PreAuthorize("@authz.isTenantAdmin() or hasRole('PLATFORM_ADMIN')")
    @Operation(summary = "Get a storage location by id")
    public StorageLocationResponse getLocation(@PathVariable("id") UUID id) {
        return storageLocationService.getLocation(id);
    }

    @GetMapping
    @PreAuthorize("@authz.isTenantAdmin() or hasRole('PLATFORM_ADMIN')")
    @Operation(summary = "List the caller's tenant's storage locations")
    public List<StorageLocationResponse> listLocations() {
        return storageLocationService.listLocations();
    }
}
