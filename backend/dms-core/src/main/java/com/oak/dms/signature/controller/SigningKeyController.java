package com.oak.dms.signature.controller;

import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.signature.dto.RevokeSigningKeyRequest;
import com.oak.dms.signature.service.SigningKeyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Revoking a user's active signing key - gated behind the same
 * {@code DSI_MANAGE_CERTIFICATES} permission as the rest of signature-
 * configuration management. Revoking never invalidates a signature the key
 * already made (decision 9); it only stops it from signing anything new.
 * Also called automatically when a user is deactivated or permanently
 * deleted - see {@code UserServiceImpl}.
 */
@RestController
@RequestMapping("/api/v1/signing-keys")
@ConditionalOnProperty(prefix = "dms.signature", name = "enabled", havingValue = "true")
@RequiredArgsConstructor
@Tag(name = "Signing keys", description = "Revoke a user's digital-signature key")
public class SigningKeyController {

    private final SigningKeyService signingKeyService;

    @PostMapping("/{userId}/revoke")
    @PreAuthorize("@authz.hasPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).DSI_MANAGE_CERTIFICATES)")
    @Operation(summary = "Revoke a user's active signing key (reason required)",
            description = "The next signature they make gets a new key (next generation) - this one never signs "
                    + "again, but every signature it already made stays valid for the version it signed.")
    public void revoke(@PathVariable UUID userId, @Valid @RequestBody RevokeSigningKeyRequest request) {
        signingKeyService.revoke(TenantContext.getTenantId(), userId, request.reason(), TenantContext.getUserId());
    }
}
