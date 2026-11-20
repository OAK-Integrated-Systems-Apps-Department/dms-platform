package com.oak.dms.search.preset;

import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.catalog.AuditObjectTypes;
import com.oak.dms.audit.enums.AuditOutcome;
import com.oak.dms.audit.service.AuditService;
import com.oak.dms.search.api.SearchApiException;
import com.oak.dms.search.config.SearchProperties;
import com.oak.dms.security.auth.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * How many presets one person may keep. Same shape as the pin limit: a configured default
 * ({@code dms.search.presets.default-max}), overridden per organisation by a row in saved_search_settings. Lowering it
 * never deletes anyone's presets; it only stops them adding more until they are under it again.
 */
@Service
public class PresetLimitService {

    public static final int MIN = 1;
    public static final int MAX = 500;

    public record Limit(int maxPerUser, int defaultMax) {
    }

    private final SavedSearchRepository repository;
    private final SearchProperties props;
    private final AuditService auditService;

    public PresetLimitService(SavedSearchRepository repository, SearchProperties props, AuditService auditService) {
        this.repository = repository;
        this.props = props;
        this.auditService = auditService;
    }

    public int limit(UUID tenantId) {
        return repository.tenantLimit(tenantId).orElse(props.getPresets().getDefaultMax());
    }

    public Limit current() {
        return new Limit(limit(TenantContext.getTenantId()), props.getPresets().getDefaultMax());
    }

    @Transactional
    public Limit set(int max) {
        if (max < MIN || max > MAX) {
            throw new SearchApiException(HttpStatus.BAD_REQUEST, "PRESET_LIMIT_INVALID",
                    "Allow between " + MIN + " and " + MAX + " saved searches per person.");
        }
        UUID tenantId = TenantContext.getTenantId();
        int before = limit(tenantId);
        repository.setTenantLimit(tenantId, max);
        auditService.event(AuditActionCatalog.SEARCH_PRESET_LIMIT_CHANGED)
                .objectType(AuditObjectTypes.SEARCH_PRESET)
                .objectId(tenantId)
                .outcome(AuditOutcome.SUCCESS)
                .previousValue(Map.of("maxPerUser", before))
                .newValue(Map.of("maxPerUser", max))
                .log();
        return current();
    }
}
