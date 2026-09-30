package com.oak.dms.scanning.controller;

import com.oak.dms.common.dto.PageResponse;
import com.oak.dms.scanning.dto.QuarantineActionRequest;
import com.oak.dms.scanning.dto.QuarantineRecordDetailResponse;
import com.oak.dms.scanning.dto.QuarantineRecordResponse;
import com.oak.dms.scanning.dto.QuarantineReleaseResponse;
import com.oak.dms.scanning.enums.QuarantineStatus;
import com.oak.dms.scanning.service.QuarantineService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Administrator review of quarantined files (VMSBDI FR-005, alternate flow 3).
 * Tenant-scoped - another tenant's record 404s. There is deliberately no
 * download, presigned URL or preview of the quarantined bytes, for anyone.
 * Every action takes a mandatory reason, recorded in the audit trail.
 */
@RestController
@RequestMapping("/api/v1/quarantine")
@RequiredArgsConstructor
@Tag(name = "Quarantine", description = "Review, release and delete files quarantined by the malware scan")
public class QuarantineController {

    private final QuarantineService quarantineService;

    @GetMapping
    @PreAuthorize("@authz.hasAnyPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).DOCM_QUARANTINE_VIEW, T(com.oak.dms.security.rbac.catalog.PermissionCodes).DOCM_QUARANTINE_RELEASE, T(com.oak.dms.security.rbac.catalog.PermissionCodes).DOCM_QUARANTINE_DELETE, T(com.oak.dms.security.rbac.catalog.PermissionCodes).DOCM_QUARANTINE_OVERRIDE)")
    @Operation(summary = "List quarantined files in the caller's tenant",
            description = "All filters optional and combined with AND. detectedFrom/detectedTo bound the detection "
                    + "time (inclusive, ISO-8601). search matches the filename or threat name. Newest first by default.")
    public PageResponse<QuarantineRecordResponse> list(
            @RequestParam(required = false) QuarantineStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime detectedFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime detectedTo,
            @RequestParam(required = false) String search,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return PageResponse.from(quarantineService.list(status, detectedFrom, detectedTo, search, pageable));
    }

    @GetMapping("/{id}")
    @PreAuthorize("@authz.hasAnyPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).DOCM_QUARANTINE_VIEW, T(com.oak.dms.security.rbac.catalog.PermissionCodes).DOCM_QUARANTINE_RELEASE, T(com.oak.dms.security.rbac.catalog.PermissionCodes).DOCM_QUARANTINE_DELETE, T(com.oak.dms.security.rbac.catalog.PermissionCodes).DOCM_QUARANTINE_OVERRIDE)")
    @Operation(summary = "Get a quarantined file's record, the scan that caught it, and its release history")
    public QuarantineRecordDetailResponse get(@PathVariable("id") UUID id) {
        return quarantineService.get(id);
    }

    @PostMapping("/{id}/release")
    @PreAuthorize("@authz.hasPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).DOCM_QUARANTINE_RELEASE)")
    @Operation(summary = "Release a false positive - re-scans the file and releases it only if it now comes back clean",
            description = "200 with outcome released/refused/failed; 202 with outcome pending while a cloud re-scan runs "
                    + "(poll GET /{id}). 409 if the file is not held or a release is already in progress.")
    public ResponseEntity<QuarantineReleaseResponse> release(@PathVariable("id") UUID id,
                                                             @Valid @RequestBody QuarantineActionRequest request) {
        QuarantineReleaseResponse response = quarantineService.release(id, request.reason());
        HttpStatus status = "pending".equals(response.outcome()) ? HttpStatus.ACCEPTED : HttpStatus.OK;
        return ResponseEntity.status(status).body(response);
    }

    @PostMapping("/{id}/override-release")
    @PreAuthorize("@authz.hasPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).DOCM_QUARANTINE_OVERRIDE)")
    @Operation(summary = "Force-release a file the scanner still flags (administrator override)",
            description = "Only after a re-scan release was refused. Deliberately bypasses the scan verdict; "
                    + "recorded as a HIGH-severity audit event with the mandatory reason. 409 otherwise.")
    public QuarantineReleaseResponse overrideRelease(@PathVariable("id") UUID id,
                                                     @Valid @RequestBody QuarantineActionRequest request) {
        return quarantineService.overrideRelease(id, request.reason());
    }

    @PostMapping("/{id}/delete")
    @PreAuthorize("@authz.hasPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).DOCM_QUARANTINE_DELETE)")
    @Operation(summary = "Permanently delete a quarantined file",
            description = "Removes the object from quarantine storage and marks the record deleted; the record itself "
                    + "is kept. A POST action rather than DELETE-with-body, since the reason is mandatory.")
    public ResponseEntity<Void> delete(@PathVariable("id") UUID id, @Valid @RequestBody QuarantineActionRequest request) {
        quarantineService.delete(id, request.reason());
        return ResponseEntity.noContent().build();
    }
}
