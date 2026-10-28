package com.oak.dms.watermark;

import com.oak.dms.watermark.config.WatermarkProperties;
import com.oak.dms.watermark.dto.WatermarkSpec;
import com.oak.dms.watermark.enums.WatermarkPageScope;
import com.oak.dms.watermark.enums.WatermarkPlacement;
import com.oak.dms.watermark.exception.WatermarkException;
import com.oak.dms.watermark.format.FontProvider;
import com.oak.dms.watermark.format.PdfWatermarkService;
import com.oak.dms.watermark.format.WatermarkOptions;
import com.oak.dms.watermark.format.WatermarkRenderer;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The PDF format service, verified by RENDERING the output pages with
 * PDFBox's own renderer and comparing pixels against the same page rendered
 * from the input.
 * <p>
 * Checking the file's structure instead would pass on a watermark that was
 * embedded but drawn off the page, behind the content, or rotated into the
 * margin - all of which produce a perfectly valid PDF with nothing visible
 * on it. Rendering is also what makes the {@code /Rotate} and CropBox cases
 * testable at all, since PDFRenderer applies both exactly as a viewer would.
 */
class PdfWatermarkServiceTest {

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    private final WatermarkProperties properties = new WatermarkProperties();
    private final PdfWatermarkService service =
            new PdfWatermarkService(new WatermarkRenderer(new FontProvider(properties), properties), properties);

    @Test
    void itHandlesPdfAndNothingElse() {
        assertThat(service.supports("application/pdf")).isTrue();
        assertThat(service.supports("image/png")).isFalse();
        assertThat(service.supports(null)).isFalse();
    }

    @Test
    void theOutputIsAValidPdfWithTheSamePagesAndAVisibleMarkOnEachOne() throws IOException {
        byte[] source = pdf(3, PDRectangle.A4, 0);
        byte[] marked = run(source, WatermarkPlacement.TILED, WatermarkPageScope.ALL_PAGES);

        try (PDDocument output = Loader.loadPDF(marked)) {
            assertThat(output.getNumberOfPages()).isEqualTo(3);
        }
        for (int page = 0; page < 3; page++) {
            assertThat(changedPixels(source, marked, page))
                    .describedAs("page %d should carry the mark", page)
                    .isGreaterThan(1000);
        }
    }

    @Test
    void firstPageOnlyMarksPageOneAndLeavesTheRestUntouched() throws IOException {
        byte[] source = pdf(4, PDRectangle.A4, 0);
        byte[] marked = run(source, WatermarkPlacement.TILED, WatermarkPageScope.FIRST_PAGE_ONLY);

        assertThat(changedPixels(source, marked, 0)).isGreaterThan(1000);
        for (int page = 1; page < 4; page++) {
            assertThat(changedPixels(source, marked, page))
                    .describedAs("page %d must be left alone", page)
                    .isZero();
        }
    }

    @ParameterizedTest
    @EnumSource(WatermarkPlacement.class)
    void everyPlacementDrawsSomethingVisibleOnThePage(WatermarkPlacement placement) throws IOException {
        for (WatermarkPageScope scope : WatermarkPageScope.values()) {
            byte[] source = pdf(1, PDRectangle.A4, 0);
            assertThat(changedPixels(source, run(source, placement, scope), 0))
                    .describedAs("%s / %s", placement, scope)
                    .isGreaterThan(200);
        }
    }

