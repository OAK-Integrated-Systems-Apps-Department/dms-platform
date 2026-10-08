package com.oak.dms.watermark;

import com.oak.dms.watermark.config.WatermarkProperties;
import com.oak.dms.watermark.dto.WatermarkSpec;
import com.oak.dms.watermark.enums.WatermarkPageScope;
import com.oak.dms.watermark.enums.WatermarkPlacement;
import com.oak.dms.watermark.exception.WatermarkException;
import com.oak.dms.watermark.format.FontProvider;
import com.oak.dms.watermark.format.PptxWatermarkService;
import com.oak.dms.watermark.format.WatermarkOptions;
import com.oak.dms.watermark.format.WatermarkRenderer;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFPictureShape;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSheet;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFSlideLayout;
import org.apache.poi.xslf.usermodel.XSLFSlideMaster;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.io.ByteArrayInputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Presentation evidence: the mark on the master and the layouts, an extra one
 * only where those would be hidden, and - the assertion that matters most -
 * the mark BEHIND the slide's content rather than over the top of it.
 */
class PptxWatermarkServiceTest {

    private PptxWatermarkService service;
    private WatermarkProperties properties;

    @BeforeEach
    void setUp() {
        properties = new WatermarkProperties();
        WatermarkRenderer renderer = new WatermarkRenderer(new FontProvider(properties), properties);
        service = new PptxWatermarkService(renderer, properties);
    }

    private WatermarkSpec spec(WatermarkPageScope scope) {
        return new WatermarkSpec("CONFIDENTIAL", 40, 25, -45, "#808080",
                WatermarkPlacement.TILED, scope);
    }

    private byte[] run(byte[] source, WatermarkSpec spec) throws Exception {
        return service.watermark(new ByteArrayInputStream(source),
                new WatermarkOptions(spec, OfficeFixtures.PPTX_MIME, "deck.pptx"));
    }

    @Test
    void theMasterAndEveryLayoutCarryTheMarkSoOneShapeCoversTheWholeDeck() throws Exception {
        byte[] output = run(OfficeFixtures.plainPptx(6), spec(WatermarkPageScope.ALL_PAGES));

        try (XMLSlideShow show = new XMLSlideShow(new ByteArrayInputStream(output))) {
            XSLFSlideMaster master = show.getSlideMasters().get(0);
            assertThat(picturesOn(master))
                    .describedAs("one picture on the master marks every ordinary slide")
                    .hasSize(1);
            for (XSLFSlideLayout layout : master.getSlideLayouts()) {
                assertThat(picturesOn(layout))
                        .describedAs("layout '%s' - a layout that stops following its master would "
                                + "otherwise leave every slide using it clean", layout.getName())
                        .hasSize(1);
            }
            for (XSLFSlide slide : show.getSlides()) {
                assertThat(picturesOn(slide))
                        .describedAs("an ordinary slide inherits the master's mark and needs none of its own")
                        .isEmpty();
            }
        }
    }

    @Test
    void oneImageIsEmbeddedHoweverManySheetsReferenceIt() throws Exception {
        byte[] output = run(OfficeFixtures.plainPptx(40), spec(WatermarkPageScope.ALL_PAGES));

        List<String> media = OfficeFixtures.entryNames(output).stream()
                .filter(name -> name.startsWith("ppt/media/"))
                .toList();
        assertThat(media)
                .describedAs("a 40-slide deck must carry one copy of the mark, not one per sheet")
                .hasSize(1);
    }

    @Test
    void aSlideThatHidesMasterShapesGetsItsOwnMarkOrItComesBackClean() throws Exception {
        byte[] output = run(OfficeFixtures.pptxWithHiddenMasterAndOpaqueCover(),
                spec(WatermarkPageScope.ALL_PAGES));

        try (XMLSlideShow show = new XMLSlideShow(new ByteArrayInputStream(output))) {
            List<XSLFSlide> slides = show.getSlides();
            assertThat(picturesOn(slides.get(0)))
                    .describedAs("an ordinary slide needs nothing of its own")
                    .isEmpty();
            assertThat(slides.get(1).getFollowMasterGraphics()).isFalse();
            assertThat(picturesOn(slides.get(1)))
                    .describedAs("showMasterSp=false means nothing from the master is drawn at all")
                    .hasSize(1);
        }
    }

    @Test
    void aSlideCoveredByAnOpaqueShapeGetsAMarkAboveThatShapeAndBelowItsContent() throws Exception {
        byte[] output = run(OfficeFixtures.pptxWithHiddenMasterAndOpaqueCover(),
                spec(WatermarkPageScope.ALL_PAGES));

        try (XMLSlideShow show = new XMLSlideShow(new ByteArrayInputStream(output))) {
            XSLFSlide covered = show.getSlides().get(2);
            List<XSLFShape> shapes = covered.getShapes();
            assertThat(picturesOn(covered))
                    .describedAs("an opaque full-bleed shape hides the master just as effectively "
                            + "as switching master graphics off")
                    .hasSize(1);

            int watermarkIndex = indexOfPicture(shapes);
            int coverIndex = indexOfAutoShape(shapes);
            int captionIndex = indexOfTextBox(shapes);
            assertThat(watermarkIndex)
                    .describedAs("the mark must be ABOVE the shape that would hide it - at the very "
                            + "back it would be tidy and invisible, which is the one unacceptable outcome")
                    .isGreaterThan(coverIndex);
            assertThat(watermarkIndex)
                    .describedAs("but still BELOW the slide's own content, or it covers the text")
                    .isLessThan(captionIndex);
        }
    }

