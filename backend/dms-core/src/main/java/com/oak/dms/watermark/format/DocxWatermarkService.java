package com.oak.dms.watermark.format;

import com.oak.dms.watermark.config.WatermarkProperties;
import com.oak.dms.watermark.dto.WatermarkSpec;
import com.oak.dms.watermark.enums.WatermarkPlacement;
import com.oak.dms.watermark.exception.WatermarkException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
import org.apache.poi.openxml4j.opc.PackagePart;
import org.apache.poi.openxml4j.opc.PackagePartName;
import org.apache.poi.openxml4j.opc.PackagingURIHelper;
import org.apache.poi.openxml4j.opc.PackageRelationship;
import org.apache.poi.openxml4j.opc.TargetMode;
import org.apache.poi.xwpf.model.XWPFHeaderFooterPolicy;
import org.apache.poi.xwpf.usermodel.Document;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFHeader;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.xmlbeans.XmlCursor;
import org.openxmlformats.schemas.drawingml.x2006.wordprocessingDrawing.CTAnchor;
import org.openxmlformats.schemas.drawingml.x2006.wordprocessingDrawing.CTInline;
import org.openxmlformats.schemas.drawingml.x2006.wordprocessingDrawing.CTPosH;
import org.openxmlformats.schemas.drawingml.x2006.wordprocessingDrawing.CTPosV;
import org.openxmlformats.schemas.drawingml.x2006.wordprocessingDrawing.STRelFromH;
import org.openxmlformats.schemas.drawingml.x2006.wordprocessingDrawing.STRelFromV;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTBody;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTDrawing;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTHdrFtr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTOnOff;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTP;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageMar;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageSz;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSectPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STHdrFtr;
import org.springframework.stereotype.Component;

