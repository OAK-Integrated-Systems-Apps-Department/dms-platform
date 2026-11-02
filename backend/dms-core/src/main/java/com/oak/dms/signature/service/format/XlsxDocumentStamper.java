package com.oak.dms.signature.service.format;

import com.oak.dms.signature.exception.SignatureException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.ClientAnchor;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFClientAnchor;
import org.apache.poi.xssf.usermodel.XSSFDrawing;
import org.apache.poi.xssf.usermodel.XSSFPicture;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * "Page" means worksheet here - Excel has no other page concept POI can
 * address directly. A page's preview goes through {@link OfficePreviewRenderer}
 * (LibreOffice's own pagination of the whole workbook); a known limitation,
 * called out rather than hidden: if an earlier sheet prints across more than
 * one PDF page, later sheets' preview images shift by that many pages. The
 * STAMP itself never goes through that conversion - it's always placed
 * natively into the original workbook's own sheet.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class XlsxDocumentStamper implements DocumentFormatStamper {

    private static final String MIME_XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final OfficePreviewRenderer previewRenderer;

    @Override
    public boolean supports(String mimeType) {
        return MIME_XLSX.equals(mimeType);
    }

    @Override
    public int pageCount(byte[] source) {
        try (XSSFWorkbook workbook = open(source)) {
            return eligibleSheets(workbook).size();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException("Could not read a workbook for signature placement", e);
        }
    }

    @Override
    public byte[] renderPagePreview(byte[] source, int page, int width) {
        requirePage(pageCount(source), page);
        byte[] pdf = previewRenderer.toPdf(source, "xlsx");
        int pdfPages = previewRenderer.pageCount(pdf);
        // Best-effort 1:1 sheet-to-PDF-page mapping - see this class's own doc comment.
        int pdfPage = Math.min(page, Math.max(0, pdfPages - 1));
        return previewRenderer.renderPage(pdf, pdfPage, width);
    }

    @Override
    public byte[] stamp(byte[] source, int page, double xRatio, double yRatio, double widthRatio, double heightRatio,
                        byte[] signaturePng) {
        try (XSSFWorkbook workbook = open(source)) {
            List<XSSFSheet> sheets = eligibleSheets(workbook);
            requirePage(sheets.size(), page);
            XSSFSheet sheet = sheets.get(page);

            BufferedImage markImage = ImageIO.read(new ByteArrayInputStream(signaturePng));
            if (markImage == null) {
                throw SignatureException.badRequest("SIGNATURE_PLACEMENT_IMAGE_DAMAGED",
                        "The signature image could not be read.");
            }
            int pictureIndex = workbook.addPicture(signaturePng, Workbook.PICTURE_TYPE_PNG);

            // Excel has no absolute point coordinate system for a drawing the way a PDF page does - an
            // anchor is expressed in cells. The sheet's used range stands in for "the page" so the
            // x/y/width ratios land in roughly the place the preview showed, same spirit as the PDF/PPTX
            // stampers' own ratio-of-page-size math.
            CellRangeAddress used = usedRange(sheet);
            int firstCol = used.getFirstColumn();
            int lastCol = Math.max(firstCol, used.getLastColumn());
            int firstRow = used.getFirstRow();
            int lastRow = Math.max(firstRow, used.getLastRow());
            int col1 = firstCol + (int) Math.round(xRatio * (lastCol - firstCol + 1));
            int row1 = firstRow + (int) Math.round(yRatio * (lastRow - firstRow + 1));
            int colSpan = Math.max(1, (int) Math.round(widthRatio * (lastCol - firstCol + 1)));
            double aspect = (double) markImage.getHeight() / markImage.getWidth();
            int rowSpan = Math.max(1, (int) Math.round(colSpan * aspect));

            XSSFDrawing drawing = sheet.createDrawingPatriarch();
            ClientAnchor anchor = drawing.createAnchor(0, 0, 0, 0, col1, row1, col1 + colSpan, row1 + rowSpan);
            anchor.setAnchorType(ClientAnchor.AnchorType.MOVE_AND_RESIZE);
            XSSFPicture picture = drawing.createPicture(anchor, pictureIndex);
            picture.resize();

            ByteArrayOutputStream out = new ByteArrayOutputStream(source.length + 64 * 1024);
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            log.debug("Could not stamp a workbook with a signature", e);
            throw SignatureException.badRequest("SIGNATURE_PLACEMENT_XLSX_DAMAGED",
                    "This workbook could not be read - it may be damaged or incomplete.");
        } catch (RuntimeException e) {
            throw unreadable(e);
        }
    }

    @Override
    public byte[] appendBlankPage(byte[] source) {
        try (XSSFWorkbook workbook = open(source)) {
            String name = workbook.createSheet().getSheetName();
            log.debug("Appended blank worksheet {} for signature placement", name);
            ByteArrayOutputStream out = new ByteArrayOutputStream(source.length + 8 * 1024);
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw SignatureException.badRequest("SIGNATURE_PLACEMENT_XLSX_DAMAGED",
                    "This workbook could not be read - it may be damaged or incomplete.");
        } catch (RuntimeException e) {
            throw unreadable(e);
        }
    }

    /** Visible, non-chart sheets only - a hidden sheet isn't something a signer can see to drag onto. */
    private static List<XSSFSheet> eligibleSheets(XSSFWorkbook workbook) {
        List<XSSFSheet> eligible = new ArrayList<>();
        for (int i = 0; i < workbook.getNumberOfSheets(); i++) {
            if (workbook.isSheetHidden(i) || workbook.isSheetVeryHidden(i)) {
                continue;
            }
            XSSFSheet sheet = workbook.getSheetAt(i);
            if (!(sheet instanceof org.apache.poi.xssf.usermodel.XSSFChartSheet)) {
                eligible.add(sheet);
            }
        }
        return eligible;
    }

    private static CellRangeAddress usedRange(XSSFSheet sheet) {
        int lastRow = Math.max(0, sheet.getLastRowNum());
        int lastCol = 0;
        for (int r = sheet.getFirstRowNum(); r <= lastRow; r++) {
            org.apache.poi.ss.usermodel.Row row = sheet.getRow(r);
            if (row != null) {
                lastCol = Math.max(lastCol, row.getLastCellNum());
            }
        }
        return new CellRangeAddress(0, Math.max(lastRow, 20), 0, Math.max(lastCol, 10));
    }

    private static XSSFWorkbook open(byte[] source) throws IOException {
        OoxmlSanityCheck.check(source, "workbook");
        try {
            return new XSSFWorkbook(new ByteArrayInputStream(source));
        } catch (RuntimeException e) {
            throw unreadable(e);
        }
    }

    private static void requirePage(int pageCount, int page) {
        if (page < 0 || page >= pageCount) {
            throw SignatureException.badRequest("SIGNATURE_PLACEMENT_PAGE_OUT_OF_RANGE",
                    "That page doesn't exist in this document.");
        }
    }

    private static SignatureException unreadable(RuntimeException cause) {
        log.warn("Could not process a workbook for signature placement - refusing it", cause);
        return SignatureException.badRequest("SIGNATURE_PLACEMENT_XLSX_DAMAGED",
                "This workbook could not be read - it may be damaged or in a format this server doesn't recognise.");
    }
}
