package com.oak.dms.reporting.datasource;

import com.oak.dms.reporting.catalog.EffectiveCatalogue;
import com.oak.dms.reporting.catalog.FieldDescriptor;
import com.oak.dms.reporting.catalog.ReportFieldCatalog;
import com.oak.dms.reporting.datasource.DocumentReportQueryBuilder.Limits;
import com.oak.dms.reporting.dto.ReportDefinition;
import com.oak.dms.reporting.dto.ReportDefinition.Sort;
import com.oak.dms.reporting.settings.ReportSettingsService;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The Documents source: the field catalogue, the one query builder, and read-only execution in a single snapshot. */
@Component
public class DocumentReportDataSource implements ReportDataSource {

    static final String ID = "documents";
    private static final int MAX_SORT_KEYS = 3;

    private final ReportFieldCatalog catalog;
    private final DocumentReportQueryBuilder builder;
    private final ReportSettingsService settings;
    private final ReadOnlySnapshot snapshot;

    public DocumentReportDataSource(ReportFieldCatalog catalog, DocumentReportQueryBuilder builder,
                                    ReportSettingsService settings, ReadOnlySnapshot snapshot) {
        this.catalog = catalog;
        this.builder = builder;
        this.settings = settings;
        this.snapshot = snapshot;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public EffectiveCatalogue catalogue(UUID tenantId) {
        return catalog.forTenant(tenantId);
    }

    @Override
    public ReportQuery plan(ReportDefinition definition, ReportContext context) {
        EffectiveCatalogue catalogue = catalogue(context.tenantId());
        ReportDefinition normalised = withDefaults(definition, context, catalogue);
        var ceilings = settings.ceilings();
        return builder.build(context.tenantId(), context.access(), normalised, catalogue,
                new Limits(ceilings.maxFilters(), ceilings.maxColumns(), ceilings.maxMetadataFilters()),
                context.zone(), context.now(), context.settings().includeInactiveByDefault());
    }

    /**
     * What the user left out comes from the organisation's defaults - but only the defaults that are still available: a field the
     * administrator has since switched off is skipped here, not an error. Anything the user chose explicitly is left exactly as
     * given, so the builder can refuse it.
     */
    static ReportDefinition withDefaults(ReportDefinition def, ReportContext ctx, EffectiveCatalogue catalogue) {
        List<String> columns = def.columnsOrEmpty().isEmpty() ? defaultColumns(ctx, catalogue) : def.columns();
        List<Sort> sort = def.sortOrEmpty().isEmpty() ? defaultSort(ctx, catalogue) : def.sort();
        return new ReportDefinition(def.source(), def.filters(), columns, sort, def.groupBy(), def.includeInactive(),
                def.timeZone());
    }

    private static List<String> defaultColumns(ReportContext ctx, EffectiveCatalogue catalogue) {
        List<String> columns = new ArrayList<>();
        for (String key : ctx.settings().defaultColumns() == null ? List.<String>of() : ctx.settings().defaultColumns()) {
            if (catalogue.find(key).filter(FieldDescriptor::selectable).isPresent()) {
                columns.add(key);
            }
        }
        if (columns.isEmpty()) {
            catalogue.all().stream().filter(f -> f.selectable() && f.defaultSelected()).forEach(f -> columns.add(f.key()));
        }
        if (columns.isEmpty()) {
            catalogue.all().stream().filter(FieldDescriptor::selectable).findFirst().ifPresent(f -> columns.add(f.key()));
        }
        return columns;
    }

    /** Stored as {@code [{"field": "createdAt", "direction": "desc"}]}. */
    private static List<Sort> defaultSort(ReportContext ctx, EffectiveCatalogue catalogue) {
        List<Sort> sort = new ArrayList<>();
        List<Map<String, Object>> stored = ctx.settings().defaultSort();
        if (stored == null) {
            return sort;
        }
        for (Map<String, Object> entry : stored) {
            Object field = entry.get("field");
            Object direction = entry.get("direction");
            if (field != null && catalogue.find(field.toString()).filter(FieldDescriptor::sortable).isPresent()
                    && sort.size() < MAX_SORT_KEYS) {
                sort.add(new Sort(field.toString(), direction == null ? null : direction.toString()));
            }
        }
        return sort;
    }

    @Override
    public Preview preview(ReportQuery query, int sampleSize, Duration timeout) {
        return snapshot.run(timeout, jdbc -> {
            MapSqlParameterSource params = new MapSqlParameterSource(query.params());
            Long total = jdbc.queryForObject(query.countSql(), params, Long.class);
            // sampleSize is a number from code (50), never user input.
            List<ReportRow> rows = jdbc.query(query.sql() + " limit " + sampleSize, params,
                    (rs, i) -> ReportRow.read(rs, query.columns()));
            return new Preview(total == null ? 0 : total, rows);
        });
    }
}