import javax.xml.namespace.QName;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Watermarks a Word document by putting a floating, behind-text picture into
 * the HEADER of every section.
 *
 * <h2>Why the header, and not the body</h2>
 * A header is drawn on every page of its section without being part of the
 * text flow, so one picture marks a hundred pages, nothing reflows, and no
 * page breaks move. Putting a shape in the body would anchor it to one
 * paragraph: it would appear once, and it would push text around. This is
 * also exactly the mechanism Word's own Insert &gt; Watermark uses, which is
 * why the result behaves the way a Word user expects.
 * <p>
 * POI ships {@code XWPFHeaderFooterPolicy.createWatermark(String)}, and it
 * is deliberately not used: it writes a VML {@code <v:shape>} with
 * hard-coded diagonal text in a fixed font, so it cannot honour placement,
 * rotation, opacity, colour or a non-Latin name, and VML is a deprecated
 * Office-only legacy that LibreOffice renders inconsistently. Embedding the
 * same PNG every other format uses keeps all six looking identical.
 *
 * <h2>Sections, and why each one is measured separately</h2>
 * A document can change page size and margins part way through - a portrait
 * report with a landscape foldout is one document with two sections, each
 * with its own {@code w:pgSz} and {@code w:pgMar} and its own set of header
 * references. Stamping the first section's geometry everywhere would leave
 * the foldout's mark rotated ninety degrees to its page.
 *
 * <h2>titlePg: the trap this code exists to avoid</h2>
 * {@code first-page-only} needs the mark in the FIRST-page header, which
 * Word only consults when the section has {@code w:titlePg} set. Setting it
 * on a section that did not have it changes that section's behaviour: page 1
 * stops using the default header and starts using the first-page one. If
 * that header is newly created and empty, page 1 silently LOSES whatever
 * the default header had on it - a letterhead, a logo, a page number.
 * <p>
 * So when titlePg has to be switched on, the default header's content is
 * copied into the new first-page header first, and every relationship that
 * content points at (images, hyperlinks) is re-pointed at the new part. A
 * copied {@code r:embed} still naming the old part's relationship id is the
 * classic way to produce a document Word opens with "unreadable content".
 *
 * <h2>What the recipient can do about it</h2>
 * Honest limitation, stated in the API docs too: this is an ordinary header
 * picture, so anyone with the file open in Word can go into the header and
 * delete it. Phase 1 marks a copy so its provenance is visible, which is a
 * different job from stopping a determined person removing it; nothing
 * short of a flattened render could do that, and that would stop being a
 * Word document.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocxWatermarkService implements WatermarkFormatService {

    private static final String MIME_DOCX =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    /** 1 inch = 1440 twips = 914400 EMU. */
    private static final int EMU_PER_TWIP = 635;
    private static final double TWIPS_PER_POINT = 20.0;
    /** US Letter, which is what Word assumes when a section declares no page size. */
    private static final long DEFAULT_PAGE_WIDTH_TWIPS = 12240;
    private static final long DEFAULT_PAGE_HEIGHT_TWIPS = 15840;
    private static final long DEFAULT_MARGIN_TWIPS = 1440;

    /** The namespace every {@code r:id} / {@code r:embed} attribute lives in. */
    private static final String RELATIONSHIP_NS =
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships";

    private final WatermarkRenderer renderer;
    private final WatermarkProperties properties;

    @Override
    public Set<String> mimeTypes() {
        return Set.of(MIME_DOCX);
    }

    @Override
    public byte[] watermark(InputStream input, WatermarkOptions options) throws IOException {
        byte[] source = input.readAllBytes();
        OoxmlGuards.check(source, properties.getLimits());

        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(source))) {
            List<CTSectPr> sections = sectionsOf(document);
            int maxSections = properties.getLimits().getMaxSections();
            if (sections.size() > maxSections) {
                throw WatermarkException.tooLarge("This document has " + sections.size()
                        + " sections, and " + maxSections + " is the most that can be watermarked.");
            }

            // One font for the whole document - a report whose landscape
            // appendix is set in a different typeface from its body looks
            // like a forgery rather than a watermark.
            FontChoice font = renderer.resolveFont(options.spec().text());
            Map<Geometry, byte[]> rendered = new LinkedHashMap<>();

            if (options.spec().firstPageOnly()) {
                stampFirstPageOnly(document, sections.get(0), options, font, rendered);
            } else {
                boolean evenAndOdd = document.getEvenAndOddHeadings();
                for (CTSectPr section : sections) {
                    stampEveryPage(document, section, evenAndOdd, options, font, rendered);
                }
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream(source.length + 64 * 1024);
            document.write(out);
            return out.toByteArray();
        } catch (WatermarkException e) {
            throw e;
        } catch (RuntimeException e) {
            throw OoxmlGuards.unreadable("Word document", e);
        }
    }

    // ------------------------------------------------------------ sections

    /**
     * Every section, in document order.
     * <p>
     * All but the last live inside a paragraph's {@code w:pPr}; the last one
     * is the body's own {@code w:sectPr}. A document with no sectPr at all
     * is legal and means "one section, all defaults", so one is created -
     * otherwise there would be nowhere to hang a header reference.
     */
    private List<CTSectPr> sectionsOf(XWPFDocument document) {
        CTBody body = document.getDocument().getBody();
        List<CTSectPr> sections = new ArrayList<>();
        for (CTP paragraph : body.getPList()) {
            if (paragraph.isSetPPr() && paragraph.getPPr().isSetSectPr()) {
                sections.add(paragraph.getPPr().getSectPr());
            }
        }
        sections.add(body.isSetSectPr() ? body.getSectPr() : body.addNewSectPr());
        return sections;
    }

    // -------------------------------------------------------------- stamps

    /**
     * Every page of this section: the default header always, plus the
     * first-page header where the section already uses one and the even-page
     * header where the document uses those. A header that doesn't exist yet
     * is created, because a section with no header at all is the common case
     * and it still has to end up marked.
     */
    private void stampEveryPage(XWPFDocument document, CTSectPr section, boolean evenAndOdd,
                                WatermarkOptions options, FontChoice font,
                                Map<Geometry, byte[]> rendered) throws IOException {
        XWPFHeaderFooterPolicy policy = new XWPFHeaderFooterPolicy(document, section);
        Set<STHdrFtr.Enum> types = new LinkedHashSet<>();
        types.add(STHdrFtr.DEFAULT);
        if (isOn(section.isSetTitlePg() ? section.getTitlePg() : null)) {
            // Already a title page: page 1 uses the first-page header, so
            // leaving it alone would leave page 1 unmarked.
            types.add(STHdrFtr.FIRST);
        }
        if (evenAndOdd) {
            // Every even page uses the even header; without this, half the
            // document comes back unmarked.
            types.add(STHdrFtr.EVEN);
        }

        for (STHdrFtr.Enum type : types) {
            XWPFHeader header = policy.getHeader(type);
            if (header == null) {
                header = policy.createHeader(type);
            }
            stamp(header, section, options, font, rendered);
        }
    }

    /**
     * Page 1 only. See the class note on titlePg: switching it on is what
     * makes a first-page header take effect, and is also what can silently
     * empty page 1's header, so the default header's content comes with it.
     */
    private void stampFirstPageOnly(XWPFDocument document, CTSectPr section,
                                    WatermarkOptions options, FontChoice font,
                                    Map<Geometry, byte[]> rendered) throws IOException {
        XWPFHeaderFooterPolicy policy = new XWPFHeaderFooterPolicy(document, section);
        boolean hadTitlePage = isOn(section.isSetTitlePg() ? section.getTitlePg() : null);
        XWPFHeader first = policy.getHeader(STHdrFtr.FIRST);

        if (first == null) {
            XWPFHeader existingDefault = policy.getHeader(STHdrFtr.DEFAULT);
            first = policy.createHeader(STHdrFtr.FIRST);
            if (!hadTitlePage && existingDefault != null) {
                // Page 1 was showing the default header a moment ago. It is
                // about to start showing this one instead, so this one has
                // to carry what that showed.
                copyHeaderContent(existingDefault, first);
            }
        }
        if (!hadTitlePage) {
            setOn(section.isSetTitlePg() ? section.getTitlePg() : section.addNewTitlePg());
        }
        stamp(first, section, options, font, rendered);
    }

    /**
     * Deep-copies one header part's content into another and re-points every
     * relationship the copy refers to.
     * <p>
     * The XML copy is the easy half. The half that breaks documents is that
     * {@code r:embed="rId4"} is meaningful only against the part it was
     * written in: the same string in the new part means whatever rId4
     * happens to be THERE, which is usually nothing. Word reports that as
     * "unreadable content" and offers to repair the file. Each one is
     * therefore looked up in the source part and added to the target, and
     * the attribute rewritten to the id the target gave back.
     * <p>
     * Relationships are added through {@link PackagePart} rather than
     * {@code POIXMLDocumentPart.addRelation}, which needs a typed
     * {@code POIXMLRelation} descriptor and so would only work for the
     * relationship types this code happened to enumerate. Going through the
     * package carries any type across - image, chart, embedded object -
     * using the source's own relationship type, which is the only thing
     * that has to match.
     */
    private void copyHeaderContent(XWPFHeader source, XWPFHeader target) {
        CTHdrFtr copy = (CTHdrFtr) source._getHdrFtr().copy();
        target._getHdrFtr().set(copy);
        remapRelationships(source, target);
    }

    private void remapRelationships(XWPFHeader source, XWPFHeader target) {
        PackagePart sourcePart = source.getPackagePart();
        PackagePart targetPart = target.getPackagePart();
        Map<String, String> remapped = new LinkedHashMap<>();

        try (XmlCursor cursor = target._getHdrFtr().newCursor()) {
            while (cursor.hasNextToken()) {
                if (cursor.isStart() && cursor.toFirstAttribute()) {
                    do {
                        QName name = cursor.getName();
                        if (!RELATIONSHIP_NS.equals(name.getNamespaceURI())) {
                            continue;
                        }
                        String oldId = cursor.getTextValue();
                        String newId = remapped.computeIfAbsent(oldId,
                                id -> carryRelationship(sourcePart, targetPart, id));
                        if (newId != null) {
                            cursor.setTextValue(newId);
                        }
                    } while (cursor.toNextAttribute());
                    cursor.toParent();
                }
                cursor.toNextToken();
            }
        }
    }

    /**
     * @return the new relationship id in the target part, or {@code null}
     *         when the source has no such relationship - which means the
     *         attribute was already dangling in the original, and rewriting
     *         it would not improve matters
     */
    private String carryRelationship(PackagePart sourcePart, PackagePart targetPart, String oldId) {
        try {
            PackageRelationship relationship = sourcePart.getRelationship(oldId);
            if (relationship == null) {
                log.debug("Header content referred to {}, which the source part doesn't have", oldId);
                return null;
            }
            PackageRelationship added;
            if (relationship.getTargetMode() == TargetMode.EXTERNAL) {
                added = targetPart.addExternalRelationship(relationship.getTargetURI().toString(),
                        relationship.getRelationshipType());
            } else {
                // The target URI is relative to the SOURCE part, so it is
                // resolved against that part before being handed to the new
                // one. Both headers live in /word/, so the relative form
                // happens to be identical - but relying on that would break
                // the day a package puts them anywhere else.
                PackagePartName target = PackagingURIHelper.createPartName(
                        PackagingURIHelper.resolvePartUri(sourcePart.getPartName().getURI(),
                                relationship.getTargetURI()));
                added = targetPart.addRelationship(target, TargetMode.INTERNAL,
                        relationship.getRelationshipType());
            }
            return added.getId();
        } catch (InvalidFormatException | RuntimeException e) {
            log.warn("Could not carry header relationship {} to the first-page header", oldId, e);
            return null;
        }
    }

    /**
     * Puts the picture into one header, sized and positioned for this
     * section's own page.
     */
    private void stamp(XWPFHeader header, CTSectPr section, WatermarkOptions options,
                       FontChoice font, Map<Geometry, byte[]> rendered) throws IOException {
        Page page = pageOf(section);
        Area area = areaFor(page, options.spec());
        Geometry geometry = new Geometry(area.widthTwips(), area.heightTwips());

        byte[] png = rendered.get(geometry);
        if (png == null) {
            png = rendered.size() >= properties.getLimits().getMaxDistinctPageSizes()
                    ? largestOf(rendered)
                    : renderer.renderPng(options.spec(), targetFor(area), font);
            rendered.put(geometry, png);
        }

        XWPFParagraph paragraph = header.createParagraph();
        // No spacing of its own: an empty paragraph carrying only an
        // anchored picture still occupies a line in the header, which
        // pushes the body text down a line on every page. Zeroing it keeps
        // the layout identical to the original.
        paragraph.setSpacingAfter(0);
        paragraph.setSpacingBefore(0);
        XWPFRun run = paragraph.createRun();

        int widthEmu = (int) Math.min(Integer.MAX_VALUE, area.widthTwips() * EMU_PER_TWIP);
        int heightEmu = (int) Math.min(Integer.MAX_VALUE, area.heightTwips() * EMU_PER_TWIP);
        try {
            run.addPicture(new ByteArrayInputStream(png), Document.PICTURE_TYPE_PNG,
                    "watermark.png", widthEmu, heightEmu);
        } catch (InvalidFormatException e) {
            // PNG is one of the types POI always accepts; this would mean
            // the package itself refused the relationship.
            throw OoxmlGuards.unreadable("Word document", new IllegalStateException(e));
        }
        floatBehindText(run, area);
    }

    /**
     * Turns the inline drawing POI just created into a floating anchor
     * behind the text.
     * <p>
     * {@code addPicture} can only make an INLINE drawing - one that sits in
     * the text flow like a very large character, which in a header means a
     * mark that is a few lines tall at the top of the page rather than one
     * covering it. The relationship, the media part and the whole
     * {@code pic:pic} subtree it builds are exactly what an anchor needs, so
     * the graphic is moved onto a typed {@link CTAnchor} and the inline
     * dropped, rather than assembling the picture XML by hand.
     */
    private void floatBehindText(XWPFRun run, Area area) {
        CTDrawing drawing = run.getCTR().getDrawingArray(0);
        CTInline inline = drawing.getInlineArray(0);

        CTAnchor anchor = drawing.addNewAnchor();
        anchor.setDistT(0);
        anchor.setDistB(0);
        anchor.setDistL(0);
        anchor.setDistR(0);
        anchor.setSimplePos2(false);
        // Behind everything, and allowed to overlap: without allowOverlap
        // Word may push the mark aside to avoid another floating object.
        anchor.setBehindDoc(true);
        anchor.setLocked(false);
        anchor.setLayoutInCell(true);
        anchor.setAllowOverlap(true);
        anchor.setRelativeHeight(0);

        anchor.addNewSimplePos().setX(0);
        anchor.getSimplePos().setY(0);

        // Positioned against the PAGE, not the margin or the paragraph: the
        // mark has to land in the same place regardless of how much text the
        // header happens to contain.
        CTPosH positionH = anchor.addNewPositionH();
        positionH.setRelativeFrom(STRelFromH.PAGE);
        positionH.setPosOffset((int) Math.min(Integer.MAX_VALUE, area.xTwips() * EMU_PER_TWIP));
        CTPosV positionV = anchor.addNewPositionV();
        positionV.setRelativeFrom(STRelFromV.PAGE);
        positionV.setPosOffset((int) Math.min(Integer.MAX_VALUE, area.yTwips() * EMU_PER_TWIP));

        anchor.setExtent(inline.getExtent());
        anchor.addNewEffectExtent();
        anchor.addNewWrapNone();
        anchor.setDocPr(inline.getDocPr());
        if (inline.isSetCNvGraphicFramePr()) {
            anchor.setCNvGraphicFramePr(inline.getCNvGraphicFramePr());
        }
        anchor.setGraphic(inline.getGraphic());

        drawing.removeInline(0);
    }

    // ------------------------------------------------------------ geometry

    /**
     * Where the mark is drawn on the page, which is not the same question
     * for every placement.
     * <p>
     * Tiled covers the text area: tiles running under the margins would be
     * cut off by most printers and look like a mistake. The other three are
     * drawn across the whole sheet, because a corner badge belongs in the
     * paper's corner and a header/footer line belongs at the paper's edges -
     * the renderer insets them from the surface it is given, so giving it
     * the text area instead would inset them twice.
     */
    private Area areaFor(Page page, WatermarkSpec spec) {
        if (spec.placement() != WatermarkPlacement.TILED) {
            return new Area(0, 0, page.widthTwips(), page.heightTwips());
        }
        long width = Math.max(1, page.widthTwips() - page.leftTwips() - page.rightTwips());
        long height = Math.max(1, page.heightTwips() - page.topTwips() - page.bottomTwips());
        return new Area(page.leftTwips(), page.topTwips(), width, height);
    }

    private RenderTarget targetFor(Area area) {
        return RenderTarget.ofPoints(area.widthTwips() / TWIPS_PER_POINT,
                area.heightTwips() / TWIPS_PER_POINT, renderer.renderDpi());
    }

    private Page pageOf(CTSectPr section) {
        CTPageSz size = section.isSetPgSz() ? section.getPgSz() : null;
        CTPageMar margin = section.isSetPgMar() ? section.getPgMar() : null;
        long width = twips(size == null ? null : size.getW(), DEFAULT_PAGE_WIDTH_TWIPS);
        long height = twips(size == null ? null : size.getH(), DEFAULT_PAGE_HEIGHT_TWIPS);
        return new Page(width, height,
                twips(margin == null ? null : margin.getTop(), DEFAULT_MARGIN_TWIPS),
                twips(margin == null ? null : margin.getBottom(), DEFAULT_MARGIN_TWIPS),
                twips(margin == null ? null : margin.getLeft(), DEFAULT_MARGIN_TWIPS),
                twips(margin == null ? null : margin.getRight(), DEFAULT_MARGIN_TWIPS));
    }

    /**
     * {@code ST_TwipsMeasure} is a union - a plain number of twips, or a
     * string like {@code "2.5cm"} - so XmlBeans types the getter as
     * {@link Object} and the caller has to deal with both. A value that is
     * neither falls back to the default rather than failing the job: a
     * slightly wrongly-sized watermark beats refusing a document over its
     * margin notation.
     */
    private static long twips(Object value, long fallback) {
        if (value instanceof BigInteger number) {
            long twips = number.longValue();
            return twips > 0 ? twips : fallback;
        }
        if (value instanceof Number number) {
            long twips = number.longValue();
            return twips > 0 ? twips : fallback;
        }
        if (value instanceof String text) {
            Long parsed = universalMeasureToTwips(text.trim());
            if (parsed != null && parsed > 0) {
                return parsed;
            }
        }
        return fallback;
    }

    /** {@code 12240}, {@code "8.5in"}, {@code "21cm"}, {@code "595pt"} - the forms Word writes. */
    private static Long universalMeasureToTwips(String text) {
        if (text.isEmpty()) {
            return null;
        }
        try {
            if (text.length() > 2) {
                String unit = text.substring(text.length() - 2).toLowerCase(java.util.Locale.ROOT);
                String number = text.substring(0, text.length() - 2).trim();
                Double perUnit = switch (unit) {
                    case "in" -> 1440.0;
                    case "cm" -> 1440.0 / 2.54;
                    case "mm" -> 1440.0 / 25.4;
                    case "pt" -> 20.0;
                    case "pc", "pi" -> 240.0;
                    default -> null;
                };
                if (perUnit != null) {
                    return Math.round(Double.parseDouble(number) * perUnit);
                }
            }
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean isOn(CTOnOff onOff) {
        // An absent w:titlePg is off; a present one is ON unless it says
        // otherwise, which is the OOXML convention for every w:onOff.
        if (onOff == null) {
            return false;
        }
        return !onOff.isSetVal() || toBoolean(onOff.getVal());
    }

    private static void setOn(CTOnOff onOff) {
        if (onOff.isSetVal()) {
            onOff.unsetVal();
        }
    }

    private static boolean toBoolean(Object value) {
        if (value instanceof Boolean flag) {
            return flag;
        }
        if (value instanceof Number number) {
            return number.intValue() != 0;
        }
        String text = String.valueOf(value);
        return !"0".equals(text) && !"false".equalsIgnoreCase(text) && !"off".equalsIgnoreCase(text);
    }

    private static byte[] largestOf(Map<Geometry, byte[]> rendered) {
        return rendered.entrySet().stream()
                .max(Map.Entry.comparingByKey(
                        Comparator.comparingLong(g -> g.widthTwips() * g.heightTwips())))
                .orElseThrow()
                .getValue();
    }

    /** A section's page, in twips. */
    private record Page(long widthTwips, long heightTwips,
                        long topTwips, long bottomTwips, long leftTwips, long rightTwips) {
    }

    /** The rectangle the mark is drawn into, relative to the page's top-left corner. */
    private record Area(long xTwips, long yTwips, long widthTwips, long heightTwips) {
    }

    /**
     * What lets two sections share one rendered PNG. Only the drawn area
     * matters - two sections with different margins but the same text area
     * genuinely want the same image.
     */
    private record Geometry(long widthTwips, long heightTwips) {
    }
}
