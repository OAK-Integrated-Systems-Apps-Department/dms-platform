package com.oak.dms.reporting.export;

import com.oak.dms.folder.DocumentAccessService.DocumentAccessView;
import com.oak.dms.folder.FolderAccessView;
import com.oak.dms.reporting.ReportProperties;
import com.oak.dms.reporting.catalog.EffectiveCatalogue;
import com.oak.dms.reporting.catalog.ReportFieldCatalog;
import com.oak.dms.reporting.datasource.DocumentReportQueryBuilder;
import com.oak.dms.reporting.datasource.DocumentReportQueryBuilder.Limits;
import com.oak.dms.reporting.datasource.ReadOnlySnapshot;
import com.oak.dms.reporting.datasource.ReportContext;
import com.oak.dms.reporting.datasource.ReportQuery;
import com.oak.dms.reporting.dto.ReportDefinition;
import com.oak.dms.reporting.dto.ReportDefinition.Sort;
import com.oak.dms.reporting.format.ReportValueFormatter;
import com.oak.dms.reporting.format.RowResolver;
import com.oak.dms.reporting.run.ReportGenerator;
import com.oak.dms.reporting.settings.EffectiveReportSettings;
import com.oak.dms.reporting.settings.ReportSettingsDefaults;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongConsumer;

/**
 * Runs ONE export through the real generator in a JVM with a small heap, so that "memory does not grow with the report" is a fact the
 * operating system enforces rather than a claim. Arguments: url user password tenant format rowLimit outputFile. Prints
 * {@code OK total=.. written=.. bytes=..} on success; any failure (including OutOfMemoryError) exits non-zero.
 * <p>
 * Also usable in-process by the consistency test through {@link #export}.
 */
public final class ScaleExportMain {

    private ScaleExportMain() {
    }

    public static void main(String[] args) throws Exception {
        UUID tenant = UUID.fromString(args[3]);
        Path out = Path.of(args[6]);
        ReportGenerator.Outcome outcome = export(args[0], args[1], args[2], tenant, args[4], Long.parseLong(args[5]), out, rows -> {
        }, () -> false);
        System.out.println("OK total=" + outcome.total() + " written=" + outcome.written() + " bytes=" + Files.size(out));
    }

    public static ReportGenerator.Outcome export(String url, String user, String password, UUID tenant, String format, long rowLimit,
                                                 Path out, LongConsumer progress, java.util.function.BooleanSupplier cancelled)
            throws Exception {
        DriverManagerDataSource ds = new DriverManagerDataSource(url, user, password);
        NamedParameterJdbcTemplate jdbc = new NamedParameterJdbcTemplate(ds);
        ReportGenerator generator = new ReportGenerator(new ReadOnlySnapshot(new DataSourceTransactionManager(ds), jdbc), new RowResolver(null));

        EffectiveReportSettings settings = new ReportSettingsDefaults(new ReportProperties()).resolve(null);
        EffectiveCatalogue catalogue = ReportFieldCatalog.merge(List.of(), List.of());
        DocumentAccessView access = new DocumentAccessView(new FolderAccessView(List.of(), Map.of(), true), Map.of(), true, true);
        ZoneId zone = ZoneId.of("UTC");
        Instant now = Instant.now();
        ReportDefinition definition = new ReportDefinition("documents", List.of(),
                List.of("title", "documentType", "createdAt", "status", "pageCount"), List.of(new Sort("title", "asc")), null, null, null);
        ReportQuery query = new DocumentReportQueryBuilder().build(tenant, access, definition, catalogue, new Limits(25, 40, 5), zone, now, false);
        ReportContext context = new ReportContext(tenant, UUID.randomUUID(), access, settings, zone, now);
        ReportValueFormatter formatter = new ReportValueFormatter(settings, zone);

        ReportExporter exporter = switch (format) {
            case "csv" -> new CsvReportExporter();
            case "xlsx" -> new ExcelReportExporter();
            case "pdf" -> new PdfReportExporter(List.<Path>of());
            default -> throw new IllegalArgumentException(format);
        };
        AtomicLong header = new AtomicLong();
        return generator.generate(query, context, exporter, out, rowLimit, true, Duration.ofMinutes(5), progress, cancelled,
                (rowCount, truncated, limit, groups) -> {
                    header.set(rowCount);
                    return new ReportFileSpec("Scale report", OffsetDateTime.now(ZoneOffset.UTC), "Test", query.resolvedFilters(), rowCount,
                            truncated, limit, query.columns(), query.groupColumn(), groups, formatter, settings);
                });
    }
}
