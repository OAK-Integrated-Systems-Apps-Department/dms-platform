package com.oak.dms.reporting.admin;

import com.oak.dms.common.io.TempFiles;
import com.oak.dms.reporting.ReportException;
import com.oak.dms.reporting.catalog.FieldDescriptor.Special;
import com.oak.dms.reporting.catalog.FieldDescriptor.ValueKind;
import com.oak.dms.reporting.catalog.FieldType;
import com.oak.dms.reporting.datasource.ReportQuery.Column;
import com.oak.dms.reporting.datasource.ReportRow;
import com.oak.dms.reporting.dto.ReportSettingsUpdate;
import com.oak.dms.reporting.export.ReportExporter;
import com.oak.dms.reporting.export.ReportFileSpec;
import com.oak.dms.reporting.format.ReportValueFormatter;
import com.oak.dms.reporting.settings.EffectiveReportSettings;
import com.oak.dms.reporting.settings.ReportSettingsService;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * A one-page sample of what a report will look like under the output settings on the administrator's screen - before they save them.
 * The rows are invented here; no document, user or tenant data is read, so the sample can be shown to anyone and nothing about it can
 * leak. The settings are validated exactly as a save would, and stored nowhere.
 */
@Service
public class ReportSampleService {

    private static final List<Column> COLUMNS = List.of(
            new Column("title", "Title", FieldType.TEXT, ValueKind.TEXT, false, Special.NONE, "c0"),
            new Column("documentType", "Document type", FieldType.CHOICE, ValueKind.TEXT, false, Special.NONE, "c1"),
            new Column("pageCount", "Page count", FieldType.NUMBER, ValueKind.NUMBER, false, Special.NONE, "c2"),
            new Column("createdAt", "Created at", FieldType.DATE, ValueKind.TIMESTAMP, false, Special.NONE, "c3"),
            new Column("legalHold", "Legal hold", FieldType.BOOLEAN, ValueKind.BOOLEAN, false, Special.NONE, "c4"));

    private final ReportSettingsService settingsService;
    private final Map<String, ReportExporter> exporters;

    public ReportSampleService(ReportSettingsService settingsService, List<ReportExporter> exporters) {
        this.settingsService = settingsService;
        this.exporters = exporters.stream().collect(Collectors.toMap(ReportExporter::format, Function.identity()));
    }

    public record Sample(byte[] bytes, String contentType, String filename) {
    }

    /** @param format pdf or csv */
    public Sample generate(String format, ReportSettingsUpdate unsaved) {
        String wanted = format == null ? "" : format.toLowerCase();
        if (!wanted.equals("pdf") && !wanted.equals("csv")) {
            throw ReportException.unprocessable("REPORT_SAMPLE_FORMAT", "A sample can be made as pdf or csv.");
        }
        EffectiveReportSettings settings = settingsService.preview(unsaved == null ? emptyUpdate() : unsaved);
        ReportExporter exporter = exporters.get(wanted);
        ZoneId zone = ZoneId.of(settings.defaultTimeZone() == null || settings.defaultTimeZone().isBlank() ? "UTC" : settings.defaultTimeZone());
        List<ReportRow> rows = new ArrayList<>();
        String[] titles = {"Supplier agreement", "Employee handbook", "Q3 financial summary", "Site safety checklist", "Board minutes"};
        String[] types = {"Contract", "Policy", "Report", "Form", "Minutes"};
        for (int i = 0; i < titles.length; i++) {
            rows.add(new ReportRow(UUID.randomUUID(), new ArrayList<>(List.of(titles[i], types[i], BigDecimal.valueOf(3 + i * 7L),
                    OffsetDateTime.of(2025, 1 + i, 10 + i, 9, 30, 0, 0, ZoneOffset.UTC), i == 0))));
        }
        ReportFileSpec spec = new ReportFileSpec("Sample report", OffsetDateTime.now(ZoneOffset.UTC), "Sample administrator",
                List.of("Document type: is any of Contract, Policy", "Created at: in the last 30 days"), rows.size(), false, rows.size(),
                COLUMNS, null, Map.of(), new ReportValueFormatter(settings, zone), settings);
        Iterator<ReportRow> it = rows.iterator();
        Path temp = null;
        try {
            temp = TempFiles.create("dms-report-sample-", "." + exporter.extension());
            exporter.write(spec, () -> it.hasNext() ? it.next() : null, temp);
            return new Sample(Files.readAllBytes(temp), exporter.contentType(), "sample-report." + exporter.extension());
        } catch (IOException e) {
            throw ReportException.unprocessable("REPORT_SAMPLE_FAILED", "The sample could not be made. Try again.");
        } finally {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // a temp file left behind is cleaned by the operating system
                }
            }
        }
    }

    private static ReportSettingsUpdate emptyUpdate() {
        return new ReportSettingsUpdate(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }
}
