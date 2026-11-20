package com.oak.dms.reporting.format;

import com.oak.dms.reporting.catalog.FieldDescriptor.Special;
import com.oak.dms.reporting.catalog.FieldDescriptor.ValueKind;
import com.oak.dms.reporting.catalog.FieldType;
import com.oak.dms.reporting.datasource.ReportQuery.Column;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

class ReportValueFormatterTest {

    private final ReportValueFormatter formatter = new ReportValueFormatter("dd/MM/yyyy", "#,##0.00", "en-GB", ZoneId.of("Asia/Colombo"));

    private static Column column(String key, FieldType type, ValueKind kind, boolean multi) {
        return new Column(key, key, type, kind, multi, Special.NONE, "c0");
    }

    @Test
    void timestampsAreShownInTheReportsZone_withTheTenantsDatePattern() {
        Column created = column("createdAt", FieldType.DATE, ValueKind.TIMESTAMP, false);

        // 20:00 UTC on the 9th is 01:30 on the 10th at +05:30
        assertThat(formatter.display(created, OffsetDateTime.parse("2024-03-09T20:00:00Z"))).isEqualTo("10/03/2024 01:30");
    }

    @Test
    void aDateHasNoTimeAndNoZoneShift() {
        Column signed = column("meta.signedOn", FieldType.DATE, ValueKind.DATE, false);

        assertThat(formatter.display(signed, LocalDate.of(2024, 1, 15))).isEqualTo("15/01/2024");
    }

    @Test
    void builtInCountsAreWholeNumbers_andACustomNumberFollowsTheTenantsPattern() {
        Column pages = column("pageCount", FieldType.NUMBER, ValueKind.NUMBER, false);
        Column value = column("meta.contractValue", FieldType.NUMBER, ValueKind.NUMBER, false);

        assertThat(formatter.display(pages, new BigDecimal("1234"))).isEqualTo("1,234");
        assertThat(formatter.display(value, new BigDecimal("1234.5"))).isEqualTo("1,234.50");
    }

    @Test
    void booleansNullsAndPlainText() {
        Column flag = column("legalHold", FieldType.BOOLEAN, ValueKind.BOOLEAN, false);
        Column title = column("title", FieldType.TEXT, ValueKind.TEXT, false);

        assertThat(formatter.display(flag, true)).isEqualTo("Yes");
        assertThat(formatter.display(flag, false)).isEqualTo("No");
        assertThat(formatter.display(title, null)).isEmpty();
        assertThat(formatter.display(title, "Annual report")).isEqualTo("Annual report");
    }

    @Test
    void aMultiSelectValueIsAJsonListShownCommaSeparated() {
        Column tags = column("meta.tags", FieldType.CHOICE, ValueKind.TEXT, true);

        assertThat(formatter.display(tags, "[\"red\",\"blue\"]")).isEqualTo("red, blue");
        assertThat(formatter.display(tags, "not json")).isEqualTo("not json");
        assertThat(formatter.display(tags, "7")).isEqualTo("7");
    }

    @Test
    void withNoPatternsConfiguredItUsesSensibleDefaults() {
        ReportValueFormatter plain = new ReportValueFormatter(null, null, null, ZoneId.of("UTC"));

        assertThat(plain.display(column("signedOn", FieldType.DATE, ValueKind.DATE, false), LocalDate.of(2024, 3, 10))).isEqualTo("2024-03-10");
        assertThat(plain.display(column("meta.v", FieldType.NUMBER, ValueKind.NUMBER, false), new BigDecimal("100.5"))).isEqualTo("100.5");
    }
}