    @Test
    void theExistingContentSurvives() throws IOException {
        // The mark is appended, never a rewrite - the original text has to
        // still be there, and still be text.
        byte[] source = pdf(1, PDRectangle.A4, 0);
        byte[] marked = run(source, WatermarkPlacement.CORNER, WatermarkPageScope.ALL_PAGES);

        org.apache.pdfbox.text.PDFTextStripper stripper = new org.apache.pdfbox.text.PDFTextStripper();
        try (PDDocument output = Loader.loadPDF(marked)) {
            assertThat(stripper.getText(output)).contains("Original content");
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 90, 180, 270})
    void aPageWithARotateStillGetsAnUprightMarkInsideItsBounds(int rotation) throws IOException {
        // The failure this guards: a mark drawn in unrotated user space on a
        // /Rotate 90 page appears running up the side of the page, or half
        // off it. Rendering applies /Rotate exactly as a viewer does, so a
        // mark that landed wrong shows up as missing ink.
        byte[] source = pdf(1, PDRectangle.A4, rotation);
        byte[] marked = run(source, WatermarkPlacement.CENTERED, WatermarkPageScope.ALL_PAGES);

        BufferedImage rendered = render(marked, 0);
        assertThat(changedPixels(source, marked, 0))
                .describedAs("/Rotate %d should still be marked", rotation)
                .isGreaterThan(500);
        // And the ink is in the middle of what the reader sees, not shoved
        // against an edge by a mis-signed rotation.
        assertThat(inkIn(render(source, 0), rendered, 0.3, 0.3, 0.7, 0.7)).isGreaterThan(200);
    }

    @Test
    void aCropBoxThatDoesNotStartAtTheOriginIsHonoured() throws IOException {
        // A trimmed page: the visible area starts at (100, 150), not (0, 0).
        // Drawing at the origin would put most of the mark outside it.
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(new PDRectangle(0, 0, 595, 842));
            page.setCropBox(new PDRectangle(100, 150, 300, 400));
            document.addPage(page);
            write(document, page);
            document.save(out);
        }
        byte[] source = out.toByteArray();
        byte[] marked = run(source, WatermarkPlacement.CENTERED, WatermarkPageScope.ALL_PAGES);

