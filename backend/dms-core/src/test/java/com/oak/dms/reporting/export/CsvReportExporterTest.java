package com.oak.dms.reporting.export;

import com.oak.dms.reporting.datasource.ReportRow;
import com.oak.dms.reporting.entity.TenantReportSettings;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.oak.dms.reporting.export.ExporterFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;

class CsvReportExporterTest {

    @TempDir
    Path dir;

    private final CsvReportExporter exporter = new CsvReportExporter();

    private List<CSVRecord> read(Path file, char delimiter) throws Exception {
        String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        if (text.startsWith("﻿")) {
            text = text.substring(1);
        }
        try (CSVParser parser = CSVFormat.DEFAULT.builder().setDelimiter(delimiter).get().parse(new StringReader(text))) {
            return parser.getRecords();
        }
    }

    private Path write(ReportFileSpec spec, List<ReportRow> rows) throws Exception {
        Path file = dir.resolve("r-" + UUID.randomUUID() + ".csv");
        exporter.write(spec, cursor(rows), file);
        return file;
    }

    @Test
    void writesTheHeaderBlock_theColumnHeadings_andTheRowsInOrder() throws Exception {
        var settings = defaults();
        ReportFileSpec spec = spec(settings, 2, false, null, Map.of());
        Path file = write(spec, List.of(row("Alpha", 12, "2024-03-09T20:00:00Z", true), row("Beta", 3, "2024-03-01T00:00:00Z", false)));

        List<CSVRecord> records = read(file, ',');
        List<String> firstColumn = records.stream().map(r -> r.get(0)).toList();

        assertThat(firstColumn).contains("Contract register", "Generated at: 2024-03-10 12:00 (Asia/Colombo)",
                "Generated for: Alice Perera", "Filter: Title: contains \"contract\"", "Rows: 2");
        int heading = firstColumn.indexOf("Title");
        assertThat(records.get(heading).toList()).containsExactly("Title", "Page count", "Created at", "Legal hold");
        assertThat(records.get(heading + 1).toList()).containsExactly("Alpha", "12", "2024-03-10 01:30", "Yes");
        assertThat(records.get(heading + 2).toList()).containsExactly("Beta", "3", "2024-03-01 05:30", "No");
        assertThat(records).hasSize(heading + 3);
    }

    @Test
    void anyTextCellThatCouldBeAFormulaIsQuoted_butNumbersAreLeftAlone() throws Exception {
        ReportFileSpec spec = spec(defaults(), 4, false, null, Map.of());
        List<ReportRow> rows = List.of(
                row("=HYPERLINK(\"http://evil\",\"x\")", -5, "2024-03-09T20:00:00Z", false),
                row("+1+1", 1, "2024-03-09T20:00:00Z", false),
                row("@SUM(A1)", 1, "2024-03-09T20:00:00Z", false),
                row("-2+3", -1, "2024-03-09T20:00:00Z", false));

        List<CSVRecord> records = read(write(spec, rows), ',');
        List<CSVRecord> data = records.subList(records.size() - 4, records.size());

        assertThat(data.get(0).get(0)).isEqualTo("'=HYPERLINK(\"http://evil\",\"x\")");
        assertThat(data.get(0).get(1)).as("a negative number is a number, not a formula").isEqualTo("-5");
        assertThat(data.get(1).get(0)).isEqualTo("'+1+1");
        assertThat(data.get(2).get(0)).isEqualTo("'@SUM(A1)");
        assertThat(data.get(3).get(0)).isEqualTo("'-2+3");
    }

    @Test
    void theHeaderBlockIsSanitisedToo_becauseAFilterValueIsTextTheUserTyped() throws Exception {
        ReportFileSpec s = spec(defaults(), 0, false, null, Map.of());
        ReportFileSpec hostile = new ReportFileSpec("=cmd", s.generatedAt(), "-x", List.of("=evil"), 0, false, 0, s.columns(), null,
                Map.of(), s.formatter(), s.settings());

        List<CSVRecord> records = read(write(hostile, List.of()), ',');
        List<String> first = records.stream().map(r -> r.get(0)).toList();

        assertThat(first).contains("'=cmd", "Generated for: -x", "Filter: =evil");
        assertThat(first).noneMatch(v -> v.startsWith("="));
    }

