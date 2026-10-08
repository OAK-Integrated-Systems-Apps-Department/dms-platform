package com.oak.dms.watermark.format;

import com.oak.dms.watermark.config.WatermarkProperties;
import com.oak.dms.watermark.exception.WatermarkException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.ClientAnchor;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.SheetVisibility;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.util.Units;
import org.apache.poi.xssf.usermodel.XSSFChartSheet;
import org.apache.poi.xssf.usermodel.XSSFClientAnchor;
import org.apache.poi.xssf.usermodel.XSSFDrawing;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Set;

/**
 * Watermarks a spreadsheet by floating one picture over the top-left region
 * of each worksheet.
 *
 * <h2>Why a floating picture and not a header, a background or a cell</h2>
 * Excel's three obvious alternatives are all worse:
 * <ul>
 *   <li>A <b>sheet background</b> ({@code setBackgroundImage}) is only drawn
 *       on screen. It does not print and it is not exported to PDF, so the
 *       copy would look watermarked to the person who made it and be clean
 *       for everyone it was given to - the worst possible failure for this
 *       feature.</li>
 *   <li>A <b>header/footer</b> graphic prints, but is invisible in Normal
 *       view, which is how people actually read spreadsheets.</li>
 *   <li>Writing into <b>cells</b> would destroy data. Not an option at any
 *       price.</li>
 * </ul>
 * A floating picture is visible on screen, prints, survives a PDF export and
 * touches no cell. It does appear in front of the cells rather than behind
 * them, which Excel has no way to avoid - the opacity the watermark is drawn
 * at is what keeps the numbers underneath readable, and is why the minimum
 * opacity is a floor rather than something a user can set to zero.
 *
 * <h2>Anchored so it cannot drift</h2>
 * {@code DONT_MOVE_AND_RESIZE} pins the picture to the sheet rather than to
 * the cells under it. Without it, inserting a row or widening a column would
 * drag the watermark around, and a filter that hides rows would fold it up
 * into nothing.
 *
 * <h2>Which sheets get one</h2>
 * Every worksheet, including hidden and very-hidden ones - a hidden sheet
 * holds data like any other, and unhiding it is one right-click. Chart
 * sheets are skipped: they have no cell grid to anchor to, and POI models
 * them as a different kind of sheet with no drawing patriarch.
 * <p>
 * {@code first-page-only} has no meaning in a spreadsheet, which has no
 * pages until it is printed, so it is interpreted as the first VISIBLE
 * worksheet. That is said out loud in the API documentation, because the
 * alternative - quietly treating it as all-sheets - would be a surprise.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class XlsxWatermarkService implements WatermarkFormatService {

    private static final String MIME_XLSX =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    /** Excel's own default column width in pixels, used when a sheet declares none. */
    private static final int FALLBACK_COLUMN_WIDTH_PX = 64;
    /** Pixels per point at Excel's nominal 96 DPI. */
    private static final double PIXELS_PER_POINT = 96.0 / 72.0;
    /** Below this the picture would be a stamp in the corner rather than a watermark. */
    private static final int MIN_WATERMARK_PX = 240;

    private final WatermarkRenderer renderer;
    private final WatermarkProperties properties;

    @Override
    public Set<String> mimeTypes() {
        return Set.of(MIME_XLSX);
    }

    @Override
    public byte[] watermark(InputStream input, WatermarkOptions options) throws IOException {
        byte[] source = input.readAllBytes();
        OoxmlGuards.check(source, properties.getLimits());

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(source))) {
            int sheets = workbook.getNumberOfSheets();
            if (sheets == 0) {
                throw WatermarkException.unprocessable("WATERMARK_XLSX_EMPTY",
                        "This workbook has no sheets, so there is nothing to watermark.");
            }
            int maxSheets = properties.getLimits().getMaxSheets();
            if (sheets > maxSheets) {
                throw WatermarkException.tooLarge("This workbook has " + sheets + " sheets, and "
                        + maxSheets + " is the most that can be watermarked.");
            }

            FontChoice font = renderer.resolveFont(options.spec().text());
            boolean firstOnly = options.spec().firstPageOnly();
            // Embedded ONCE for the whole workbook and referenced by every
            // sheet's drawing: a 50-sheet workbook carries one copy of the
            // image, not fifty.
            Integer pictureIndex = null;
            int marked = 0;

            for (int index = 0; index < sheets; index++) {
                XSSFSheet sheet = workbook.getSheetAt(index);
                if (sheet instanceof XSSFChartSheet) {
                    continue;   // no cell grid to anchor to
                }
                if (firstOnly && workbook.getSheetVisibility(index) != SheetVisibility.VISIBLE) {
                    continue;
                }

                Size size = sizeFor(sheet);
                if (pictureIndex == null) {
                    byte[] png = renderer.renderPng(options.spec(),
                            RenderTarget.ofPixels(size.widthPx(), size.heightPx(), size.widthPx()), font);
                    pictureIndex = workbook.addPicture(png, Workbook.PICTURE_TYPE_PNG);
                }
                stamp(sheet, pictureIndex, size);
                marked++;

                if (firstOnly) {
                    break;
                }
            }

            if (marked == 0) {
                // Every sheet was a chart sheet, or first-page-only found no
                // visible one. Saying so beats handing back a copy that
                // looks watermarked and isn't.
                throw WatermarkException.unprocessable("WATERMARK_XLSX_NO_WORKSHEET",
                        "This workbook has no worksheet that can carry a watermark.");
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream(source.length + 64 * 1024);
            workbook.write(out);
            return out.toByteArray();
        } catch (WatermarkException e) {
            throw e;
        } catch (RuntimeException e) {
            throw OoxmlGuards.unreadable("spreadsheet", e);
        }
    }

    /**
     * Adds the picture without disturbing whatever is already drawn on the
     * sheet.
     * <p>
     * {@code createDrawingPatriarch} returns the EXISTING drawing when the
     * sheet has one, so charts, images and form controls already on the
     * sheet are left exactly where they are and the watermark is simply one
     * more shape in that drawing. Creating a second drawing part would be
     * the way to lose them.
     */
    private void stamp(XSSFSheet sheet, int pictureIndex, Size size) {
        XSSFDrawing drawing = sheet.createDrawingPatriarch();
        XSSFClientAnchor anchor = drawing.createAnchor(0, 0, 0, 0, 0, 0, 0, 0);
        // Pinned to the sheet: inserting a row, widening a column or
        // applying a filter must not move or squash the mark.
        anchor.setAnchorType(ClientAnchor.AnchorType.DONT_MOVE_AND_RESIZE);
        anchor.setCol2(size.lastColumn());
        anchor.setDx2(size.dx2Emu());
        anchor.setRow2(size.lastRow());
        anchor.setDy2(size.dy2Emu());
        drawing.createPicture(anchor, pictureIndex);
    }

    /**
     * How big the mark should be on this sheet, and which cell its
     * bottom-right corner lands in.
     * <p>
     * Sized from the sheet's actual column widths and row heights so it
     * covers the data rather than a nominal rectangle - a sheet of narrow
     * columns and one of wide ones should both end up with a mark across
     * their content. Then clamped three ways: by a column count, by a row
     * count, and by an absolute pixel size. The clamps are not cosmetic. A
     * used range is routinely tens of thousands of columns wide because
     * somebody once formatted a whole row, and a picture sized to all of it
     * would be both unreadable and enormous to render.
     */
    private Size sizeFor(XSSFSheet sheet) {
        WatermarkProperties.Limits limits = properties.getLimits();
        int pixelLimit = Math.max(MIN_WATERMARK_PX, limits.getMaxSheetWatermarkPixels());

        Extent across = extend(Math.max(1, limits.getMaxSheetWatermarkColumns()), pixelLimit,
                index -> columnWidthPx(sheet, index));
        Extent down = extend(Math.max(1, limits.getMaxSheetWatermarkRows()), pixelLimit,
                index -> rowHeightPx(sheet, index));

        return new Size(across.pixels(), down.pixels(),
                across.lastIndex(), across.offsetEmu(), down.lastIndex(), down.offsetEmu());
    }

    /**
     * Walks columns (or rows) until the mark is big enough, and reports BOTH
     * where it ends and how many pixels that actually is.
     *
     * <h2>Why the two have to be computed together</h2>
     * Excel does not scale a picture to its content - it scales the content
     * to the ANCHOR. The anchor's physical size is decided entirely by the
     * cells it spans, so the image must be rendered at exactly that size or
     * Excel squashes it to fit. Working the two out independently produced
     * a watermark compressed into an illegible band a few pixels tall, which
     * every structural assertion passed: the picture was present, on every
     * sheet, anchored correctly. It was only wrong to look at.
     *
     * <h2>Why the used range is not consulted</h2>
     * It was, and that was the cause. {@code getLastRowNum()} on a sheet
     * with one row of data is 0, so the mark got one row of height. The used
     * range says where the data is, which is a different question from how
     * big a watermark should be - and a sheet with one row still deserves a
     * readable one, because somebody is about to paste into it. Rows and
     * columns past the used range exist in Excel and have the default size,
     * so anchoring into them is perfectly ordinary.
     */
    private Extent extend(int cellLimit, int pixelLimit, java.util.function.IntToDoubleFunction sizeOf) {
        double total = 0;
        for (int index = 0; index < cellLimit; index++) {
            double size = sizeOf.applyAsDouble(index);
            if (total + size >= pixelLimit) {
                // Ends part way into this cell: the offset is the remainder.
                return new Extent(pixelLimit, index, Units.pixelToEMU((int) Math.max(1, pixelLimit - total)));
            }
            total += size;
        }
        // Ran out of cells before pixels - the mark ends on a cell boundary,
        // which is what a zero offset means.
        return new Extent((int) Math.round(Math.max(MIN_WATERMARK_PX, total)), cellLimit, 0);
    }

    /**
     * @param pixels    how big the mark really is on this axis - what the
     *                  image must be rendered at
     * @param lastIndex the anchor's {@code col2} / {@code row2}
     * @param offsetEmu how far into that cell the edge falls; zero means it
     *                  ends on the boundary
     */
    private record Extent(int pixels, int lastIndex, int offsetEmu) {
    }

    private double columnWidthPx(XSSFSheet sheet, int column) {
        if (sheet.isColumnHidden(column)) {
            return 0;
        }
        float pixels = sheet.getColumnWidthInPixels(column);
        return pixels > 0 ? pixels : FALLBACK_COLUMN_WIDTH_PX;
    }

    private double rowHeightPx(XSSFSheet sheet, int index) {
        Row row = sheet.getRow(index);
        float points = row != null ? row.getHeightInPoints() : sheet.getDefaultRowHeightInPoints();
        double pixels = points * PIXELS_PER_POINT;
        return pixels > 0 ? pixels : sheet.getDefaultRowHeightInPoints() * PIXELS_PER_POINT;
    }

    /** The mark's real pixel size and the anchor that produces exactly that size. */
    private record Size(int widthPx, int heightPx, int lastColumn, int dx2Emu, int lastRow, int dy2Emu) {
    }
}