    @Test
    void theMarkGoesBehindExistingContentOnAnOrdinarySheet() throws Exception {
        byte[] output = run(OfficeFixtures.plainPptx(2), spec(WatermarkPageScope.ALL_PAGES));

        try (XMLSlideShow show = new XMLSlideShow(new ByteArrayInputStream(output))) {
            XSLFSlideMaster master = show.getSlideMasters().get(0);
            List<XSLFShape> shapes = master.getShapes();
            assertThat(shapes.get(0))
                    .describedAs("createPicture appends, so without a reorder the mark sits on top "
                            + "of the master's own placeholders")
                    .isInstanceOf(XSLFPictureShape.class);
        }
    }

    @Test
    void firstPageOnlyMarksSlideOneAndLeavesTheMasterAlone() throws Exception {
        byte[] output = run(OfficeFixtures.plainPptx(4), spec(WatermarkPageScope.FIRST_PAGE_ONLY));

        try (XMLSlideShow show = new XMLSlideShow(new ByteArrayInputStream(output))) {
            assertThat(picturesOn(show.getSlides().get(0))).hasSize(1);
            for (int index = 1; index < show.getSlides().size(); index++) {
                assertThat(picturesOn(show.getSlides().get(index)))
                        .describedAs("slide %d", index + 1)
                        .isEmpty();
            }
            assertThat(picturesOn(show.getSlideMasters().get(0)))
                    .describedAs("a master picture would appear on every slide, which is the opposite "
                            + "of first-page-only")
                    .isEmpty();
        }
    }

    @Test
    void theSlidesOwnContentSurvives() throws Exception {
        byte[] output = run(OfficeFixtures.plainPptx(3), spec(WatermarkPageScope.ALL_PAGES));

        try (XMLSlideShow show = new XMLSlideShow(new ByteArrayInputStream(output))) {
            assertThat(show.getSlides()).hasSize(3);
            assertThat(show.getSlides().get(0).getShapes())
                    .describedAs("the text box that was there before must still be there")
                    .isNotEmpty();
        }
    }

    @Test
    void theOriginalBytesAreNeverModified() throws Exception {
        byte[] source = OfficeFixtures.pptxWithHiddenMasterAndOpaqueCover();
        byte[] copy = source.clone();

        run(source, spec(WatermarkPageScope.ALL_PAGES));

        assertThat(source).isEqualTo(copy);
    }

    // ------------------------------------------------------- refusals

    @Test
    void aSignedPresentationIsRefused() {
        byte[] signed = OfficeFixtures.signed(OfficeFixtures.plainPptx(2));

        assertThatThrownBy(() -> run(signed, spec(WatermarkPageScope.ALL_PAGES)))
                .isInstanceOf(WatermarkException.class)
                .hasFieldOrPropertyWithValue("code", "WATERMARK_OOXML_SIGNED");
    }

    @Test
    void aPasswordProtectedPresentationIsRefused() {
        byte[] locked = OfficeFixtures.passwordProtected();

        assertThatThrownBy(() -> run(locked, spec(WatermarkPageScope.ALL_PAGES)))
                .isInstanceOf(WatermarkException.class)
                .hasFieldOrPropertyWithValue("code", "WATERMARK_OOXML_ENCRYPTED");
    }

    @Test
    void aTruncatedPresentationIsACleanRefusal() {
        byte[] cut = OfficeFixtures.truncated(OfficeFixtures.plainPptx(4));

        assertThatThrownBy(() -> run(cut, spec(WatermarkPageScope.ALL_PAGES)))
                .isInstanceOf(WatermarkException.class)
                .hasFieldOrPropertyWithValue("status", HttpStatus.UNPROCESSABLE_CONTENT)
                .hasMessageNotContainingAny("Exception", "org.apache");
    }

    @Test
    void tooManySlidesIsRefusedWithTheCountInTheMessage() {
        properties.getLimits().setMaxSlides(5);
        byte[] many = OfficeFixtures.pptxWithSlides(9);

        assertThatThrownBy(() -> run(many, spec(WatermarkPageScope.ALL_PAGES)))
                .isInstanceOf(WatermarkException.class)
                .hasFieldOrPropertyWithValue("status", HttpStatus.CONTENT_TOO_LARGE)
                .hasMessageContaining("9 slides");
    }

    // -------------------------------------------------------- helpers

    private static List<XSLFPictureShape> picturesOn(XSLFSheet sheet) {
        return sheet.getShapes().stream()
                .filter(XSLFPictureShape.class::isInstance)
                .map(XSLFPictureShape.class::cast)
                .toList();
    }

    private static int indexOfPicture(List<XSLFShape> shapes) {
        return indexOf(shapes, XSLFPictureShape.class);
    }

    private static int indexOfAutoShape(List<XSLFShape> shapes) {
        return indexOf(shapes, org.apache.poi.xslf.usermodel.XSLFAutoShape.class);
    }

    private static int indexOfTextBox(List<XSLFShape> shapes) {
        return indexOf(shapes, org.apache.poi.xslf.usermodel.XSLFTextBox.class);
    }

    private static int indexOf(List<XSLFShape> shapes, Class<?> type) {
        for (int index = 0; index < shapes.size(); index++) {
            if (type.isInstance(shapes.get(index))) {
                return index;
            }
        }
        return -1;
    }
}
