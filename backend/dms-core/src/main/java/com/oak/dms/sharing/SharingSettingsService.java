package com.oak.dms.sharing;

import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.catalog.AuditObjectTypes;
import com.oak.dms.audit.enums.AuditOutcome;
import com.oak.dms.audit.service.AuditService;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.sharing.SharingDtos.Settings;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

/** The organisation's external-sharing policy (precondition 1.5.4; expiry, lockout and reclassification rules). */
@Service
@RequiredArgsConstructor
public class SharingSettingsService {

    private final TenantSharingSettingsRepository repository;
    private final AuditService auditService;

    /** The tenant's policy, or the defaults (sharing off) when none was ever saved. */
    @Transactional(readOnly = true)
    public TenantSharingSettings forTenant(UUID tenantId) {
        return repository.findById(tenantId).orElseGet(() -> TenantSharingSettings.defaults(tenantId));
    }

    @Transactional(readOnly = true)
    public Settings get() {
        return view(forTenant(TenantContext.getTenantId()));
    }

    /** Tenant-admin only (the controller checks). Validated here so every caller gets the same rules. */
    @Transactional
    public Settings update(Settings request) {
        UUID tenantId = TenantContext.getTenantId();
        validate(request);
        Settings previous = view(forTenant(tenantId));
        TenantSharingSettings row = repository.findById(tenantId)
                .orElseGet(() -> TenantSharingSettings.defaults(tenantId));
        row.setExternalSharingEnabled(request.externalSharingEnabled());
        row.setDefaultExpiryDays(request.defaultExpiryDays());
        row.setMaxExpiryDays(request.maxExpiryDays());
        row.setMaxPasswordAttempts(request.maxPasswordAttempts());
        row.setLockoutMinutes(request.lockoutMinutes());
        row.setGuestInactivityDays(request.guestInactivityDays());
        row.setOnReclassify(request.onReclassify());
        row.setUpdatedBy(TenantContext.getUserId());
        row.setUpdatedAt(OffsetDateTime.now());
        repository.save(row);
        auditService.event(AuditActionCatalog.SHARING_SETTINGS_CHANGED)
                .objectType(AuditObjectTypes.TENANT).objectId(tenantId)
                .outcome(AuditOutcome.SUCCESS)
                .previousValue(previous).newValue(request)
                .log();
        return view(row);
    }

    static Settings view(TenantSharingSettings s) {
        return new Settings(s.isExternalSharingEnabled(), s.getDefaultExpiryDays(), s.getMaxExpiryDays(),
                s.getMaxPasswordAttempts(), s.getLockoutMinutes(), s.getGuestInactivityDays(), s.getOnReclassify());
    }

    private static void validate(Settings s) {
        require(between(s.defaultExpiryDays(), 1, 365), "The default expiry must be 1 to 365 days.");
        require(between(s.maxExpiryDays(), 1, 365), "The longest expiry must be 1 to 365 days.");
        require(s.defaultExpiryDays() <= s.maxExpiryDays(),
                "The default expiry can't be longer than the longest allowed.");
        require(between(s.maxPasswordAttempts(), 3, 20), "Password attempts must be between 3 and 20.");
        require(between(s.lockoutMinutes(), 1, 1440), "The lock-out must be 1 to 1440 minutes.");
        require(between(s.guestInactivityDays(), 7, 730), "Guest inactivity must be 7 to 730 days.");
        require(TenantSharingSettings.ON_RECLASSIFY_FLAG.equals(s.onReclassify())
                        || TenantSharingSettings.ON_RECLASSIFY_REVOKE.equals(s.onReclassify()),
                "Choose whether links are flagged for review or revoked when something becomes confidential.");
    }

    private static boolean between(int value, int min, int max) {
        return value >= min && value <= max;
    }

    private static void require(boolean ok, String message) {
        if (!ok) {
            throw SharingException.badRequest("SHARING_SETTINGS_INVALID", message);
        }
    }
}
