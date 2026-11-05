package com.oak.dms.encryption;

import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.catalog.AuditObjectTypes;
import com.oak.dms.audit.enums.AuditOutcome;
import com.oak.dms.audit.service.AuditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Crypto-shredding a removed organisation (DE-FR-005): once a platform admin
 * has deactivated a tenant, this makes everything it stored encrypted
 * unreadable for good - wherever the bytes are, including backups and copies
 * of the storage:
 * <ol>
 *   <li>its wrapped data keys are overwritten in storage_object_keys (the
 *       only place they exist), each row kept as a tombstone (V60);</li>
 *   <li>its master key is destroyed in every key service that holds one
 *       (OpenBao Transit, or the development key file and its backup) - which
 *       also covers database backups that still hold the wrapped keys;</li>
 *   <li>tenants.keys_shredded_at records it (V59).</li>
 * </ol>
 * Irreversible, so it takes the tenant's exact name and a reason, and only
 * runs on a deactivated tenant. Files stored before encryption aren't
 * protected by the key; the request must acknowledge them explicitly. A
 * failure part-way (a key service unreachable) can simply be retried.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TenantKeyShredder {

    private final JdbcTemplate jdbc;
    private final KeyServices keys;
    private final EncryptionAdminService admin;
    private final DataKeyCache cache;
    private final AuditService auditService;

    /**
     * @param destroyedKeys        "provider:scope" of every master key destroyed
     * @param unencryptedFilesLeft files stored before encryption - still readable to whoever has the storage
     */
    public record ShredResult(UUID tenantId, OffsetDateTime shreddedAt, long wrappedKeysDeleted,
                              List<String> destroyedKeys, long unencryptedFilesLeft) {
    }

    public ShredResult shred(UUID tenantId, String confirmTenantName, String reason, boolean acknowledgeUnencrypted) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select tenant_name, is_deleted, keys_shredded_at from tenants where tenant_id = ?", tenantId);
        if (rows.isEmpty()) {
            throw new EncryptionException(HttpStatus.NOT_FOUND, "TENANT_NOT_FOUND", "There is no such organisation.");
        }
        Map<String, Object> tenant = rows.get(0);
        String name = (String) tenant.get("tenant_name");
        if (tenant.get("keys_shredded_at") != null) {
            throw new EncryptionException(HttpStatus.CONFLICT, "KEYS_ALREADY_SHREDDED",
                    "This organisation's keys were already destroyed.");
        }
        if (!Boolean.TRUE.equals(tenant.get("is_deleted"))) {
            throw new EncryptionException(HttpStatus.CONFLICT, "TENANT_STILL_ACTIVE",
                    "Deactivate the organisation first - its keys can only be destroyed once it is removed.");
        }
        // Shredding destroys every document of the organisation for good - including one under legal hold, which no one may delete
        // until the hold is lifted (6.1, CRM-FR-016). A platform administrator is no exception.
        Long held = jdbc.queryForObject("select count(*) from documents where tenant_id = ? and legal_hold and purged_at is null",
                Long.class, tenantId);
        if (held != null && held > 0) {
            throw new EncryptionException(HttpStatus.CONFLICT, "LEGAL_HOLD_ACTIVE", held + (held == 1 ? " document is" : " documents are")
                    + " under legal hold, so this organisation's keys can't be destroyed - that would destroy "
                    + (held == 1 ? "it" : "them") + " for good. Lift the hold" + (held == 1 ? "" : "s") + " first.");
        }
        if (confirmTenantName == null || !confirmTenantName.trim().equals(name)) {
            throw new EncryptionException(HttpStatus.BAD_REQUEST, "CONFIRMATION_MISMATCH",
                    "Type the organisation's name exactly to confirm.");
        }
        if (reason == null || reason.isBlank()) {
            throw new EncryptionException(HttpStatus.BAD_REQUEST, "REASON_REQUIRED",
                    "Say why the keys are being destroyed - it is kept in the audit log.");
        }
        long unencrypted = admin.unencryptedFiles(tenantId);
        if (unencrypted > 0 && !acknowledgeUnencrypted) {
            throw new EncryptionException(HttpStatus.CONFLICT, "UNENCRYPTED_FILES_LEFT", unencrypted
                    + " of this organisation's files were stored before encryption and stay readable to anyone"
                    + " with the storage. Delete them from storage, or confirm that you know.");
        }

        String scope = KeyEncryptionService.scopeOf(tenantId);
        Set<String> providers = new LinkedHashSet<>(jdbc.queryForList(
                "select distinct key_provider from storage_object_keys where (tenant_id = ? or key_scope = ?)",
                String.class, tenantId, scope));
        providers.add(keys.primary().provider());

        // 1. The wrapped data keys - after this nothing in this database can open the files. Each row
        //    stays as a tombstone (V60), so a read fails plainly instead of serving the ciphertext.
        int deleted = jdbc.update("update storage_object_keys set wrapped_key = 'shredded', shredded_at = now() "
                + "where (tenant_id = ? or key_scope = ?) and shredded_at is null", tenantId, scope);
        cache.clear();

        // 2. The master keys - after this no copy of the wrapped keys (a database backup) can either.
        List<String> destroyed = new ArrayList<>();
        for (String provider : providers) {
            try {
                keys.forProvider(provider).destroy(scope);
                destroyed.add(provider + ":" + scope);
            } catch (RuntimeException e) {
                audit(tenantId, name, reason, AuditOutcome.FAILURE, deleted, destroyed, unencrypted, e.getMessage());
                throw new EncryptionException(HttpStatus.BAD_GATEWAY, "KEY_SERVICE_UNAVAILABLE",
                        "The data keys were deleted, but the '" + provider + "' master key could not be destroyed ("
                                + e.getMessage() + "). Try again once the key service is reachable.");
            }
        }

        jdbc.update("update tenants set keys_shredded_at = now() where tenant_id = ?", tenantId);
        log.warn("Crypto-shredded tenant {}: {} wrapped keys deleted, master keys destroyed: {}", tenantId, deleted,
                destroyed);
        audit(tenantId, name, reason, AuditOutcome.SUCCESS, deleted, destroyed, unencrypted, null);
        OffsetDateTime at = time(jdbc.queryForObject("select keys_shredded_at from tenants where tenant_id = ?",
                Object.class, tenantId));
        return new ShredResult(tenantId, at, deleted, destroyed, unencrypted);
    }

    private void audit(UUID tenantId, String name, String reason, AuditOutcome outcome, long deleted,
                       List<String> destroyed, long unencrypted, String failure) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("wrappedKeysDeleted", deleted);
        details.put("masterKeysDestroyed", List.copyOf(destroyed));
        details.put("unencryptedFilesLeft", unencrypted);
        // A platform admin's action on a removed tenant - platform level, like TENANT_DEACTIVATED.
        auditService.event(AuditActionCatalog.ENCRYPTION_TENANT_KEYS_SHREDDED)
                .platformLevel()
                .objectType(AuditObjectTypes.TENANT)
                .objectId(tenantId)
                .resourceName(name)
                .outcome(outcome)
                .failureReason(failure)
                .reason(reason)
                .newValue(details)
                .log();
    }

    private static OffsetDateTime time(Object value) {
        if (value instanceof OffsetDateTime odt) {
            return odt;
        }
        if (value instanceof Timestamp ts) {
            return ts.toInstant().atOffset(ZoneOffset.UTC);
        }
        return null;
    }
}
