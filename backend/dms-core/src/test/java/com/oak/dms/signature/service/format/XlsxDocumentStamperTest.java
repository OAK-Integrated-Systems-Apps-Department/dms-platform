package com.oak.dms.signature.service.format;

import com.oak.dms.signature.exception.SignatureException;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class XlsxDocumentStamperTest {

    private static final String MIME_XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final XlsxDocumentStamper stamper = new XlsxDocumentStamper(mock(OfficePreviewRenderer.class));

    @Test
    void supportsOnlyXlsx() {
        assertThat(stamper.supports(MIME_XLSX)).isTrue();
        assertThat(stamper.supports("application/pdf")).isFalse();
    }

    @Test
    void pageCount_countsVisibleNonChartSheetsOnly() throws IOException {
        byte[] workbook = workbookWith("Visible", "AlsoVisible");
        assertThat(stamper.pageCount(workbook)).isEqualTo(2);
    }

    @Test
    void pageCount_excludesHiddenSheets() throws IOException {
        byte[] workbook;
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            wb.createSheet("Visible");
            wb.createSheet("Hidden");
            wb.setSheetHidden(1, true);
            workbook = toBytes(wb);
        }
        assertThat(stamper.pageCount(workbook)).isEqualTo(1);
    }

    @Test
    void stamp_embedsAPictureOnTheChosenSheetOnly() throws IOException {
        byte[] workbook = workbookWith("First", "Second");
        byte[] stamped = stamper.stamp(workbook, 1, 0.1, 0.1, 0.3, 0.1, SignaturePngFixture.bytes());

        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(stamped))) {
            XSSFSheet first = wb.getSheet("First");
            XSSFSheet second = wb.getSheet("Second");
            assertThat(first.getDrawingPatriarch()).isNull();
            assertThat(second.getDrawingPatriarch().getShapes()).hasSize(1);
        }
    }

    @Test
    void appendBlankPage_addsOneMoreSheet() throws IOException {
        byte[] workbook = workbookWith("Only");
        byte[] withPage = stamper.appendBlankPage(workbook);
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(withPage))) {
            assertThat(wb.getNumberOfSheets()).isEqualTo(2);
        }
    }

    @Test
    void refusesACorruptFile() {
        assertThatThrownBy(() -> stamper.pageCount("not a workbook".getBytes()))
                .isInstanceOf(SignatureException.class);
    }

    private static byte[] workbookWith(String... sheetNames) throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            for (String name : sheetNames) {
                wb.createSheet(name);
            }
            return toBytes(wb);
        }
    }

    private static byte[] toBytes(XSSFWorkbook wb) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        wb.write(out);
        return out.toByteArray();
    }
}
