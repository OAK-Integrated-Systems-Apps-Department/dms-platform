package com.oak.dms.watermark.format;

import com.oak.dms.watermark.config.WatermarkProperties;
import com.oak.dms.watermark.exception.WatermarkException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.sl.usermodel.PictureData;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFPictureData;
import org.apache.poi.xslf.usermodel.XSLFPictureShape;
import org.apache.poi.xslf.usermodel.XSLFSheet;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSimpleShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFSlideLayout;
import org.apache.poi.xslf.usermodel.XSLFSlideMaster;
import org.apache.xmlbeans.XmlCursor;
import org.apache.xmlbeans.XmlObject;
import org.springframework.stereotype.Component;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.geom.Rectangle2D;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Set;

/**
 * Watermarks a presentation by putting the picture on the slide master and
 * every layout, and on individual slides only where those would not show.
 *
 * <h2>Master first, because it is one shape instead of five hundred</h2>
 * PowerPoint composites a slide as master, then layout, then the slide's own
 * shapes. A picture on the master therefore appears underneath the content
 * of every slide that follows the master, which is most of them - so a
 * 500-slide deck is marked by one shape rather than 500, the file grows by
 * one picture, and the mark cannot be deleted by clicking it in Normal view.
 * Layouts get one too: a layout that does not follow its master would
 * otherwise leave every slide using it unmarked.
 *
 * <h2>The two cases where the master is not enough</h2>
 * <ol>
 *   <li><b>{@code showMasterSp="false"}</b> - a slide or layout can switch
 *       master graphics off entirely. Nothing on the master is drawn, so
 *       the slide comes back clean.</li>
 *   <li><b>An opaque full-bleed shape</b> - a section divider with a solid
 *       colour rectangle over the whole slide, or a full-page photograph,
 *       hides whatever the master drew underneath it just as effectively.</li>
 * </ol>
 * Both are detected and get a picture on the slide itself. For the second,
 * the picture is inserted directly ABOVE the covering shape rather than at
 * the back - behind the real content, but in front of the thing that would
 * have hidden it. Putting it at the back would be tidier and invisible,
 * which is the one outcome this feature must never produce.
 *
 * <h2>first-page-only</h2>
 * Slide 1 and nothing else, so the picture goes on that slide and
 * deliberately NOT on the master - a master picture would appear on all of
 * them.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PptxWatermarkService implements WatermarkFormatService {

    private static final String MIME_PPTX =
            "application/vnd.openxmlformats-officedocument.presentationml.presentation";

    /** A shape covering at least this much of the slide counts as hiding what is under it. */
    private static final double FULL_BLEED_COVERAGE = 0.92;
    /** Below this alpha a fill lets the master show through well enough. */
    private static final int OPAQUE_ALPHA = 230;

    private final WatermarkRenderer renderer;
    private final WatermarkProperties properties;

    @Override
    public Set<String> mimeTypes() {
        return Set.of(MIME_PPTX);
    }

    @Override
    public byte[] watermark(InputStream input, WatermarkOptions options) throws IOException {
        byte[] source = input.readAllBytes();
        OoxmlGuards.check(source, properties.getLimits());

        try (XMLSlideShow show = new XMLSlideShow(new ByteArrayInputStream(source))) {
            List<XSLFSlide> slides = show.getSlides();
            if (slides.isEmpty()) {
                throw WatermarkException.unprocessable("WATERMARK_PPTX_EMPTY",
                        "This presentation has no slides, so there is nothing to watermark.");
            }
            int maxSlides = properties.getLimits().getMaxSlides();
            if (slides.size() > maxSlides) {
                throw WatermarkException.tooLarge("This presentation has " + slides.size()
                        + " slides, and " + maxSlides + " is the most that can be watermarked.");
            }

            Dimension page = show.getPageSize();
            FontChoice font = renderer.resolveFont(options.spec().text());
            byte[] png = renderer.renderPng(options.spec(),
                    RenderTarget.ofPoints(page.getWidth(), page.getHeight(), renderer.renderDpi()), font);
            // One picture part for the whole deck, referenced from the
            // master, the layouts and any slide that needs its own.
            XSLFPictureData picture = show.addPicture(png, PictureData.PictureType.PNG);
            Rectangle2D anchor = new Rectangle2D.Double(0, 0, page.getWidth(), page.getHeight());

            if (options.spec().firstPageOnly()) {
                placeBehindContent(slides.get(0), picture, anchor);
            } else {
                for (XSLFSlideMaster master : show.getSlideMasters()) {
                    placeBehindContent(master, picture, anchor);
                    for (XSLFSlideLayout layout : master.getSlideLayouts()) {
                        placeBehindContent(layout, picture, anchor);
                    }
                }
                for (XSLFSlide slide : slides) {
                    if (needsItsOwn(slide)) {
                        placeBehindContent(slide, picture, anchor);
                    }
                }
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream(source.length + 64 * 1024);
            show.write(out);
            return out.toByteArray();
        } catch (WatermarkException e) {
            throw e;
        } catch (RuntimeException e) {
            throw OoxmlGuards.unreadable("presentation", e);
        }
    }

    /**
     * Whether what the master drew would actually be visible on this slide.
     * Checks the slide itself and the layout behind it, because either can
     * switch master graphics off or cover the page.
     */
    private boolean needsItsOwn(XSLFSlide slide) {
        if (!slide.getFollowMasterGraphics()) {
            log.debug("Slide {} hides master shapes - giving it its own watermark", slide.getSlideNumber());
            return true;
        }
        XSLFSlideLayout layout = slide.getSlideLayout();
        if (layout != null && !layout.getFollowMasterGraphics()) {
            return true;
        }
        return coveringShapeIndex(slide) >= 0
                || (layout != null && coveringShapeIndex(layout) >= 0);
    }

    /**
     * The index of the LAST shape that covers the slide opaquely, or -1.
     * <p>
     * The last one rather than the first: if a deck has a background
     * rectangle and then a full-bleed photograph over it, the watermark has
     * to go above both or it is still hidden.
     */
    private int coveringShapeIndex(XSLFSheet sheet) {
        List<XSLFShape> shapes = sheet.getShapes();
        double slideArea = area(sheet);
        int found = -1;
        for (int index = 0; index < shapes.size(); index++) {
            if (coversOpaquely(shapes.get(index), slideArea)) {
                found = index;
            }
        }
        return found;
    }

    private boolean coversOpaquely(XSLFShape shape, double slideArea) {
        Rectangle2D anchor = anchorOf(shape);
        if (anchor == null || slideArea <= 0) {
            return false;
        }
        double coverage = (anchor.getWidth() * anchor.getHeight()) / slideArea;
        if (coverage < FULL_BLEED_COVERAGE) {
            return false;
        }
        if (shape instanceof XSLFPictureShape) {
            // A full-bleed photograph. Assumed opaque: a PNG with
            // transparency would be the exception, and guessing wrong here
            // costs one extra picture rather than an invisible watermark.
            return true;
        }
        if (shape instanceof XSLFSimpleShape simple) {
            Color fill = fillColourOf(simple);
            return fill != null && fill.getAlpha() >= OPAQUE_ALPHA;
        }
        return false;
    }

    /**
     * A shape's rectangle, or {@code null} when it does not have one.
     * <p>
     * {@code getAnchor()} is not total. A placeholder that inherits its
     * position from the layout has no {@code a:xfrm} of its own, and POI
     * answers by throwing rather than by returning null - which took out
     * the whole job for every deck built from a standard layout, since
     * those are made almost entirely of inherited placeholders. A shape
     * whose position cannot be read simply is not a shape that covers the
     * slide, so far as this check is concerned.
     */
    private Rectangle2D anchorOf(XSLFShape shape) {
        try {
            return shape.getAnchor();
        } catch (RuntimeException e) {
            log.trace("Shape has no readable anchor - not treating it as a cover", e);
            return null;
        }
    }

    private Color fillColourOf(XSLFSimpleShape shape) {
        try {
            return shape.getFillColor();
        } catch (RuntimeException e) {
            // Gradient, pattern and theme fills can all throw here
            // depending on the deck. An unreadable fill is treated as no
            // fill - the master picture is tried first either way.
            log.trace("Could not read a shape's fill colour", e);
            return null;
        }
    }

    private static double area(XSLFSheet sheet) {
        Dimension size = sheet.getSlideShow().getPageSize();
        return size.getWidth() * size.getHeight();
    }

    /**
     * Adds the picture and moves it into place in the z-order.
     * <p>
     * {@code createPicture} appends, which puts the shape on top of
     * everything - a grey rectangle over the slide's own text. The shape's
     * XML is therefore moved back down the shape tree: to just after the
     * last thing that would have hidden it, or to the very front when
     * nothing would.
     */
    private void placeBehindContent(XSLFSheet sheet, XSLFPictureData picture, Rectangle2D anchor) {
        int coveringIndex = coveringShapeIndex(sheet);
        List<XSLFShape> before = List.copyOf(sheet.getShapes());

        XSLFPictureShape shape = sheet.createPicture(picture);
        shape.setAnchor(anchor);

        if (before.isEmpty()) {
            return;   // nothing to get behind
        }
        XSLFShape moveBefore = coveringIndex < 0
                ? before.get(0)
                : (coveringIndex + 1 < before.size() ? before.get(coveringIndex + 1) : null);
        if (moveBefore == null) {
            return;   // the covering shape was the last one; appended is already right
        }
        moveXmlBefore(shape.getXmlObject(), moveBefore.getXmlObject());
    }

    /**
     * Moves one shape's XML so it sits immediately before another's.
     * <p>
     * Done with a cursor rather than through POI because {@code XSLFSheet}
     * keeps its shape tree protected and offers no reorder; the shape tree
     * is plain XML and its document order IS the z-order, so moving the
     * element is the whole operation.
     */
    private void moveXmlBefore(XmlObject shape, XmlObject target) {
        try (XmlCursor source = shape.newCursor(); XmlCursor destination = target.newCursor()) {
            source.moveXml(destination);
        }
    }
}
