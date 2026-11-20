package com.oak.dms.reporting.datasource;

import com.oak.dms.reporting.catalog.EffectiveCatalogue;
import com.oak.dms.reporting.dto.ReportDefinition;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Something reports can be run over. v1 has one: documents. Controllers, jobs and exporters talk only to this interface, so
 * another source can be added without touching them.
 */
public interface ReportDataSource {

    /** The value of {@code ReportDefinition#source}. */
    String id();

    /** What this organisation can show, filter, sort and group by - already reduced by the administrator's settings. */
    EffectiveCatalogue catalogue(UUID tenantId);

    /**
     * Validates the definition against the catalogue and the limits, applies the organisation's defaults for what the user left
     * out, and builds the query. Preview and generation both go through here, so they cannot disagree.
     *
     * @throws com.oak.dms.reporting.ReportException 422 with a stable code and the offending field
     */
    ReportQuery plan(ReportDefinition definition, ReportContext context);

    /**
     * The visible row count and the first {@code sampleSize} rows, from one snapshot. Cells are typed but not yet finished
     * (see {@code RowResolver}).
     */
    Preview preview(ReportQuery query, int sampleSize, Duration timeout);

    record Preview(long total, List<ReportRow> sample) {
    }
}
