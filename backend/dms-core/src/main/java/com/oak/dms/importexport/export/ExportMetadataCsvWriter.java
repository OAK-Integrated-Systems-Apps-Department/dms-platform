package com.oak.dms.importexport.export;

import com.oak.dms.importexport.csv.CsvCells;

import java.util.List;

/**
 * Writes bulk export's CSV metadata - for systems: option codes, user IDs
 * and ISO dates (see {@link ExportCell#csvText()}), cells through
 * {@link CsvCells} (formula-injection safe, UTF-8 with a BOM, the same as
 * the bulk import failure report). Skipped documents go in a file of
 * their own, so metadata.csv stays one row per exported document.
 */
public final class ExportMetadataCsvWriter {

    public static final String METADATA_FILENAME = "metadata.csv";
    public static final String SKIPPED_FILENAME = "skipped.csv";
    static final List<String> SKIPPED_HEADERS = List.of("Title", "Reason");

    private ExportMetadataCsvWriter() {
    }

    public static byte[] metadata(ExportMetadataTable table) {
        StringBuilder csv = new StringBuilder();
        CsvCells.line(csv, table.headers());
        for (List<ExportCell> row : table.rows()) {
            CsvCells.line(csv, row.stream().map(ExportCell::csvText).toList());
        }
        return CsvCells.withBom(csv.toString());
    }

    public static byte[] skipped(List<SkippedDocument> skipped) {
        StringBuilder csv = new StringBuilder();
        CsvCells.line(csv, SKIPPED_HEADERS);
        for (SkippedDocument document : skipped) {
            CsvCells.line(csv, List.of(document.title(), document.reason()));
        }
        return CsvCells.withBom(csv.toString());
    }
}
