package com.oak.dms.reporting;

import com.oak.dms.reporting.catalog.EffectiveCatalogue;
import com.oak.dms.reporting.catalog.FieldDescriptor;
import com.oak.dms.reporting.catalog.Operator;
import com.oak.dms.reporting.datasource.DocumentReportDataSource;
import com.oak.dms.reporting.datasource.RelativeDates;
import com.oak.dms.reporting.dto.CatalogResponse;
import com.oak.dms.reporting.dto.CatalogResponse.Capabilities;
import com.oak.dms.reporting.dto.CatalogResponse.Field;
import com.oak.dms.reporting.dto.CatalogResponse.Limits;
import com.oak.dms.reporting.dto.CatalogResponse.Settings;
import com.oak.dms.reporting.settings.EffectiveReportSettings;
import com.oak.dms.reporting.settings.ReportSettingsService;
import com.oak.dms.security.auth.AuthorizationService;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.security.rbac.catalog.PermissionCodes;
import org.springframework.stereotype.Service;

import java.util.UUID;

/** The effective catalogue and settings for the caller's organisation, in the shape the builder needs. */
@Service
public class ReportCatalogService {

    private static final int MAX_SORT_KEYS = 3;

    private final ReportSettingsService settingsService;
    private final DocumentReportDataSource dataSource;
    private final AuthorizationService authorization;

    public ReportCatalogService(ReportSettingsService settingsService, DocumentReportDataSource dataSource,
                                AuthorizationService authorization) {
        this.settingsService = settingsService;
        this.dataSource = dataSource;
        this.authorization = authorization;
    }

    public CatalogResponse catalog() {
        UUID tenantId = TenantContext.getTenantId();
        EffectiveReportSettings s = settingsService.requireEnabled(tenantId);
        EffectiveCatalogue catalogue = dataSource.catalogue(tenantId);
        var ceilings = settingsService.ceilings();
        Limits limits = new Limits(ceilings.maxFilters(), ceilings.maxColumns(), ceilings.maxMetadataFilters(),
                MAX_SORT_KEYS, s.maxRows(), s.maxPdfRows());
        Settings settings = new Settings(s.allowedFormats(), s.defaultFormat(), s.overLimitBehaviour(), s.defaultTimeZone(),
                s.includeInactiveByDefault(), s.defaultColumns(), s.defaultSort(), limits, s.schedulingEnabled(),
                s.minScheduleInterval(), s.allowedDeliveryChannels(), s.deliveryModeDefault(), s.allowAttachments(),
                s.maxAttachmentMb(), s.allowExternalRecipients(), s.maxRecipientsPerSchedule(), s.showFilterSummary(),
                s.pauseAfterConsecutiveFailures());
        Capabilities capabilities = new Capabilities(
                authorization.hasPermission(PermissionCodes.CRG_BUILD),
                authorization.hasPermission(PermissionCodes.CRG_RUN),
                s.schedulingEnabled() && authorization.hasPermission(PermissionCodes.CRG_SCHEDULE_CREATE),
                authorization.hasPermission(PermissionCodes.CRG_TEMPLATE_PUBLISH),
                authorization.hasPermission(PermissionCodes.CRG_CONFIGURE_REPORTS),
                authorization.hasPermission(PermissionCodes.CRG_VIEW_ALL_RUNS));
        return new CatalogResponse(catalogue.all().stream().map(ReportCatalogService::field).toList(), settings, capabilities,
                RelativeDates.OPTIONS, RelativeDates.MAX_DAYS);
    }

    private static Field field(FieldDescriptor f) {
        return new Field(f.key(), f.label(), f.type().name().toLowerCase(),
                f.operators().stream().map(Operator::key).toList(), f.multi(), f.isMetadata(), f.selectable(), f.filterable(),
                f.sortable(), f.groupable(), f.defaultSelected());
    }
}
