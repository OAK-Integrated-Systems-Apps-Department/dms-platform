package com.oak.dms.compliance;

import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.catalog.AuditObjectTypes;
import com.oak.dms.audit.enums.AuditOutcome;
import com.oak.dms.audit.service.AuditService;
import com.oak.dms.security.auth.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Each organisation's own retention settings (CRM-NFR-006: behaviour is configurable and adaptable as regulations change).
 * No row = the defaults: the automatic job runs, the longest retention wins when rules overlap, a document no rule covers
 * is flagged for an administrator, and a reason for a manual status change is optional.
 */
@Service
@RequiredArgsConstructor
public class RetentionSettingsService {

    /** A document no rule covers is flagged for an administrator to assign a rule (the default)... */
    public static final String FLAG = "flag";
    /** ...or simply left alone. */
    public static final String IGNORE = "ignore";

    /** Versions removed by a retention rule stay restorable this long before their files are destroyed. */
    public static final int DEFAULT_GRACE_DAYS = 30;

    public record Settings(boolean automationEnabled, String precedence, String whenNoPolicy, boolean requireStatusReason,
                           int versionGraceDays, boolean protectFromDeletion) {
    }

    private static final Settings DEFAULT = new Settings(true, RetentionResolver.LONGEST, FLAG, false, DEFAULT_GRACE_DAYS, false);

    private final TenantRetentionSettingsRepository repository;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public Settings forTenant(UUID tenantId) {
        return repository.findById(tenantId)
                .map(s -> new Settings(s.isAutomationEnabled(), s.getPrecedence(), s.getWhenNoPolicy(), s.isRequireStatusReason(),
                        s.getVersionGraceDays(), s.isProtectFromDeletion()))
                .orElse(DEFAULT);
    }

    @Transactional(readOnly = true)
    public Settings mine() {
        return forTenant(TenantContext.getTenantId());
    }

    /** @throws ComplianceException 400 {@code RETENTION_PRECEDENCE_INVALID} / {@code RETENTION_NO_POLICY_ACTION_INVALID} */
    @Transactional
    public Settings save(Settings wanted) {
        String precedence = wanted.precedence() == null ? "" : wanted.precedence().trim().toLowerCase(Locale.ROOT);
        if (!RetentionResolver.LONGEST.equals(precedence) && !RetentionResolver.MOST_SPECIFIC.equals(precedence)) {
            throw ComplianceException.badRequest("RETENTION_PRECEDENCE_INVALID",
                    "When rules overlap, choose \"longest\" (the longest retention wins) or \"most_specific\" "
                            + "(folder, then document type, then department, then the organisation default).");
        }
        String whenNone = wanted.whenNoPolicy() == null ? "" : wanted.whenNoPolicy().trim().toLowerCase(Locale.ROOT);
        if (!FLAG.equals(whenNone) && !IGNORE.equals(whenNone)) {
            throw ComplianceException.badRequest("RETENTION_NO_POLICY_ACTION_INVALID",
                    "For a document no rule covers, choose \"flag\" (ask an administrator to assign one) or \"ignore\".");
        }
        if (wanted.versionGraceDays() < 0 || wanted.versionGraceDays() > 3650) {
            throw ComplianceException.badRequest("RETENTION_GRACE_INVALID",
                    "Removed versions can stay restorable for 0 to 3650 days (0 = destroyed at the next run).");
        }
        UUID tenantId = TenantContext.getTenantId();
        Settings before = forTenant(tenantId);
        TenantRetentionSettings row = repository.findById(tenantId)
                .orElseGet(() -> TenantRetentionSettings.builder().tenantId(tenantId).build());
        row.setAutomationEnabled(wanted.automationEnabled());
        row.setPrecedence(precedence);
        row.setWhenNoPolicy(whenNone);
        row.setRequireStatusReason(wanted.requireStatusReason());
        row.setVersionGraceDays(wanted.versionGraceDays());
        row.setProtectFromDeletion(wanted.protectFromDeletion());
        row.setUpdatedBy(TenantContext.getUserId());
        row.setUpdatedAt(OffsetDateTime.now());
        repository.save(row);

        Settings after = new Settings(wanted.automationEnabled(), precedence, whenNone, wanted.requireStatusReason(),
                wanted.versionGraceDays(), wanted.protectFromDeletion());
        auditService.event(AuditActionCatalog.RETENTION_SETTINGS_CHANGED)
                .objectType(AuditObjectTypes.TENANT).objectId(tenantId)
                .outcome(AuditOutcome.SUCCESS).previousValue(asMap(before)).newValue(asMap(after)).log();
        return after;
    }

    private static Map<String, Object> asMap(Settings s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("automationEnabled", s.automationEnabled());
        m.put("precedence", s.precedence());
        m.put("whenNoPolicy", s.whenNoPolicy());
        m.put("requireStatusReason", s.requireStatusReason());
        m.put("versionGraceDays", s.versionGraceDays());
        m.put("protectFromDeletion", s.protectFromDeletion());
        return m;
    }
}
