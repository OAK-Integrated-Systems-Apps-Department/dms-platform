package com.oak.dms.reporting.export;

import com.oak.dms.reporting.ReportProperties;
import com.oak.dms.reporting.catalog.FieldDescriptor.Special;
import com.oak.dms.reporting.catalog.FieldDescriptor.ValueKind;
import com.oak.dms.reporting.catalog.FieldType;
import com.oak.dms.reporting.datasource.ReportQuery.Column;
import com.oak.dms.reporting.datasource.ReportRow;
import com.oak.dms.reporting.entity.TenantReportSettings;
import com.oak.dms.reporting.format.ReportValueFormatter;
import com.oak.dms.reporting.settings.EffectiveReportSettings;
import com.oak.dms.reporting.settings.ReportSettingsDefaults;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** Small builders shared by the exporter tests. */
final class ExporterFixtures {

    static final ZoneId ZONE = ZoneId.of("Asia/Colombo");
    static final OffsetDateTime GENERATED = OffsetDateTime.parse("2024-03-10T06:30:00Z"); // 12:00 in Colombo

    static final Column TITLE = new Column("title", "Title", FieldType.TEXT, ValueKind.TEXT, false, Special.NONE, "c0");
    static final Column PAGES = new Column("pageCount", "Page count", FieldType.NUMBER, ValueKind.NUMBER, false, Special.NONE, "c1");
    static final Column CREATED = new Column("createdAt", "Created at", FieldType.DATE, ValueKind.TIMESTAMP, false, Special.NONE, "c2");
    static final Column HOLD = new Column("legalHold", "Legal hold", FieldType.BOOLEAN, ValueKind.BOOLEAN, false, Special.NONE, "c3");
    static final Column STATUS = new Column("status", "Document status", FieldType.CHOICE, ValueKind.TEXT, false, Special.NONE, "grp");
    static final List<Column> COLUMNS = List.of(TITLE, PAGES, CREATED, HOLD);

    private ExporterFixtures() {
    }

    static EffectiveReportSettings settings(TenantReportSettings.TenantReportSettingsBuilder customise) {
        return new ReportSettingsDefaults(new ReportProperties()).resolve(customise.tenantId(java.util.UUID.randomUUID()).build());
    }

    static EffectiveReportSettings defaults() {
        return new ReportSettingsDefaults(new ReportProperties()).resolve(null);
    }

    static ReportRow row(String title, long pages, String created, boolean hold) {
        return new ReportRow(java.util.UUID.randomUUID(), new ArrayList<>(List.of(title, BigDecimal.valueOf(pages),
                OffsetDateTime.parse(created), hold)));
    }

    static ReportRow grouped(String title, long pages, String created, boolean hold, String status) {
        ReportRow r = row(title, pages, created, hold);
        return new ReportRow(r.documentId(), r.cells(), status);
    }

    static ReportFileSpec spec(EffectiveReportSettings settings, long rows, boolean truncated, Column group,
                               Map<String, Long> groupCounts) {
        return new ReportFileSpec("Contract register", GENERATED, "Alice Perera",
                List.of("Title: contains \"contract\"", "Created at: last 7 days (2024-03-04 to 2024-03-10)"), rows, truncated,
                rows, COLUMNS, group, groupCounts, new ReportValueFormatter(settings.dateFormat(), settings.numberFormat(),
                settings.defaultLocale(), ZONE), settings);
    }

    static ReportFileSpec.RowCursor cursor(List<ReportRow> rows) {
        Iterator<ReportRow> it = rows.iterator();
        return () -> it.hasNext() ? it.next() : null;
    }
}
