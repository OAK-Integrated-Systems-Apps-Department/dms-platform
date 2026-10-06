package com.oak.dms.watermark;

import org.apache.poi.sl.usermodel.PictureData;
import org.apache.poi.ss.usermodel.ClientAnchor;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.SheetVisibility;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.util.Units;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFAutoShape;
import org.apache.poi.xslf.usermodel.XSLFPictureData;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFSlideLayout;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.apache.poi.xssf.usermodel.XSSFDrawing;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xwpf.model.XWPFHeaderFooterPolicy;
import org.apache.poi.xwpf.usermodel.Document;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFHeader;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageMar;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageSz;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSectPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STHdrFtr;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Office documents built in code, never checked in as binary blobs.
 *
 * <h2>Why generated rather than committed</h2>
 * A .docx in the repository is an opaque 20 KB that nobody can review, nobody
 * can diff, and nobody can adjust when a test needs one more section. Worse,
 * it hides what the test is actually about: a reader cannot tell from
 * {@code multiSectionDocx()} whether the second section is landscape unless
 * the code that made it is right there. Everything here is therefore built
 * through POI, so the fixture and its intent are the same text.
 *
 * <h2>What they deliberately contain</h2>
 * The awkward cases, because the easy ones never found a bug: sections with
 * different page sizes, headers that already have content and images in
 * them, even/odd headers, hidden sheets, chart sheets, existing drawings,
 * slides that switch master graphics off, and slides covered by an opaque
 * shape.
 */
final class OfficeFixtures {

    static final String DOCX_MIME =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    static final String XLSX_MIME =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    static final String PPTX_MIME =
            "application/vnd.openxmlformats-officedocument.presentationml.presentation";

    /** Letter portrait and Letter landscape, in twips. */
    private static final int LETTER_WIDTH = 12240;
    private static final int LETTER_HEIGHT = 15840;

    private OfficeFixtures() {
    }

    // --------------------------------------------------------------- docx

    /** One section, no headers at all - the commonest document there is. */
    static byte[] plainDocx() {
        return docx(document -> {
            for (int i = 0; i < 3; i++) {
                document.createParagraph().createRun().setText("Body paragraph " + i);
            }
        });
    }

    /**
     * Two sections with different page sizes: a portrait body and a
     * landscape appendix, which is the shape that catches a service that
     * measures the first section and assumes the rest match.
     */
    static byte[] multiSectionDocx() {
        return docx(document -> {
            XWPFParagraph body = document.createParagraph();
            body.createRun().setText("Portrait body");
            // A sectPr on a paragraph ENDS a section at that paragraph.
            CTSectPr first = body.getCTP().addNewPPr().addNewSectPr();
            pageSize(first, LETTER_WIDTH, LETTER_HEIGHT);

            document.createParagraph().createRun().setText("Landscape appendix");
            CTSectPr last = document.getDocument().getBody().addNewSectPr();
            pageSize(last, LETTER_HEIGHT, LETTER_WIDTH);
        });
    }

