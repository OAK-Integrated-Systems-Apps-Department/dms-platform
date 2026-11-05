package com.oak.dms.encryption;

import com.oak.dms.encryption.TenantKeyShredder.ShredResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Crypto-shredding a deactivated organisation's keys - platform admins only. See {@link TenantKeyShredder}.
 * <p>
 * FOLLOW-UP (found 2026-10-02): the frontend has no platform tenant-management
 * screen at all (no list, deactivate or shred), so today this is called
 * through the API (docs/storage-architecture.md, section 8a). A platform admin
 * page should list tenants, deactivate one, and offer "Destroy keys" with the
 * typed-name confirmation, the reason and the unencrypted-files warning.
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/shred-keys")
@RequiredArgsConstructor
@Tag(name = "Encryption", description = "Encryption at rest - key status, rotation, re-wrapping")
public class TenantKeyShredController {

    private final TenantKeyShredder shredder;
    private final com.oak.dms.security.auth.PasswordConfirmation passwordConfirmation;

    /**
     * @param confirmTenantName       the organisation's exact name
     * @param reason                  kept in the audit log
     * @param acknowledgeUnencrypted  required when files stored before encryption remain
     */
    public record ShredRequest(@NotBlank String confirmTenantName, @NotBlank @Size(max = 1000) String reason,
                               boolean acknowledgeUnencrypted) {
    }

    @PostMapping
    @PreAuthorize("hasRole('PLATFORM_ADMIN')")
    @Operation(summary = "Destroy a deactivated organisation's encryption keys - its documents become unreadable"
            + " for good")
    public ShredResult shred(@PathVariable UUID tenantId, @Valid @RequestBody ShredRequest request,
                             jakarta.servlet.http.HttpServletRequest http) {
        // The most destructive thing a platform administrator can do: confirm with their own password first.
        passwordConfirmation.require(http);
        return shredder.shred(tenantId, request.confirmTenantName(), request.reason(),
                request.acknowledgeUnencrypted());
    }
}
