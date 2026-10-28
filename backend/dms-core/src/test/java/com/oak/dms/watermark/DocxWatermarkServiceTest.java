package com.oak.dms.watermark;

import com.oak.dms.watermark.config.WatermarkProperties;
import com.oak.dms.watermark.dto.WatermarkSpec;
import com.oak.dms.watermark.enums.WatermarkPageScope;
import com.oak.dms.watermark.enums.WatermarkPlacement;
import com.oak.dms.watermark.exception.WatermarkException;
import com.oak.dms.watermark.format.DocxWatermarkService;
import com.oak.dms.watermark.format.FontProvider;
import com.oak.dms.watermark.format.WatermarkOptions;
import com.oak.dms.watermark.format.WatermarkRenderer;
import org.apache.poi.xwpf.model.XWPFHeaderFooterPolicy;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFHeader;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSectPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STHdrFtr;
import org.springframework.http.HttpStatus;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The structural half of the DOCX evidence: that the drawing really lands in
 * the header parts Word will consult, that nothing already in the document
 * was lost, and that the output is still a document POI can reopen.
 * <p>
 * The other half - that the mark is actually VISIBLE - cannot be asserted
 * here, because rendering a .docx needs a layout engine. That is what
 * {@code tools/watermark-samples.sh} is for; this test proves the structure
 * the renderer depends on.
 */
class DocxWatermarkServiceTest {

    private DocxWatermarkService service;

    @BeforeEach
    void setUp() {
        WatermarkProperties properties = new WatermarkProperties();
        WatermarkRenderer renderer = new WatermarkRenderer(new FontProvider(properties), properties);
        service = new DocxWatermarkService(renderer, properties);
    }

    private WatermarkSpec spec(WatermarkPlacement placement, WatermarkPageScope scope) {
        return new WatermarkSpec("CONFIDENTIAL", 40, 25, -45, "#808080", placement, scope);
    }

    private byte[] run(byte[] source, WatermarkSpec spec) throws Exception {
        return service.watermark(new ByteArrayInputStream(source),
                new WatermarkOptions(spec, OfficeFixtures.DOCX_MIME, "report.docx"));
    }

