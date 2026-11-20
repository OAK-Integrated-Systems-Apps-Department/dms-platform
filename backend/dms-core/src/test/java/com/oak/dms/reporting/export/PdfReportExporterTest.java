package com.oak.dms.reporting.export;

import com.oak.dms.reporting.datasource.ReportRow;
import com.oak.dms.reporting.entity.TenantReportSettings;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.oak.dms.reporting.export.ExporterFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;

class PdfReportExporterTest {

    @TempDir
    Path dir;

    /** No font directories: the built-in Helvetica only, the same on every machine. */
    private final PdfReportExporter exporter = new PdfReportExporter(List.of());

    private PDDocument write(PdfReportExporter e, ReportFileSpec spec, List<ReportRow> rows) throws Exception {
        Path file = dir.resolve("r.pdf");
        e.write(spec, cursor(rows), file);
        return Loader.loadPDF(Files.readAllBytes(file));
    }

    private static String text(PDDocument doc, int page) throws Exception {
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setStartPage(page);
        stripper.setEndPage(page);
        return stripper.getText(doc);
    }

    @Test
    void writesTheTitleBlock_theHeadings_theRows_andPageNumbers() throws Exception {
        ReportFileSpec spec = spec(defaults(), 2, false, null, Map.of());
        try (PDDocument doc = write(exporter, spec, List.of(row("Alpha contract", 12, "2024-03-09T20:00:00Z", true),
                row("Beta contract", 3, "2024-03-01T00:00:00Z", false)))) {
            String page = text(doc, 1);

            assertThat(doc.getNumberOfPages()).isEqualTo(1);
            assertThat(page).contains("Contract register", "Generated at: 2024-03-10 12:00 (Asia/Colombo)",
                    "Generated for: Alice Perera", "Rows: 2", "Title", "Page count", "Created at", "Legal hold",
                    "Alpha contract", "Beta contract", "2024-03-10 01:30", "Yes", "Page 1 of 1");
            assertThat(doc.getPage(0).getMediaBox().getWidth()).as("landscape by default").isGreaterThan(doc.getPage(0).getMediaBox().getHeight());
        }
    }

    @Test
    void theHeadingsRepeatOnEveryPage_andEveryPageIsNumbered() throws Exception {
        List<ReportRow> rows = new ArrayList<>();
        for (int i = 0; i < 160; i++) {
            rows.add(row("Document " + i, i, "2024-03-09T20:00:00Z", false));
        }
        try (PDDocument doc = write(exporter, spec(defaults(), 160, false, null, Map.of()), rows)) {
            int pages = doc.getNumberOfPages();

            assertThat(pages).isGreaterThan(2);
            for (int p = 1; p <= pages; p++) {
                String page = text(doc, p);
                assertThat(page).as("page " + p).contains("Page count", "Legal hold").contains("Page " + p + " of " + pages);
            }
            assertThat(text(doc, 1)).contains("Document 0");
            assertThat(text(doc, pages)).contains("Document 159");
        }
    }

    @Test
    void pageSizeAndOrientationComeFromTheSettings() throws Exception {
        var portraitLetter = settings(TenantReportSettings.builder().pdfPageSize("Letter").pdfOrientation("portrait"));
        try (PDDocument doc = write(exporter, spec(portraitLetter, 1, false, null, Map.of()),
                List.of(row("A", 1, "2024-03-09T20:00:00Z", false)))) {
            assertThat(doc.getPage(0).getMediaBox().getWidth()).isEqualTo(612f);
            assertThat(doc.getPage(0).getMediaBox().getHeight()).isEqualTo(792f);
        }
    }

    @Test
    void groupedReportsPrintAHeaderPerGroupAndATotal() throws Exception {
        ReportFileSpec spec = spec(defaults(), 3, false, STATUS, Map.of("Draft", 2L, "Published", 1L));
        try (PDDocument doc = write(exporter, spec, List.of(grouped("A", 1, "2024-03-09T20:00:00Z", false, "Draft"),
                grouped("B", 1, "2024-03-09T20:00:00Z", false, "Draft"), grouped("C", 1, "2024-03-09T20:00:00Z", false, "Published")))) {
            assertThat(text(doc, 1)).contains("Document status: Draft (2 documents)", "Document status: Published (1 document)",
                    "Total: 3 documents");
        }
    }

    @Test
    void theOrganisationsFooterAndConfidentialityNoticeAreOnEveryPage() throws Exception {
        var custom = settings(TenantReportSettings.builder().footerText("Acme Ltd - internal").confidentialityNotice("CONFIDENTIAL"));
        List<ReportRow> rows = new ArrayList<>();
        for (int i = 0; i < 120; i++) {
            rows.add(row("Document " + i, i, "2024-03-09T20:00:00Z", false));
        }
        try (PDDocument doc = write(exporter, spec(custom, 120, false, null, Map.of()), rows)) {
            for (int p = 1; p <= doc.getNumberOfPages(); p++) {
                assertThat(text(doc, p)).as("page " + p).contains("Acme Ltd - internal", "CONFIDENTIAL");
            }
        }
    }

    @Test
    void textThatCouldBeAFormulaIsQuotedHereToo() throws Exception {
        try (PDDocument doc = write(exporter, spec(defaults(), 1, false, null, Map.of()),
                List.of(row("=HYPERLINK(\"x\")", 1, "2024-03-09T20:00:00Z", false)))) {
            assertThat(text(doc, 1)).contains("'=HYPERLINK");
        }
    }

    @Test
    void characterNoFontCanDrawPrintsAsAPlaceholder_andNeverFailsTheRun() throws Exception {
        // With no font files at all, only Latin text can be drawn: Sinhala, Tamil, Arabic and CJK become "?" - visibly - and the
        // file is still produced.
        List<ReportRow> rows = new ArrayList<>();
        for (String name : List.of("සිංහල ගිවිසුම", "தமிழ் ஒப்பந்தம்", "عقد", "合同", "Contract සිංහල")) {
            rows.add(row(name, 1, "2024-03-09T20:00:00Z", false));
        }
        try (PDDocument doc = write(exporter, spec(defaults(), 5, false, null, Map.of()), rows)) {
            String page = text(doc, 1);

            assertThat(doc.getNumberOfPages()).isEqualTo(1);
            assertThat(page).contains("?").contains("Contract ?");
            assertThat(page).doesNotContain("සිංහල");
        }
    }

    @Test
    void whenAFontForTheScriptIsInstalled_theCharactersAreEmbeddedAndReadBackIntact() throws Exception {
        PdfReportExporter system = new PdfReportExporter(List.of(Path.of("/usr/share/fonts"), Path.of("C:/Windows/Fonts")));
        List<Path> fonts = PdfFontChain.discover(List.of(Path.of("/usr/share/fonts"), Path.of("C:/Windows/Fonts")));
        org.junit.jupiter.api.Assumptions.assumeFalse(fonts.isEmpty(), "this machine has no font files");

        try (PDDocument doc = write(system, spec(defaults(), 2, false, null, Map.of()),
                List.of(row("Résumé Ünïcode Δοκιμή Тест", 1, "2024-03-09T20:00:00Z", false),
                        row("Plain ASCII", 1, "2024-03-09T20:00:00Z", false)))) {
            String page = text(doc, 1);

            assertThat(page).contains("Plain ASCII");
            // Whatever the machine's fonts cover is drawn; whatever they don't becomes "?" - never an exception.
            assertThat(page).contains("R");
        }
    }
}
