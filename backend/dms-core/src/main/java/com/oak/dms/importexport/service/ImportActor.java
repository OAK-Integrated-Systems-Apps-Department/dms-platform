package com.oak.dms.importexport.service;

import com.oak.dms.security.auth.TenantContext;

import java.util.UUID;

/**
 * Who an import runs as, captured on the request thread so the background
 * worker can act as that same user: the scan gate records them as the
 * uploader and audit events keep the real actor (and impersonator, if any).
 */
public record ImportActor(UUID tenantId, UUID userId, String displayName, UUID keycloakId,
                          UUID impersonatorId, String impersonatorDisplayName) {

    public static ImportActor current() {
        return new ImportActor(TenantContext.getTenantId(), TenantContext.getUserId(),
                TenantContext.getDisplayNameOrNull(), TenantContext.getKeycloakIdOrNull(),
                TenantContext.getImpersonatorIdOrNull(), TenantContext.getImpersonatorDisplayNameOrNull());
    }

    /** For work resumed without a request (restart, scan reconciler): the batch's creator. */
    public static ImportActor of(UUID tenantId, UUID userId) {
        return new ImportActor(tenantId, userId, null, null, null, null);
    }

    /** Binds this actor to the current thread; always pair with {@link TenantContext#clear()}. */
    public void bind() {
        TenantContext.set(tenantId, userId, displayName, keycloakId);
        if (impersonatorId != null) {
            TenantContext.setImpersonator(impersonatorId, impersonatorDisplayName);
        }
    }
}
