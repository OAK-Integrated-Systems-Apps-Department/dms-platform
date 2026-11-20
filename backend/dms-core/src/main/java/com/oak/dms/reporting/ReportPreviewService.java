package com.oak.dms.reporting;

import com.oak.dms.folder.DocumentAccessService;
import com.oak.dms.reporting.datasource.DocumentReportDataSource;
import com.oak.dms.reporting.datasource.ReportContext;
import com.oak.dms.reporting.datasource.ReportDataSource;
import com.oak.dms.reporting.datasource.ReportQuery;
import com.oak.dms.reporting.datasource.ReportRow;
import com.oak.dms.reporting.datasource.ReadOnlySnapshot;
import com.oak.dms.reporting.dto.PreviewResponse;
import com.oak.dms.reporting.dto.PreviewResponse.ColumnInfo;
import com.oak.dms.reporting.dto.ReportDefinition;
import com.oak.dms.reporting.format.ReportValueFormatter;
import com.oak.dms.reporting.format.RowResolver;
import com.oak.dms.reporting.settings.EffectiveReportSettings;
import com.oak.dms.reporting.settings.ReportSettingsService;
import com.oak.dms.security.auth.AuthorizationService;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.security.rbac.catalog.PermissionCodes;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The synchronous preview behind the builder: a visible-row total and the first 50 rows, through the same query builder a real
 * run uses. Slow filters are cut off at {@code dms.reports.preview-timeout} and answered with {@code timedOut}, not an error.
 */
@Slf4j
@Service
public class ReportPreviewService {

    static final int SAMPLE_SIZE = 50;

    private final ReportSettingsService settingsService;
    private final DocumentAccessService documentAccess;
    private final AuthorizationService authorization;
    private final DocumentReportDataSource dataSource;
    private final RowResolver rowResolver;
    private final ReportMetrics metrics;

    public ReportPreviewService(ReportSettingsService settingsService, DocumentAccessService documentAccess,
                                AuthorizationService authorization, DocumentReportDataSource dataSource,
                                RowResolver rowResolver, ReportMetrics metrics) {
        this.settingsService = settingsService;
        this.documentAccess = documentAccess;
        this.authorization = authorization;
        this.dataSource = dataSource;
        this.rowResolver = rowResolver;
        this.metrics = metrics;
    }

    public PreviewResponse preview(ReportDefinition definition) {
        Instant started = Instant.now();
        String outcome = "error";
        try {
            UUID tenantId = TenantContext.getTenantId();
            EffectiveReportSettings settings = settingsService.requireEnabled(tenantId);
            // Reports show documents, so they need the permission that shows documents: the "run" grant alone must never
            // be a way round it.
            if (!authorization.hasPermission(PermissionCodes.DOCM_DOCUMENTS_VIEW)) {
                throw ReportException.forbidden("REPORT_DOCUMENT_ACCESS_REQUIRED",
                        "You need access to documents to build a report on them.");
            }
            ZoneId zone = ReportContext.zoneFor(definition.timeZone(), settings);
            ReportContext context = new ReportContext(tenantId, TenantContext.getUserId(), documentAccess.forCaller(),
                    settings, zone, started);
            ReportQuery query = dataSource.plan(definition, context);

            ReportDataSource.Preview result;
            try {
                result = dataSource.preview(query, SAMPLE_SIZE, settingsService.previewTimeout());
            } catch (DataAccessException e) {
                if (ReadOnlySnapshot.isTimeout(e)) {
                    outcome = "timeout";
                    log.info("Report preview timed out after {} ms", Duration.between(started, Instant.now()).toMillis());
                    return timedOut(query, settings, zone);
                }
                throw e;
            }
            rowResolver.resolve(result.sample(), query.columns(), context.access(), tenantId);
            outcome = "ok";
            return respond(query, result, settings, zone);
        } catch (ReportException e) {
            outcome = "refused";
            throw e;
        } finally {
            metrics.preview(Duration.between(started, Instant.now()), outcome);
        }
    }

    private static PreviewResponse respond(ReportQuery query, ReportDataSource.Preview result, EffectiveReportSettings settings,
                                           ZoneId zone) {
        ReportValueFormatter formatter = new ReportValueFormatter(settings, zone);
        List<List<String>> sample = new ArrayList<>();
        for (ReportRow row : result.sample()) {
            List<String> cells = new ArrayList<>(query.columns().size());
            for (int i = 0; i < query.columns().size(); i++) {
                cells.add(formatter.display(query.columns().get(i), row.cells().get(i)));
            }
            sample.add(cells);
        }
        return new PreviewResponse(result.total(), columnsOf(query), sample, query.limitedToAccess(),
                query.resolvedFilters(), false, zone.getId(), settings.maxRows(), settings.maxPdfRows());
    }

    private static PreviewResponse timedOut(ReportQuery query, EffectiveReportSettings settings, ZoneId zone) {
        return new PreviewResponse(null, columnsOf(query), List.of(), query.limitedToAccess(), query.resolvedFilters(), true,
                zone.getId(), settings.maxRows(), settings.maxPdfRows());
    }

    private static List<ColumnInfo> columnsOf(ReportQuery query) {
        return query.columns().stream().map(c -> new ColumnInfo(c.key(), c.label(), c.type().name().toLowerCase())).toList();
    }
}
