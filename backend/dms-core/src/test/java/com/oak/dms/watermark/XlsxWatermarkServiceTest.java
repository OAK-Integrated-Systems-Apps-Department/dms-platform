package com.oak.dms.watermark;

import com.oak.dms.watermark.config.WatermarkProperties;
import com.oak.dms.watermark.dto.WatermarkSpec;
import com.oak.dms.watermark.enums.WatermarkPageScope;
import com.oak.dms.watermark.enums.WatermarkPlacement;
import com.oak.dms.watermark.exception.WatermarkException;
import com.oak.dms.watermark.format.FontProvider;
import com.oak.dms.watermark.format.WatermarkOptions;
import com.oak.dms.watermark.format.WatermarkRenderer;
import com.oak.dms.watermark.format.XlsxWatermarkService;
import org.apache.poi.ss.usermodel.ClientAnchor;
import org.apache.poi.ss.usermodel.SheetVisibility;
import org.apache.poi.xssf.usermodel.XSSFChartSheet;
import org.apache.poi.xssf.usermodel.XSSFPicture;
import org.apache.poi.xssf.usermodel.XSSFShape;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Spreadsheet evidence: a picture on every worksheet that can carry one,
 * nothing that was already drawn on the sheet disturbed, and an anchor that
 * cannot be dragged around by a row insert.
 */
class XlsxWatermarkServiceTest {

    private XlsxWatermarkService service;
    private WatermarkProperties properties;

    @BeforeEach
    void setUp() {
        properties = new WatermarkProperties();
        WatermarkRenderer renderer = new WatermarkRenderer(new FontProvider(properties), properties);
        service = new XlsxWatermarkService(renderer, properties);
    }

    private WatermarkSpec spec(WatermarkPageScope scope) {
        return new WatermarkSpec("CONFIDENTIAL", 40, 25, -45, "#808080",
                WatermarkPlacement.TILED, scope);
    }

    private byte[] run(byte[] source, WatermarkSpec spec) throws Exception {
        return service.watermark(new ByteArrayInputStream(source),
                new WatermarkOptions(spec, OfficeFixtures.XLSX_MIME, "book.xlsx"));
    }