    @Test
    void aDocumentWithNoHeaderAtAllGetsOneAndTheDrawingGoesInIt() throws Exception {
        byte[] output = run(OfficeFixtures.plainDocx(),
                spec(WatermarkPlacement.TILED, WatermarkPageScope.ALL_PAGES));

        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(output))) {
            List<XWPFHeader> headers = document.getHeaderList();
            assertThat(headers).hasSize(1);
            assertThat(anchoredDrawings(headers.get(0)))
                    .describedAs("the watermark must be an anchored drawing, not an inline one")
                    .isEqualTo(1);
            assertThat(inlineDrawings(headers.get(0)))
                    .describedAs("the inline drawing POI builds must be replaced, not left beside the anchor - "
                            + "an inline one is a band of grey at the top of each page, not a watermark")
                    .isZero();
            assertThat(document.getParagraphs())
                    .describedAs("body text must be untouched")
                    .hasSize(3);
        }
    }

    @Test
    void everySectionIsStampedWithItsOwnPageGeometry() throws Exception {
        byte[] output = run(OfficeFixtures.multiSectionDocx(),
                spec(WatermarkPlacement.TILED, WatermarkPageScope.ALL_PAGES));

        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(output))) {
            List<CTSectPr> sections = sectionsOf(document);
            assertThat(sections).hasSize(2);
            for (CTSectPr section : sections) {
                XWPFHeaderFooterPolicy policy = new XWPFHeaderFooterPolicy(document, section);
                XWPFHeader header = policy.getHeader(STHdrFtr.DEFAULT);
                assertThat(header).describedAs("each section needs its own header").isNotNull();
                assertThat(anchoredDrawings(header)).isEqualTo(1);
            }
        }
        // Portrait and landscape are different geometries, so two DIFFERENT
        // images - one would mean a mark shaped for the wrong page on the
        // appendix it is sitting on.
        assertThat(mediaParts(output))
                .describedAs("a portrait and a landscape section need differently-shaped marks")
                .hasSize(2);
    }

    /**
     * The other half of the same claim: sections that DO match share one
     * embedded image. Without this, "one image per geometry" and "one image
     * per section" both pass the test above, and the second would make a
     * hundred-section document carry a hundred copies of the same PNG.
     */
    @Test
    void sectionsWithTheSameGeometryShareOneEmbeddedImage() throws Exception {
        byte[] output = run(OfficeFixtures.docxWithSections(6),
                spec(WatermarkPlacement.TILED, WatermarkPageScope.ALL_PAGES));

        assertThat(mediaParts(output))
                .describedAs("six identical sections must not embed six copies of the same mark")
                .hasSize(1);
    }

    @Test
    void anEvenAndOddDocumentIsStampedOnBothHeadersOrHalfOfItComesBackClean() throws Exception {
        byte[] output = run(OfficeFixtures.docxWithEvenAndOddHeaders(),
                spec(WatermarkPlacement.TILED, WatermarkPageScope.ALL_PAGES));

        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(output))) {
            XWPFHeaderFooterPolicy policy = new XWPFHeaderFooterPolicy(document,
                    document.getDocument().getBody().getSectPr());
            assertThat(anchoredDrawings(policy.getHeader(STHdrFtr.DEFAULT))).isEqualTo(1);
            assertThat(anchoredDrawings(policy.getHeader(STHdrFtr.EVEN)))
                    .describedAs("every even page uses the even header")
                    .isEqualTo(1);
            assertThat(textOf(policy.getHeader(STHdrFtr.EVEN))).contains("Even pages");
        }
    }

    @Test
    void aDocumentThatAlreadyHasATitlePageIsStampedOnItsFirstHeaderToo() throws Exception {
        byte[] output = run(OfficeFixtures.docxWithTitlePage(),
                spec(WatermarkPlacement.TILED, WatermarkPageScope.ALL_PAGES));

        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(output))) {
            XWPFHeaderFooterPolicy policy = new XWPFHeaderFooterPolicy(document,
                    document.getDocument().getBody().getSectPr());
            assertThat(anchoredDrawings(policy.getHeader(STHdrFtr.FIRST)))
                    .describedAs("page 1 uses the first-page header, so leaving it alone leaves page 1 clean")
                    .isEqualTo(1);
            assertThat(anchoredDrawings(policy.getHeader(STHdrFtr.DEFAULT))).isEqualTo(1);
        }
    }

    // ------------------------------------------------- first-page-only

    @Test
    void firstPageOnlySetsTitlePgAndStampsOnlyTheFirstPageHeader() throws Exception {
        byte[] output = run(OfficeFixtures.plainDocx(),
                spec(WatermarkPlacement.TILED, WatermarkPageScope.FIRST_PAGE_ONLY));

        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(output))) {
            CTSectPr section = document.getDocument().getBody().getSectPr();
            assertThat(section.isSetTitlePg())
                    .describedAs("without titlePg Word never consults the first-page header")
                    .isTrue();
            XWPFHeaderFooterPolicy policy = new XWPFHeaderFooterPolicy(document, section);
            assertThat(anchoredDrawings(policy.getHeader(STHdrFtr.FIRST))).isEqualTo(1);
            assertThat(policy.getHeader(STHdrFtr.DEFAULT))
                    .describedAs("no default header existed, and first-page-only must not create a marked one")
                    .isNull();
        }
    }

    /**
     * The regression this service's most intricate code exists for.
     * Switching titlePg on moves page 1 from the default header to the
     * first-page one; if the content does not come with it, page 1 silently
     * loses its letterhead and its logo.
     */
    @Test
    void firstPageOnlyCarriesTheExistingDefaultHeadersContentAndItsImage() throws Exception {
        byte[] source = OfficeFixtures.docxWithPopulatedDefaultHeader();
        byte[] output = run(source, spec(WatermarkPlacement.TILED, WatermarkPageScope.FIRST_PAGE_ONLY));

        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(output))) {
            CTSectPr section = document.getDocument().getBody().getSectPr();
            assertThat(section.isSetTitlePg()).isTrue();
            XWPFHeaderFooterPolicy policy = new XWPFHeaderFooterPolicy(document, section);
            XWPFHeader first = policy.getHeader(STHdrFtr.FIRST);

            assertThat(textOf(first))
                    .describedAs("page 1 must keep the letterhead it had before")
                    .contains("ACME LETTERHEAD");
            assertThat(first.getAllPictures())
                    .describedAs("and the logo, which is the part a bad relationship copy loses")
                    .isNotEmpty();
            assertThat(anchoredDrawings(first)).isEqualTo(1);
        }
    }

    /**
     * The failure mode a wrong relationship copy actually produces is not a
     * missing logo - it is a file Word offers to repair. Every r:embed in
     * the new header must name a relationship that part really has.
     */
    @Test
    void everyRelationshipTheCopiedHeaderRefersToExistsInItsOwnPart() throws Exception {
        byte[] output = run(OfficeFixtures.docxWithPopulatedDefaultHeader(),
                spec(WatermarkPlacement.TILED, WatermarkPageScope.FIRST_PAGE_ONLY));

        List<String> headerParts = OfficeFixtures.entryNames(output).stream()
                .filter(name -> name.matches("word/header\\d+\\.xml"))
                .toList();
        assertThat(headerParts).isNotEmpty();

        for (String part : headerParts) {
            String xml = new String(OfficeFixtures.entry(output, part), StandardCharsets.UTF_8);
            String relsName = part.replace("word/", "word/_rels/") + ".rels";
            String rels = new String(OfficeFixtures.entry(output, relsName), StandardCharsets.UTF_8);
            for (String referenced : relationshipIdsIn(xml)) {
                assertThat(rels)
                        .describedAs("%s refers to %s, which must exist in %s", part, referenced, relsName)
                        .contains("Id=\"" + referenced + "\"");
            }
        }
    }

    @Test
    void theOriginalBytesAreNeverModified() throws Exception {
        byte[] source = OfficeFixtures.docxWithPopulatedDefaultHeader();
        byte[] copy = source.clone();

        run(source, spec(WatermarkPlacement.TILED, WatermarkPageScope.ALL_PAGES));

        assertThat(source).describedAs("the input array must come back untouched").isEqualTo(copy);
    }

    @Test
    void everyPlacementProducesAReadableDocument() throws Exception {
        for (WatermarkPlacement placement : WatermarkPlacement.values()) {
            byte[] output = run(OfficeFixtures.plainDocx(),
                    spec(placement, WatermarkPageScope.ALL_PAGES));
            try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(output))) {
                assertThat(anchoredDrawings(document.getHeaderList().get(0)))
                        .describedAs("placement %s", placement)
                        .isEqualTo(1);
            }
        }
    }

    // ------------------------------------------------------- refusals

    @Test
    void aSignedDocumentIsRefusedBeforeAnythingIsWritten() {
        byte[] signed = OfficeFixtures.signed(OfficeFixtures.plainDocx());

        assertThatThrownBy(() -> run(signed, spec(WatermarkPlacement.TILED, WatermarkPageScope.ALL_PAGES)))
                .isInstanceOf(WatermarkException.class)
                .hasFieldOrPropertyWithValue("code", "WATERMARK_OOXML_SIGNED")
                .hasFieldOrPropertyWithValue("status", HttpStatus.UNPROCESSABLE_CONTENT);
    }

    @Test
    void aPasswordProtectedFileIsRefusedWithAMessageAboutThePassword() {
        byte[] protectedFile = OfficeFixtures.passwordProtected();

        assertThatThrownBy(() -> run(protectedFile, spec(WatermarkPlacement.TILED, WatermarkPageScope.ALL_PAGES)))
                .isInstanceOf(WatermarkException.class)
                .hasFieldOrPropertyWithValue("code", "WATERMARK_OOXML_ENCRYPTED")
                .hasMessageContaining("password-protected");
    }

    @Test
    void aTruncatedFileIsACleanRefusalRatherThanAStackTrace() {
        byte[] cut = OfficeFixtures.truncated(OfficeFixtures.plainDocx());

        assertThatThrownBy(() -> run(cut, spec(WatermarkPlacement.TILED, WatermarkPageScope.ALL_PAGES)))
                .isInstanceOf(WatermarkException.class)
                .hasFieldOrPropertyWithValue("status", HttpStatus.UNPROCESSABLE_CONTENT)
                .hasMessageNotContainingAny("Exception", "org.apache");
    }

    @Test
    void aZipBombIsRefusedWithoutBeingOpened() {
        byte[] bomb = OfficeFixtures.zipBomb();

        assertThatThrownBy(() -> run(bomb, spec(WatermarkPlacement.TILED, WatermarkPageScope.ALL_PAGES)))
                .isInstanceOf(WatermarkException.class)
                .hasFieldOrPropertyWithValue("code", "WATERMARK_OOXML_EXPANDS_TOO_FAR");
    }

    @Test
    void tooManySectionsIsRefusedWithTheCountInTheMessage() {
        byte[] many = OfficeFixtures.docxWithSections(120);

        assertThatThrownBy(() -> run(many, spec(WatermarkPlacement.TILED, WatermarkPageScope.ALL_PAGES)))
                .isInstanceOf(WatermarkException.class)
                .hasFieldOrPropertyWithValue("status", HttpStatus.CONTENT_TOO_LARGE)
                .hasMessageContaining("100");
    }

    // -------------------------------------------------------- helpers

    private static List<CTSectPr> sectionsOf(XWPFDocument document) {
        List<CTSectPr> sections = new ArrayList<>();
        document.getDocument().getBody().getPList().forEach(paragraph -> {
            if (paragraph.isSetPPr() && paragraph.getPPr().isSetSectPr()) {
                sections.add(paragraph.getPPr().getSectPr());
            }
        });
        if (document.getDocument().getBody().isSetSectPr()) {
            sections.add(document.getDocument().getBody().getSectPr());
        }
        return sections;
    }

    /**
     * Anchored drawings only. An INLINE drawing would mean the conversion in
     * {@code floatBehindText} silently did nothing, and the mark would be a
     * band of grey at the top of each page rather than a watermark - which
     * a "there is a picture in the header" assertion would happily pass.
     */
    private static int anchoredDrawings(XWPFHeader header) {
        return header == null ? 0 : countElements(header, "anchor");
    }

    /** Inline drawings, which a correct conversion leaves only where the source had them. */
    private static int inlineDrawings(XWPFHeader header) {
        return header == null ? 0 : countElements(header, "inline");
    }

    /**
     * Counts OPENING elements with this local name, whatever prefix
     * XmlBeans chose. Counting bare occurrences of the name matches the
     * closing tag too and silently doubles every answer - which is exactly
     * what this helper did on its first run, and why the counts here are
     * worth being fussy about.
     */
    private static int countElements(XWPFHeader header, String localName) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("<(?:[A-Za-z0-9_.-]+:)?" + localName + "[\\s/>]")
                .matcher(header._getHdrFtr().xmlText());
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private static String textOf(XWPFHeader header) {
        StringBuilder text = new StringBuilder();
        for (XWPFParagraph paragraph : header.getParagraphs()) {
            text.append(paragraph.getText()).append('\n');
        }
        return text.toString();
    }

    /**
     * The media parts in the saved package.
     * <p>
     * Read from the package rather than from
     * {@code XWPFDocument.getAllPictures()}, which only knows about
     * pictures registered on the MAIN document part and so answers zero for
     * a document whose only pictures are in its headers - which is every
     * document this service produces.
     */
    private static List<String> mediaParts(byte[] output) {
        return OfficeFixtures.entryNames(output).stream()
                .filter(name -> name.startsWith("word/media/"))
                .toList();
    }

    private static List<String> relationshipIdsIn(String xml) {
        List<String> ids = new ArrayList<>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("r:(?:id|embed|link)=\"([^\"]+)\"").matcher(xml);
        while (matcher.find()) {
            ids.add(matcher.group(1));
        }
        return ids;
    }
}
