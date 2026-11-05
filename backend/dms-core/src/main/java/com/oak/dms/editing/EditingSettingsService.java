package com.oak.dms.editing;

import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.catalog.AuditObjectTypes;
import com.oak.dms.audit.enums.AuditOutcome;
import com.oak.dms.audit.service.AuditService;
import com.oak.dms.checkout.CheckoutException;
import com.oak.dms.security.auth.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Each organisation's own online-editing switch (CICO-FR-010) - replaces the server-wide
 * {@code EDITING_ENABLED} / {@code EDITING_MODE} environment variables.
 *
 * <p>Two independent things must both be true for a tenant's users to get the editor:
 * <ul>
 *   <li>the SERVER has an editing engine ({@link EditingEngine#isConfigured}) - an infrastructure fact set once
 *       by whoever runs the server; and</li>
 *   <li>the TENANT has turned online editing on - a permission-gated setting an administrator changes in the app
 *       (<code>cico.configure-editing-engine.select-provider</code>), default OFF.</li>
 * </ul>
 * One organisation turning it on never affects another, and a server that has an engine does not switch it on for
 * anyone.
 */
@Service
@RequiredArgsConstructor
public class EditingSettingsService {

    public static final String EXCLUSIVE = "exclusive";
    public static final String SIMULTANEOUS = "simultaneous";

    /** What a tenant with no row gets. */
    private static final Settings DEFAULT = new Settings(false, EXCLUSIVE);

    public record Settings(boolean enabled, String mode) {
    }

    /**
     * @param enabled          the tenant's own switch
     * @param engineConfigured the server has an engine - when false, turning {@code enabled} on is refused
     */
    public record View(boolean enabled, String mode, boolean engineConfigured) {
    }

    private final TenantEditingSettingsRepository repository;
    private final EditingEngine engine;
    private final AuditService auditService;

    /** A tenant's settings, or the default (off, exclusive) when it has never saved any. */
    @Transactional(readOnly = true)
    public Settings forTenant(UUID tenantId) {
        return repository.findById(tenantId).map(s -> new Settings(s.isEnabled(), s.getMode())).orElse(DEFAULT);
    }

    @Transactional(readOnly = true)
    public View view() {
        Settings s = forTenant(TenantContext.getTenantId());
        return new View(s.enabled(), s.mode(), engine.isConfigured());
    }

    /**
     * @throws CheckoutException 400 {@code EDITING_MODE_INVALID}; 409 {@code EDITING_ENGINE_NOT_CONFIGURED} when
     *                           asked to turn it on and this server has no engine (turning it OFF is always allowed)
     */
    @Transactional
    public View save(boolean enabled, String mode) {
        String chosen = mode == null ? EXCLUSIVE : mode.trim().toLowerCase(java.util.Locale.ROOT);
        if (!EXCLUSIVE.equals(chosen) && !SIMULTANEOUS.equals(chosen)) {
            throw CheckoutException.badRequest("EDITING_MODE_INVALID",
                    "Mode must be \"exclusive\" (one editor at a time) or \"simultaneous\" (everyone together).");
        }
        if (enabled && !engine.isConfigured()) {
            throw CheckoutException.conflict("EDITING_ENGINE_NOT_CONFIGURED",
                    "This server has no editing engine set up, so online editing cannot be turned on. "
                            + "Ask whoever runs the server to configure one.");
        }
        UUID tenantId = TenantContext.getTenantId();
        Settings before = forTenant(tenantId);
        TenantEditingSettings row = repository.findById(tenantId)
                .orElseGet(() -> TenantEditingSettings.builder().tenantId(tenantId).build());
        row.setEnabled(enabled);
        row.setMode(chosen);
        row.setUpdatedBy(TenantContext.getUserId());
        row.setUpdatedAt(OffsetDateTime.now());
        repository.save(row);

        Map<String, Object> previous = new LinkedHashMap<>();
        previous.put("enabled", before.enabled());
        previous.put("mode", before.mode());
        Map<String, Object> now = new LinkedHashMap<>();
        now.put("enabled", enabled);
        now.put("mode", chosen);
        auditService.event(AuditActionCatalog.EDITING_SETTINGS_CHANGED)
                .objectType(AuditObjectTypes.TENANT).objectId(tenantId)
                .outcome(AuditOutcome.SUCCESS).previousValue(previous).newValue(now).log();
        return new View(enabled, chosen, engine.isConfigured());
    }
}