    @Test
    void everyWorksheetGetsAPictureIncludingTheHiddenOne() throws Exception {
        byte[] output = run(OfficeFixtures.richXlsx(), spec(WatermarkPageScope.ALL_PAGES));

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(output))) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(4);
            for (int index = 0; index < workbook.getNumberOfSheets(); index++) {
                XSSFSheet sheet = workbook.getSheetAt(index);
                assertThat(watermarksOn(sheet))
                        .describedAs("sheet '%s' (%s) must carry a watermark - a hidden sheet holds "
                                        + "data like any other and unhiding it is one right-click",
                                sheet.getSheetName(), workbook.getSheetVisibility(index))
                        .hasSize(1);
            }
            assertThat(workbook.getSheetVisibility(2)).isEqualTo(SheetVisibility.HIDDEN);
        }
    }

    /**
     * Asserted on the drawing XML rather than through
     * {@code ClientAnchor.getAnchorType()}, which is a trap: POI writes
     * {@code editAs="absolute"} correctly but does NOT read it back - a
     * re-opened anchor always reports {@code MOVE_AND_RESIZE} because
     * {@code XSSFClientAnchor}'s read path never looks at the attribute.
     * Asserting on the round-tripped enum therefore fails against a
     * perfectly correct file, and "fixing" the service to satisfy it would
     * have meant writing the wrong anchor. What Excel reads is the
     * attribute, so that is what this checks.
     */
    @Test
    void theWatermarkIsPinnedToTheSheetSoEditingCannotDragItAround() throws Exception {
        byte[] output = run(OfficeFixtures.plainXlsx(), spec(WatermarkPageScope.ALL_PAGES));

        String drawing = new String(OfficeFixtures.entry(output, "xl/drawings/drawing1.xml"),
                java.nio.charset.StandardCharsets.UTF_8);
        assertThat(drawing)
                .describedAs("anything else lets a row insert or a filter move or fold the mark")
                .contains("editAs=\"absolute\"");

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(output))) {
            XSSFPicture watermark = watermarksOn(workbook.getSheetAt(0)).get(0);
            assertThat((int) watermark.getClientAnchor().getCol1()).isZero();
            assertThat((int) watermark.getClientAnchor().getRow1()).isZero();
        }
    }

    @Test
    void existingDrawingsChartsAndPicturesAreLeftWhereTheyWere() throws Exception {
        byte[] source = OfficeFixtures.richXlsx();
        byte[] output = run(source, spec(WatermarkPageScope.ALL_PAGES));

        try (XSSFWorkbook before = new XSSFWorkbook(new ByteArrayInputStream(source));
             XSSFWorkbook after = new XSSFWorkbook(new ByteArrayInputStream(output))) {
            XSSFSheet chartSheetBefore = before.getSheet("Charts");
            XSSFSheet chartSheetAfter = after.getSheet("Charts");

            int shapesBefore = chartSheetBefore.getDrawingPatriarch().getShapes().size();
            int shapesAfter = chartSheetAfter.getDrawingPatriarch().getShapes().size();
            assertThat(shapesAfter)
                    .describedAs("the watermark must be ADDED to the existing drawing, not replace it")
                    .isEqualTo(shapesBefore + 1);
            assertThat(chartSheetAfter.getDrawingPatriarch().getCharts())
                    .describedAs("the chart must survive")
                    .hasSameSizeAs(chartSheetBefore.getDrawingPatriarch().getCharts());
            assertThat(chartSheetAfter.getRow(0).getCell(0).getNumericCellValue())
                    .describedAs("and no cell may be touched")
                    .isEqualTo(1d);
        }
    }

    @Test
    void aChartSheetIsSkippedRatherThanFailingTheJob() throws Exception {
        byte[] source = OfficeFixtures.xlsxWithChartSheet();
        try (XSSFWorkbook check = new XSSFWorkbook(new ByteArrayInputStream(source))) {
            assertThat(check.getSheetAt(1))
                    .describedAs("the fixture must really be a chart sheet or this test proves nothing")
                    .isInstanceOf(XSSFChartSheet.class);
        }

        byte[] output = run(source, spec(WatermarkPageScope.ALL_PAGES));

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(output))) {
            assertThat(watermarksOn(workbook.getSheetAt(0))).hasSize(1);
            assertThat(workbook.getSheetAt(1)).isInstanceOf(XSSFChartSheet.class);
        }
    }

    @Test
    void theMarkIsClampedOnASheetWithAnAbsurdlyWideUsedRange() throws Exception {
        byte[] output = run(OfficeFixtures.richXlsx(), spec(WatermarkPageScope.ALL_PAGES));

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(output))) {
            XSSFPicture watermark = watermarksOn(workbook.getSheet("Wide")).get(0);
            assertThat((int) watermark.getClientAnchor().getCol2())
                    .describedAs("a used range 2000 columns wide must not produce a 2000-column picture")
                    .isLessThanOrEqualTo(properties.getLimits().getMaxSheetWatermarkColumns());
            assertThat((int) watermark.getClientAnchor().getRow2())
                    .isLessThanOrEqualTo(properties.getLimits().getMaxSheetWatermarkRows());
        }
    }

    @Test
    void oneImageIsEmbeddedForTheWholeWorkbookHoweverManySheetsUseIt() throws Exception {
        byte[] output = run(OfficeFixtures.xlsxWithSheets(12), spec(WatermarkPageScope.ALL_PAGES));

        List<String> media = OfficeFixtures.entryNames(output).stream()
                .filter(name -> name.startsWith("xl/media/"))
                .toList();
        assertThat(media)
                .describedAs("twelve sheets must share one picture part, not carry twelve copies")
                .hasSize(1);
    }

    @Test
    void firstPageOnlyMarksTheFirstVisibleSheetAndNothingElse() throws Exception {
        byte[] source = OfficeFixtures.xlsx(workbook -> {
            workbook.createSheet("Hidden").createRow(0).createCell(0).setCellValue("h");
            workbook.createSheet("Visible").createRow(0).createCell(0).setCellValue("v");
            workbook.createSheet("Also").createRow(0).createCell(0).setCellValue("a");
            workbook.setSheetVisibility(0, SheetVisibility.HIDDEN);
        });

        byte[] output = run(source, spec(WatermarkPageScope.FIRST_PAGE_ONLY));

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(output))) {
            assertThat(watermarksOn(workbook.getSheet("Hidden")))
                    .describedAs("a hidden sheet is not the FIRST VISIBLE one")
                    .isEmpty();
            assertThat(watermarksOn(workbook.getSheet("Visible"))).hasSize(1);
            assertThat(watermarksOn(workbook.getSheet("Also"))).isEmpty();
        }
    }

    @Test
    void theOriginalBytesAreNeverModified() throws Exception {
        byte[] source = OfficeFixtures.richXlsx();
        byte[] copy = source.clone();

        run(source, spec(WatermarkPageScope.ALL_PAGES));

        assertThat(source).isEqualTo(copy);
    }

    // ------------------------------------------------------- refusals

    @Test
    void aSignedWorkbookIsRefused() {
        byte[] signed = OfficeFixtures.signed(OfficeFixtures.plainXlsx());

        assertThatThrownBy(() -> run(signed, spec(WatermarkPageScope.ALL_PAGES)))
                .isInstanceOf(WatermarkException.class)
                .hasFieldOrPropertyWithValue("code", "WATERMARK_OOXML_SIGNED");
    }

    @Test
    void aPasswordProtectedWorkbookIsRefused() {
        byte[] locked = OfficeFixtures.passwordProtected();

        assertThatThrownBy(() -> run(locked, spec(WatermarkPageScope.ALL_PAGES)))
                .isInstanceOf(WatermarkException.class)
                .hasFieldOrPropertyWithValue("code", "WATERMARK_OOXML_ENCRYPTED");
    }

    @Test
    void aTruncatedWorkbookIsACleanRefusal() {
        byte[] cut = OfficeFixtures.truncated(OfficeFixtures.richXlsx());

        assertThatThrownBy(() -> run(cut, spec(WatermarkPageScope.ALL_PAGES)))
                .isInstanceOf(WatermarkException.class)
                .hasFieldOrPropertyWithValue("status", HttpStatus.UNPROCESSABLE_CONTENT)
                .hasMessageNotContainingAny("Exception", "org.apache");
    }

    @Test
    void tooManySheetsIsRefusedWithTheCountInTheMessage() {
        properties.getLimits().setMaxSheets(5);
        byte[] many = OfficeFixtures.xlsxWithSheets(8);

        assertThatThrownBy(() -> run(many, spec(WatermarkPageScope.ALL_PAGES)))
                .isInstanceOf(WatermarkException.class)
                .hasFieldOrPropertyWithValue("status", HttpStatus.CONTENT_TOO_LARGE)
                .hasMessageContaining("8 sheets");
    }

    // -------------------------------------------------------- helpers

    /**
     * The pictures this service added, told apart from pictures that were
     * already on the sheet by their anchor: only the watermark starts at
     * A1 and is pinned {@code DONT_MOVE_AND_RESIZE}.
     */
    private static List<XSSFPicture> watermarksOn(XSSFSheet sheet) {
        List<XSSFPicture> found = new ArrayList<>();
        if (sheet == null || sheet.getDrawingPatriarch() == null) {
            return found;
        }
        for (XSSFShape shape : sheet.getDrawingPatriarch().getShapes()) {
            if (shape instanceof XSSFPicture picture
                    && picture.getClientAnchor().getCol1() == 0
                    && picture.getClientAnchor().getRow1() == 0) {
                found.add(picture);
            }
        }
        return found;
    }
}