        assertThat(inkIn(render(source, 0), render(marked, 0), 0.3, 0.3, 0.7, 0.7))
                .describedAs("the mark should sit in the middle of the CROP box")
                .isGreaterThan(200);
    }

    @Test
    void pagesOfTheSameSizeShareOneEmbeddedImageRatherThanOnePerPage() throws IOException {
        // A 50-page report must carry one copy of the watermark, not 50.
        byte[] marked = run(pdf(50, PDRectangle.A4, 0), WatermarkPlacement.TILED, WatermarkPageScope.ALL_PAGES);

        try (PDDocument output = Loader.loadPDF(marked)) {
            assertThat(distinctImageObjects(output)).isEqualTo(1);
        }
    }

    @Test
    void aMixedSizePdfGetsOneImagePerDistinctPageGeometry() throws IOException {
        // Portrait A4 body, landscape A3 foldout, A5 insert - three shapes,
        // three marks, each proportioned for its own page.
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (PDDocument document = new PDDocument()) {
            for (PDRectangle size : new PDRectangle[]{PDRectangle.A4, PDRectangle.A4, PDRectangle.A3,
                    PDRectangle.A5, PDRectangle.A4}) {
                PDPage page = new PDPage(size);
                document.addPage(page);
                write(document, page);
            }
            document.save(out);
        }
        byte[] marked = run(out.toByteArray(), WatermarkPlacement.TILED, WatermarkPageScope.ALL_PAGES);

        try (PDDocument output = Loader.loadPDF(marked)) {
            assertThat(output.getNumberOfPages()).isEqualTo(5);
            assertThat(distinctImageObjects(output))
                    .describedAs("A4, A3 and A5 are three geometries - the three A4 pages share one")
                    .isEqualTo(3);
        }
        for (int page = 0; page < 5; page++) {
            assertThat(changedPixels(out.toByteArray(), marked, page))
                    .describedAs("page %d of the mixed-size document", page)
                    .isGreaterThan(500);
        }
    }

    @Test
    void pastTheDistinctSizeCapTheLargestMarkIsReusedRatherThanEmbeddingMore() throws IOException {
        properties.getLimits().setMaxDistinctPageSizes(2);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (PDDocument document = new PDDocument()) {
            for (PDRectangle size : new PDRectangle[]{PDRectangle.A4, PDRectangle.A3, PDRectangle.A5,
                    PDRectangle.A6, PDRectangle.LETTER}) {
                PDPage page = new PDPage(size);
                document.addPage(page);
                write(document, page);
            }
            document.save(out);
        }
        byte[] source = out.toByteArray();
        byte[] marked = run(source, WatermarkPlacement.TILED, WatermarkPageScope.ALL_PAGES);

        try (PDDocument output = Loader.loadPDF(marked)) {
            assertThat(distinctImageObjects(output)).isLessThanOrEqualTo(2);
        }
        // Still marked everywhere - scaled, not skipped.
        for (int page = 0; page < 5; page++) {
            assertThat(changedPixels(source, marked, page))
                    .describedAs("page %d past the cap", page)
                    .isGreaterThan(200);
        }
    }

    @Test
    void aPasswordProtectedPdfIsRefusedCleanly() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            write(document, page);
            document.protect(new StandardProtectionPolicy("owner-pw", "user-pw", new AccessPermission()));
            document.save(out);
        }

        assertThatThrownBy(() -> run(out.toByteArray(), WatermarkPlacement.TILED, WatermarkPageScope.ALL_PAGES))
                .isInstanceOf(WatermarkException.class)
                .hasMessageContaining("password-protected")
                .extracting(e -> ((WatermarkException) e).getStatus())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    @Test
    void anOwnerPasswordPdfThatForbidsChangesIsRefusedEvenThoughPdfboxWouldOpenIt() throws IOException {
        // The important one. PDFBox opens this happily with an empty user
        // password, so nothing in the library stops us writing to it - the
        // author's permission is the only thing saying no, and honouring it
        // has to be a deliberate check.
        AccessPermission permissions = new AccessPermission();
        permissions.setCanModify(false);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            document.addPage(page);
            write(document, page);
            document.protect(new StandardProtectionPolicy("owner-pw", "", permissions));
            document.save(out);
        }

        assertThatThrownBy(() -> run(out.toByteArray(), WatermarkPlacement.TILED, WatermarkPageScope.ALL_PAGES))
                .isInstanceOf(WatermarkException.class)
                .hasMessageContaining("security settings")
                .extracting(e -> ((WatermarkException) e).getStatus())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    @Test
    void aDigitallySignedPdfIsRefusedRatherThanHavingItsSignatureBroken() throws IOException {
        // A signature has to be added incrementally to a document that was
        // LOADED, not to one built in memory - so the base file is written
        // first and reopened, which is also how a real signed PDF comes to
        // exist.
        byte[] unsigned = pdf(1, PDRectangle.A4, 0);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (PDDocument document = Loader.loadPDF(unsigned)) {
            org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature signature =
                    new org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature();
            signature.setFilter(org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature.FILTER_ADOBE_PPKLITE);
            signature.setSubFilter(
                    org.apache.pdfbox.pdmodel.interactive.digitalsignature.PDSignature.SUBFILTER_ADBE_PKCS7_DETACHED);
            signature.setName("A Signer");
            signature.setSignDate(java.util.Calendar.getInstance());
            // With a signing callback, so the placeholders are resolved and
            // the result is a STRUCTURALLY valid signed PDF that PDFBox can
            // reopen. The bytes are not a real CMS blob - this tests that a
            // signature is detected, not that one verifies.
            document.addSignature(signature, content -> new byte[256]);
            document.saveIncremental(out);
        }

        assertThatThrownBy(() -> run(out.toByteArray(), WatermarkPlacement.TILED, WatermarkPageScope.ALL_PAGES))
                .isInstanceOf(WatermarkException.class)
                .hasMessageContaining("signed")
                .extracting(e -> ((WatermarkException) e).getStatus())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    @Test
    void aPdfWithMorePagesThanTheCapIsRefused() throws IOException {
        properties.getLimits().setMaxPages(3);
        byte[] source = pdf(10, PDRectangle.A4, 0);

        assertThatThrownBy(() -> run(source, WatermarkPlacement.TILED, WatermarkPageScope.ALL_PAGES))
                .isInstanceOf(WatermarkException.class)
                .extracting(e -> ((WatermarkException) e).getStatus())
                .isEqualTo(HttpStatus.CONTENT_TOO_LARGE);
    }

    @Test
    void aDamagedPdfIsACleanFourTwoTwoRatherThanAStackTrace() throws IOException {
        byte[] truncated = java.util.Arrays.copyOf(pdf(2, PDRectangle.A4, 0), 180);

        assertThatThrownBy(() -> run(truncated, WatermarkPlacement.TILED, WatermarkPageScope.ALL_PAGES))
                .isInstanceOf(WatermarkException.class)
                .extracting(e -> ((WatermarkException) e).getStatus())
                .isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    @Test
    void somethingThatIsNotAPdfAtAllIsRefusedCleanly() {
        assertThatThrownBy(() -> run("not a pdf".getBytes(), WatermarkPlacement.TILED, WatermarkPageScope.ALL_PAGES))
                .isInstanceOf(WatermarkException.class);
    }

    // ---------------------------------------------------------- helpers

    private byte[] run(byte[] source, WatermarkPlacement placement, WatermarkPageScope scope) throws IOException {
        WatermarkSpec spec = new WatermarkSpec("CONFIDENTIAL", 40, 70, -45, "#ff0000", placement, scope);
        return service.watermark(new ByteArrayInputStream(source),
                new WatermarkOptions(spec, "application/pdf", "report.pdf"));
    }

    private static byte[] pdf(int pages, PDRectangle size, int rotation) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (PDDocument document = new PDDocument()) {
            for (int i = 0; i < pages; i++) {
                PDPage page = new PDPage(size);
                page.setRotation(rotation);
                document.addPage(page);
                write(document, page);
            }
            document.save(out);
        }
        return out.toByteArray();
    }

    /** Some real content, so "the existing content survives" means something. */
    private static void write(PDDocument document, PDPage page) throws IOException {
        try (PDPageContentStream content = new PDPageContentStream(document, page)) {
            content.beginText();
            content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
            content.newLineAtOffset(page.getCropBox().getLowerLeftX() + 20,
                    page.getCropBox().getLowerLeftY() + 40);
            content.showText("Original content");
            content.endText();
        }
    }

    private static BufferedImage render(byte[] pdf, int page) throws IOException {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            return new PDFRenderer(document).renderImageWithDPI(page, 72);
        }
    }

    /** How many pixels of the rendered page the watermark changed. */
    private static long changedPixels(byte[] before, byte[] after, int page) throws IOException {
        return inkIn(render(before, page), render(after, page), 0, 0, 1, 1);
    }

    /** Changed pixels inside a region, given as fractions of the rendered page. */
    private static long inkIn(BufferedImage before, BufferedImage after,
                              double x0, double y0, double x1, double y1) {
        int width = Math.min(before.getWidth(), after.getWidth());
        int height = Math.min(before.getHeight(), after.getHeight());
        long changed = 0;
        for (int y = (int) (height * y0); y < (int) (height * y1); y++) {
            for (int x = (int) (width * x0); x < (int) (width * x1); x++) {
                if ((before.getRGB(x, y) & 0xFFFFFF) != (after.getRGB(x, y) & 0xFFFFFF)) {
                    changed++;
                }
            }
        }
        return changed;
    }

    /** Distinct image XObjects across every page - how many copies of the mark the file carries. */
    private static int distinctImageObjects(PDDocument document) {
        java.util.Set<Object> seen = new java.util.HashSet<>();
        for (PDPage page : document.getPages()) {
            var resources = page.getResources();
            for (COSName name : resources.getXObjectNames()) {
                try {
                    if (resources.isImageXObject(name)) {
                        seen.add(resources.getXObject(name).getCOSObject());
                    }
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            }
        }
        return seen.size();
    }
}
