package com.oak.dms.importexport.matching;

import com.oak.dms.importexport.entity.ImportItem;
import com.oak.dms.importexport.exception.ImportFileAlreadyReceivedException;
import com.oak.dms.importexport.exception.ImportFileNotInBatchException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImportFilenameMatcherTest {

    private final ImportFilenameMatcher matcher = new ImportFilenameMatcher();

    @Test
    void match_differentCase_matchesTheRow() {
        ImportItem invoice = item(2, "Invoice-001.PDF");
        List<ImportItem> items = List.of(invoice, item(3, "other.pdf"));

        assertThat(matcher.match(items, "invoice-001.pdf")).isSameAs(invoice);
    }

    @Test
    void match_duplicateFilename_claimsLowestUnfilledRowThenRejectsWhenAllFilled() {
        ImportItem row5 = item(5, "scan.pdf");
        ImportItem row2 = item(2, "SCAN.pdf");
        ImportItem row9 = item(9, "Scan.PDF");
        List<ImportItem> items = List.of(row5, row9, row2);

        assertThat(matcher.match(items, "scan.pdf")).isSameAs(row2);
        row2.setStagedObjectKey("imports/t/b/" + row2.getImportItemId());

        assertThat(matcher.match(items, "scan.pdf")).isSameAs(row5);
        row5.setStagedObjectKey("imports/t/b/" + row5.getImportItemId());

        assertThat(matcher.match(items, "scan.pdf")).isSameAs(row9);
        row9.setStagedObjectKey("imports/t/b/" + row9.getImportItemId());

        assertThatThrownBy(() -> matcher.match(items, "scan.pdf"))
                .isInstanceOf(ImportFileAlreadyReceivedException.class);
    }

    @Test
    void match_filenameNotInBatch_rejects() {
        List<ImportItem> items = List.of(item(2, "a.pdf"));

        assertThatThrownBy(() -> matcher.match(items, "b.pdf"))
                .isInstanceOf(ImportFileNotInBatchException.class);
    }

    @Test
    void match_onlySkippedRowListsIt_rejectsSayingTheRowWasSkipped() {
        List<ImportItem> items = List.of(skipped(item(5, "bad.pdf")), item(6, "good.pdf"));

        assertThatThrownBy(() -> matcher.match(items, "BAD.pdf"))
                .isInstanceOf(ImportFileNotInBatchException.class)
                .hasMessage("Row 5 was skipped because of template errors, so it doesn't need a file.")
                .extracting("code").isEqualTo("IMPORT_FILE_NOT_IN_BATCH");
    }

    @Test
    void match_severalSkippedRowsListIt_namesEveryRow() {
        List<ImportItem> items = List.of(skipped(item(7, "dup.pdf")), skipped(item(3, "dup.pdf")),
                skipped(item(5, "Dup.pdf")));

        assertThatThrownBy(() -> matcher.match(items, "dup.pdf"))
                .isInstanceOf(ImportFileNotInBatchException.class)
                .hasMessage("Rows 3, 5 and 7 were skipped because of template errors, so they don't need a file.");
    }

    @Test
    void match_skippedAndOpenRowsListIt_claimsTheOpenRowEvenIfLater() {
        ImportItem open = item(8, "scan.pdf");
        List<ImportItem> items = List.of(skipped(item(2, "scan.pdf")), open);

        assertThat(matcher.match(items, "scan.pdf")).isSameAs(open);
        open.setStagedObjectKey("imports/t/b/" + open.getImportItemId());

        assertThatThrownBy(() -> matcher.match(items, "scan.pdf"))
                .isInstanceOf(ImportFileAlreadyReceivedException.class);
    }

    @Test
    void match_clientSuppliedPath_matchesOnFilenameOnly() {
        ImportItem item = item(2, "report.docx");

        assertThat(matcher.match(List.of(item), "C:\\Users\\me\\Report.DOCX")).isSameAs(item);
        assertThat(matcher.match(List.of(item), "uploads/report.docx")).isSameAs(item);
    }

    private static ImportItem item(int rowNumber, String filename) {
        return ImportItem.builder()
                .importItemId(UUID.randomUUID())
                .rowNumber(rowNumber)
                .filenameReference(filename)
                .build();
    }

    private static ImportItem skipped(ImportItem item) {
        item.setStatus(ImportItem.ImportItemStatus.failed);
        item.setSkippedAtPreview(true);
        return item;
    }
}