    /**
     * A default header that already carries a letterhead AND a logo image.
     * This is the fixture that proves the first-page carry-over: if the
     * relationship remapping is wrong, the logo is the thing that goes
     * missing.
     */
    static byte[] docxWithPopulatedDefaultHeader() {
        return docx(document -> {
            XWPFHeaderFooterPolicy policy = document.createHeaderFooterPolicy();
            XWPFHeader header = policy.createHeader(STHdrFtr.DEFAULT);
            XWPFParagraph paragraph = header.createParagraph();
            XWPFRun run = paragraph.createRun();
            run.setText("ACME LETTERHEAD");
            try {
                run.addPicture(new ByteArrayInputStream(logoPng()), Document.PICTURE_TYPE_PNG,
                        "logo.png", Units.toEMU(40), Units.toEMU(40));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            document.createParagraph().createRun().setText("Body");
        });
    }

    /** Even and odd headers switched on, with both parts populated. */
    static byte[] docxWithEvenAndOddHeaders() {
        return docx(document -> {
            document.setEvenAndOddHeadings(true);
            XWPFHeaderFooterPolicy policy = document.createHeaderFooterPolicy();
            policy.createHeader(STHdrFtr.DEFAULT).createParagraph().createRun().setText("Odd pages");
            policy.createHeader(STHdrFtr.EVEN).createParagraph().createRun().setText("Even pages");
            document.createParagraph().createRun().setText("Body");
        });
    }

    /** A section that already declares a title page and has a first-page header. */
    static byte[] docxWithTitlePage() {
        return docx(document -> {
            XWPFHeaderFooterPolicy policy = document.createHeaderFooterPolicy();
            policy.createHeader(STHdrFtr.DEFAULT).createParagraph().createRun().setText("Running head");
            policy.createHeader(STHdrFtr.FIRST).createParagraph().createRun().setText("Cover");
            document.getDocument().getBody().getSectPr().addNewTitlePg();
            document.createParagraph().createRun().setText("Body");
        });
    }

    /** More sections than any cap would allow, for the refusal test. */
    static byte[] docxWithSections(int count) {
        return docx(document -> {
            for (int i = 0; i < count - 1; i++) {
                XWPFParagraph paragraph = document.createParagraph();
                paragraph.createRun().setText("Section " + i);
                pageSize(paragraph.getCTP().addNewPPr().addNewSectPr(), LETTER_WIDTH, LETTER_HEIGHT);
            }
            document.createParagraph().createRun().setText("Last");
        });
    }

    private static void pageSize(CTSectPr section, int widthTwips, int heightTwips) {
        CTPageSz size = section.addNewPgSz();
        size.setW(BigInteger.valueOf(widthTwips));
        size.setH(BigInteger.valueOf(heightTwips));
        CTPageMar margin = section.addNewPgMar();
        margin.setTop(BigInteger.valueOf(1440));
        margin.setBottom(BigInteger.valueOf(1440));
        margin.setLeft(BigInteger.valueOf(1440));
        margin.setRight(BigInteger.valueOf(1440));
    }

    private static byte[] docx(DocxBuilder builder) {
        try (XWPFDocument document = new XWPFDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            builder.build(document);
            document.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @FunctionalInterface
    interface DocxBuilder {
        void build(XWPFDocument document) throws IOException;
    }

    // --------------------------------------------------------------- xlsx

    /** One sheet with a little data - the baseline. */
    static byte[] plainXlsx() {
        return xlsx(workbook -> {
            XSSFSheet sheet = workbook.createSheet("Data");
            for (int row = 0; row < 10; row++) {
                Row r = sheet.createRow(row);
                for (int column = 0; column < 5; column++) {
                    r.createCell(column).setCellValue("r" + row + "c" + column);
                }
            }
        });
    }

    /**
     * Several sheets including a hidden one, a sheet that already has a
     * picture and a chart on it, frozen panes, and a sheet whose used range
     * is absurdly wide - the case that would size a watermark to 16000
     * columns if nothing clamped it.
     */
    static byte[] richXlsx() {
        return xlsx(workbook -> {
            XSSFSheet first = workbook.createSheet("Summary");
            first.createRow(0).createCell(0).setCellValue("Summary");
            first.createFreezePane(1, 1);

            XSSFSheet withDrawings = workbook.createSheet("Charts");
            withDrawings.createRow(0).createCell(0).setCellValue(1);
            withDrawings.createRow(1).createCell(0).setCellValue(2);
            XSSFDrawing drawing = withDrawings.createDrawingPatriarch();
            int logo = workbook.addPicture(logoPng(), Workbook.PICTURE_TYPE_PNG);
            ClientAnchor pictureAnchor = drawing.createAnchor(0, 0, 0, 0, 3, 3, 6, 10);
            drawing.createPicture(pictureAnchor, logo);
            drawing.createChart(drawing.createAnchor(0, 0, 0, 0, 8, 1, 14, 12));

            XSSFSheet hidden = workbook.createSheet("Hidden");
            hidden.createRow(0).createCell(0).setCellValue("secret");
            workbook.setSheetVisibility(workbook.getSheetIndex(hidden), SheetVisibility.HIDDEN);

            XSSFSheet wide = workbook.createSheet("Wide");
            Row wideRow = wide.createRow(0);
            for (int column = 0; column < 2000; column += 97) {
                wideRow.createCell(column).setCellValue(column);
            }
        });
    }

    static byte[] xlsxWithSheets(int count) {
        return xlsx(workbook -> {
            for (int i = 0; i < count; i++) {
                workbook.createSheet("S" + i).createRow(0).createCell(0).setCellValue(i);
            }
        });
    }

    /**
     * A workbook whose SECOND sheet is a real chart sheet.
     * <p>
     * POI can read chart sheets ({@code XSSFChartSheet}) but has no public
     * API to create one, so the package is rewritten after the fact: the
     * worksheet part is replaced by a {@code chartsheet} part and the
     * content types and workbook relationships re-pointed at it. Fiddly, but
     * the alternative is asserting that a branch is skipped without ever
     * giving it something to skip.
     */
    static byte[] xlsxWithChartSheet() {
        byte[] source = xlsx(workbook -> {
            workbook.createSheet("Data").createRow(0).createCell(0).setCellValue("x");
            workbook.createSheet("Chart").createRow(0).createCell(0).setCellValue("y");
        });
        String chartsheetXml = """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <chartsheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                  <sheetPr/><sheetViews><sheetView workbookViewId="0"/></sheetViews>
                </chartsheet>
                """;
        return rewriteZip(source, (name, bytes) -> {
            if (name.equals("xl/worksheets/sheet2.xml")) {
                return new Rewritten("xl/chartsheets/sheet1.xml",
                        chartsheetXml.getBytes(StandardCharsets.UTF_8));
            }
            if (name.equals("[Content_Types].xml")) {
                String xml = new String(bytes, StandardCharsets.UTF_8)
                        .replace("<Override PartName=\"/xl/worksheets/sheet2.xml\" ContentType=\""
                                        + "application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>",
                                "<Override PartName=\"/xl/chartsheets/sheet1.xml\" ContentType=\""
                                        + "application/vnd.openxmlformats-officedocument.spreadsheetml.chartsheet+xml\"/>");
                return new Rewritten(name, xml.getBytes(StandardCharsets.UTF_8));
            }
            if (name.equals("xl/_rels/workbook.xml.rels")) {
                // Both the Target AND the Type have to change. Rewriting
                // only the Target leaves POI reading a chartsheet part
                // through the worksheet schema, which fails with "not a
                // valid worksheet document" - and makes the test look like
                // a bug in the service rather than in the fixture.
                // Attribute order is not guaranteed, so the whole
                // Relationship element is matched and rebuilt.
                String xml = new String(bytes, StandardCharsets.UTF_8);
                java.util.regex.Matcher matcher = java.util.regex.Pattern
                        .compile("<Relationship [^>]*Target=\"worksheets/sheet2\\.xml\"[^>]*/>")
                        .matcher(xml);
                if (matcher.find()) {
                    String id = java.util.regex.Pattern.compile("Id=\"([^\"]+)\"")
                            .matcher(matcher.group()).results().findFirst()
                            .orElseThrow().group(1);
                    xml = xml.replace(matcher.group(), "<Relationship Id=\"" + id + "\" Type=\""
                            + "http://schemas.openxmlformats.org/officeDocument/2006/relationships/chartsheet"
                            + "\" Target=\"chartsheets/sheet1.xml\"/>");
                }
                return new Rewritten(name, xml.getBytes(StandardCharsets.UTF_8));
            }
            return new Rewritten(name, bytes);
        });
    }

    static byte[] xlsx(XlsxBuilder builder) {
        try (XSSFWorkbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            builder.build(workbook);
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @FunctionalInterface
    interface XlsxBuilder {
        void build(XSSFWorkbook workbook) throws IOException;
    }

    // --------------------------------------------------------------- pptx

    static byte[] plainPptx(int slides) {
        return pptx(show -> {
            XSLFSlideLayout layout = show.getSlideMasters().get(0)
                    .getLayout(org.apache.poi.xslf.usermodel.SlideLayout.TITLE_AND_CONTENT);
            for (int i = 0; i < slides; i++) {
                XSLFSlide slide = layout != null ? show.createSlide(layout) : show.createSlide();
                XSLFTextBox box = slide.createTextBox();
                box.setAnchor(new Rectangle2D.Double(50, 50, 300, 50));
                box.setText("Slide " + (i + 1));
            }
        });
    }

    /** Slide 2 switches master graphics off; slide 3 is covered by an opaque rectangle. */
    static byte[] pptxWithHiddenMasterAndOpaqueCover() {
        return pptx(show -> {
            XSLFSlide normal = show.createSlide();
            normal.createTextBox().setText("Normal");

            XSLFSlide noMaster = show.createSlide();
            noMaster.setFollowMasterGraphics(false);
            noMaster.createTextBox().setText("No master graphics");

            XSLFSlide covered = show.createSlide();
            java.awt.Dimension page = show.getPageSize();
            XSLFAutoShape cover = covered.createAutoShape();
            cover.setAnchor(new Rectangle2D.Double(0, 0, page.getWidth(), page.getHeight()));
            cover.setFillColor(new Color(10, 40, 90));
            XSLFTextBox caption = covered.createTextBox();
            caption.setAnchor(new Rectangle2D.Double(60, 60, 400, 60));
            caption.setText("Section divider");
        });
    }

    static byte[] pptxWithSlides(int count) {
        return pptx(show -> {
            for (int i = 0; i < count; i++) {
                show.createSlide().createTextBox().setText("S" + i);
            }
        });
    }

    private static byte[] pptx(PptxBuilder builder) {
        try (XMLSlideShow show = new XMLSlideShow();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            builder.build(show);
            show.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @FunctionalInterface
    interface PptxBuilder {
        void build(XMLSlideShow show) throws IOException;
    }

    // ------------------------------------------------------- bad packages

    /**
     * The same package with a digital-signature part bolted on. Enough to
     * exercise the refusal: the guard looks for the part's existence, not
     * for a signature that verifies, because any rewrite breaks it either
     * way.
     */
    static byte[] signed(byte[] source) {
        return rewriteZip(source, Rewritten::new, List.of(
                new Extra("_xmlsignatures/sig1.xml",
                        "<Signature xmlns=\"http://www.w3.org/2000/09/xmldsig#\"/>"
                                .getBytes(StandardCharsets.UTF_8))));
    }

    /** An OLE2 compound file, which is what a password-protected OOXML really is. */
    static byte[] passwordProtected() {
        byte[] file = new byte[4096];
        byte[] signature = {(byte) 0xD0, (byte) 0xCF, (byte) 0x11, (byte) 0xE0,
                (byte) 0xA1, (byte) 0xB1, (byte) 0x1A, (byte) 0xE1};
        System.arraycopy(signature, 0, file, 0, signature.length);
        return file;
    }

    static byte[] truncated(byte[] source) {
        byte[] cut = new byte[source.length / 3];
        System.arraycopy(source, 0, cut, 0, cut.length);
        return cut;
    }

    /**
     * A package whose entries inflate to far more than the stored size - the
     * shape of a zip bomb, built from a highly compressible run of zeros so
     * the guard has something real to count.
     */
    static byte[] zipBomb() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zip.write("<Types/>".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            byte[] chunk = new byte[1024 * 1024];   // all zeros, compresses ~1000:1
            for (int i = 0; i < 400; i++) {
                zip.write(chunk);
            }
            zip.closeEntry();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    // ------------------------------------------------------------- shared

    /** A tiny recognisable image, used as an existing logo in headers and sheets. */
    static byte[] logoPng() {
        BufferedImage image = new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.RED);
        graphics.fillRect(0, 0, 32, 32);
        graphics.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, "png", out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    record Rewritten(String name, byte[] bytes) {
    }

    record Extra(String name, byte[] bytes) {
    }

    @FunctionalInterface
    interface EntryRewriter {
        Rewritten rewrite(String name, byte[] bytes);
    }

    private static byte[] rewriteZip(byte[] source, EntryRewriter rewriter) {
        return rewriteZip(source, rewriter, List.of());
    }

    /** Copies a package entry by entry, letting the caller rename, replace or add parts. */
    private static byte[] rewriteZip(byte[] source, EntryRewriter rewriter, List<Extra> extras) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(source.length + 1024);
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(source));
             ZipOutputStream zip = new ZipOutputStream(out)) {
            ZipEntry entry;
            while ((entry = in.getNextEntry()) != null) {
                Rewritten rewritten = rewriter.rewrite(entry.getName(), in.readAllBytes());
                zip.putNextEntry(new ZipEntry(rewritten.name()));
                zip.write(rewritten.bytes());
                zip.closeEntry();
            }
            for (Extra extra : extras) {
                zip.putNextEntry(new ZipEntry(extra.name()));
                zip.write(extra.bytes());
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    /** Every entry name in a package - what the structural assertions read. */
    static List<String> entryNames(byte[] package_) {
        List<String> names = new ArrayList<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(package_))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                names.add(entry.getName());
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return names;
    }

    /** One part's bytes, for assertions that need to read the XML itself. */
    static byte[] entry(byte[] package_, String name) {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(package_))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.getName().equals(name)) {
                    return zip.readAllBytes();
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        throw new IllegalArgumentException("No part named " + name + " in " + entryNames(package_));
    }

    static XSLFPictureData firstPicture(XMLSlideShow show) {
        List<XSLFPictureData> pictures = show.getPictureData();
        return pictures.isEmpty() ? null : pictures.get(0);
    }

    static PictureData.PictureType pngType() {
        return PictureData.PictureType.PNG;
    }
}