    @Test
    void theDelimiterAndTheByteOrderMarkComeFromTheOrganisationsSettings() throws Exception {
        var semicolon = settings(TenantReportSettings.builder().csvDelimiter(";").csvIncludeBom(true));
        var tab = settings(TenantReportSettings.builder().csvDelimiter("\t").csvIncludeBom(false));
        List<ReportRow> rows = List.of(row("A;B", 1, "2024-03-09T20:00:00Z", true));

        Path withBom = write(spec(semicolon, 1, false, null, Map.of()), rows);
        Path withoutBom = write(spec(tab, 1, false, null, Map.of()), rows);

        assertThat(Files.readAllBytes(withBom)).startsWith(0xEF, 0xBB, 0xBF);
        assertThat(Files.readAllBytes(withoutBom)[0]).isNotEqualTo((byte) 0xEF);
        assertThat(read(withBom, ';').stream().filter(r -> r.get(0).equals("A;B")).findFirst()).isPresent(); // quoted, one cell
        assertThat(new String(Files.readAllBytes(withoutBom), StandardCharsets.UTF_8)).contains("Title\tPage count\tCreated at\tLegal hold");
    }

    @Test
    void groupedReportsGetAHeaderPerGroupWithItsCount_andAGrandTotal() throws Exception {
        ReportFileSpec spec = spec(defaults(), 3, false, STATUS, Map.of("Draft", 2L, "Published", 1L));
        List<ReportRow> rows = List.of(
                grouped("A", 1, "2024-03-09T20:00:00Z", false, "Draft"),
                grouped("B", 1, "2024-03-09T20:00:00Z", false, "Draft"),
                grouped("C", 1, "2024-03-09T20:00:00Z", false, "Published"));

        List<String> first = read(write(spec, rows), ',').stream().map(r -> r.get(0)).toList();
        int heading = first.indexOf("Title");

        assertThat(first.subList(heading + 1, first.size())).containsExactly("Document status: Draft (2 documents)", "A", "B",
                "Document status: Published (1 document)", "C", "Total: 3 documents");
    }

    @Test
    void aTruncatedFileSaysSo() throws Exception {
        ReportFileSpec spec = spec(defaults(), 2, true, null, Map.of());

        List<String> first = read(write(spec, List.of(row("A", 1, "2024-03-09T20:00:00Z", false))), ',').stream()
                .map(r -> r.get(0)).toList();

        assertThat(first).contains("Truncated at 2 rows");
    }

    @Test
    void theOrganisationsHeaderAndConfidentialityTextArePrinted_andTheFilterSummaryCanBeSwitchedOff() throws Exception {
        var custom = settings(TenantReportSettings.builder().headerText("Acme Ltd").confidentialityNotice("CONFIDENTIAL")
                .showFilterSummary(false).showGeneratedBy(false));

        List<String> first = read(write(spec(custom, 0, false, null, Map.of()), List.of()), ',').stream().map(r -> r.get(0)).toList();

        assertThat(first).contains("Acme Ltd", "CONFIDENTIAL");
        assertThat(first).noneMatch(v -> v.startsWith("Filter") || v.startsWith("Generated for"));
    }

    @Test
    void nonLatinTextSurvivesAsUtf8() throws Exception {
        List<ReportRow> rows = new ArrayList<>();
        for (String name : List.of("සිංහල ගිවිසුම", "தமிழ் ஒப்பந்தம்", "عقد", "合同")) {
            rows.add(row(name, 1, "2024-03-09T20:00:00Z", false));
        }

        List<CSVRecord> records = read(write(spec(defaults(), 4, false, null, Map.of()), rows), ',');

        assertThat(records.stream().map(r -> r.get(0))).contains("සිංහල ගිවිසුම", "தமிழ் ஒப்பந்தம்", "عقد", "合同");
    }

    @Test
    void anEmptyResultStillWritesTheHeaderAndHeadings() throws Exception {
        List<CSVRecord> records = read(write(spec(defaults(), 0, false, null, Map.of()), List.of()), ',');

        assertThat(records.get(records.size() - 1).toList()).containsExactly("Title", "Page count", "Created at", "Legal hold");
    }
}
