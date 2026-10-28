package com.oak.dms.encryption;

import com.oak.dms.encryption.EncryptionAdminService.EncryptionStatus;
import com.oak.dms.encryption.EncryptionAdminService.JobStatus;
import com.oak.dms.encryption.KeyEncryptionService.KeyInfo;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Key management for encryption at rest (Module DE) - see
 * {@link EncryptionAdminService}. Gated by the de.manage-encryption-keys.*
 * codes (V10): view-key-metadata to look, rotate-key to rotate and
 * re-wrap, generate-key to encrypt documents stored before encryption.
 */
@RestController
@RequestMapping("/api/v1/encryption")
@RequiredArgsConstructor
@Tag(name = "Encryption", description = "Encryption at rest - key status, rotation, re-wrapping")
public class EncryptionController {

    private static final String P = "T(com.oak.dms.security.rbac.catalog.PermissionCodes).";

    private final EncryptionAdminService service;

    @GetMapping("/status")
    @PreAuthorize("@authz.hasAnyPermission(" + P + "DE_KEYS_VIEW, " + P + "DE_KEYS_ROTATE, " + P + "DE_KEYS_GENERATE)")
    @Operation(summary = "The master key, objects per key version, documents not yet encrypted, and job progress")
    public EncryptionStatus status() {
        return service.status();
    }

    @PostMapping("/rotate")
    @PreAuthorize("@authz.hasPermission(" + P + "DE_KEYS_ROTATE)")
    @Operation(summary = "Rotate the master key - new objects use the new version at once")
    public KeyInfo rotate() {
        return service.rotate();
    }

    @PostMapping("/rewrap")
    @PreAuthorize("@authz.hasPermission(" + P + "DE_KEYS_ROTATE)")
    @Operation(summary = "Re-wrap every older data key with the latest master-key version (background)")
    public ResponseEntity<JobStatus> rewrap() {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(service.startRewrap());
    }

    @PostMapping("/encrypt-existing")
    @PreAuthorize("@authz.hasPermission(" + P + "DE_KEYS_GENERATE)")
    @Operation(summary = "Encrypt documents stored before encryption was turned on (background)")
    public ResponseEntity<JobStatus> encryptExisting() {
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(service.startEncryptExisting());
    }

    /**
     * @param intervalDays null turns the schedule off; otherwise 30 to 3650 days
     * @param autoRotate   rotate (and re-wrap) by itself when due, instead of only reminding
     */
    public record RotationPolicyRequest(@Min(30) @Max(3650) Integer intervalDays, boolean autoRotate) {
    }

    @PutMapping("/rotation-policy")
    @PreAuthorize("@authz.hasPermission(" + P + "DE_KEYS_ROTATE)")
    @Operation(summary = "How often the master key should be rotated, and whether it happens by itself")
    public KeyRotationPolicyStore.RotationPolicy saveRotationPolicy(@Valid @RequestBody RotationPolicyRequest request) {
        return service.saveRotationPolicy(request.intervalDays(), request.autoRotate());
    }
}
