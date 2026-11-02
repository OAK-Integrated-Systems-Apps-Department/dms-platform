package com.oak.dms.signature.service.format;

import com.oak.dms.signature.exception.SignatureException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
import org.apache.poi.xwpf.usermodel.Document;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.xmlbeans.XmlCursor;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageMar;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPageSz;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTSectPr;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.util.List;

/**
 * Word has no page-addressable coordinate system the way a PDF page does -
 * {@code page} here is therefore an APPROXIMATION: the body paragraph at
 * {@code round(page / pageCount * paragraphCount)} is picked as the anchor
 * point, proportionally scaled from the real page count (via
 * {@link OfficePreviewRenderer}, the same conversion used for the preview the
 * user actually sees).
 * <p>
 * The mark is inserted as an ordinary INLINE picture in a new paragraph right
 * after that one - deliberately NOT a floating, page-positioned anchor (an
 * earlier version of this class did that, mirroring {@code DocxWatermarkService}'s
 * header picture). That floating approach is schema-valid and real Word
 * renders it, but {@code docx-preview} (the library the frontend uses to show
 * Word documents in the browser, see {@code DocxViewer}) does not resolve
 * {@code relativeFrom="page"} at all - it treats the position as an offset
 * from the anchoring paragraph's own flow position, so the mark silently
 * lands far from where it was placed and is effectively invisible in the
 * app's own preview. An inline picture has no such ambiguity: every renderer
 * (real Word, LibreOffice/Gotenberg, docx-preview) lays it out identically,
 * as part of the normal text flow. The trade-off is real: x/y/height are no
 * longer meaningful coordinates - only which paragraph (approximate page),
 * width, and a left/center/right alignment derived from xRatio are honored.
 * This is the roughest placement of the three new formats, by far, but it is
 * the only version of "roughest" that is actually visible everywhere.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DocxDocumentStamper implements DocumentFormatStamper {

    private static final String MIME_DOCX =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private static final long DEFAULT_PAGE_WIDTH_TWIPS = 12240;
    private static final long DEFAULT_PAGE_HEIGHT_TWIPS = 15840;

    private final OfficePreviewRenderer previewRenderer;

    @Override
    public boolean supports(String mimeType) {
        return MIME_DOCX.equals(mimeType);
    }

    @Override
    public int pageCount(byte[] source) {
        OoxmlSanityCheck.check(source, "document");
        return previewRenderer.pageCount(previewRenderer.toPdf(source, "docx"));
    }

    @Override
    public byte[] renderPagePreview(byte[] source, int page, int width) {
        OoxmlSanityCheck.check(source, "document");
        byte[] pdf = previewRenderer.toPdf(source, "docx");
        requirePage(previewRenderer.pageCount(pdf), page);
        return previewRenderer.renderPage(pdf, page, width);
    }

    @Override
    public byte[] stamp(byte[] source, int page, double xRatio, double yRatio, double widthRatio, double heightRatio,
                        byte[] signaturePng) {
        int realPageCount = pageCount(source);
        requirePage(realPageCount, page);
        try (XWPFDocument document = open(source)) {
            List<XWPFParagraph> paragraphs = document.getParagraphs();
            if (paragraphs.isEmpty()) {
                throw SignatureException.badRequest("SIGNATURE_PLACEMENT_DOCX_EMPTY",
                        "This document has no content to place a signature on.");
            }
            int target = Math.min(paragraphs.size() - 1,
                    (int) Math.round((double) page / Math.max(1, realPageCount) * paragraphs.size()));
            XWPFParagraph anchorParagraph = paragraphs.get(target);

            BufferedImage markImage = ImageIO.read(new ByteArrayInputStream(signaturePng));
            if (markImage == null) {
                throw SignatureException.badRequest("SIGNATURE_PLACEMENT_IMAGE_DAMAGED",
                        "The signature image could not be read.");
            }
            long pageWidthTwips = pageSizeOf(document).width();
            long widthTwips = Math.round(widthRatio * pageWidthTwips);
            long heightTwips = Math.round(widthTwips * (double) markImage.getHeight() / markImage.getWidth());

            // insertNewParagraph needs a cursor sitting on the NEXT sibling element to splice a new
            // paragraph in before it; when the anchor is the last paragraph there's no next sibling, so
            // this just becomes a plain append instead (same visible result, right after the last page's
            // own content).
            XmlCursor cursor = anchorParagraph.getCTP().newCursor();
            XWPFParagraph markParagraph = cursor.toNextSibling()
                    ? document.insertNewParagraph(cursor) : document.createParagraph();
            // xRatio has no page-coordinate meaning for an inline picture - the closest honest
            // approximation is which third of the line it sits in.
            markParagraph.setAlignment(xRatio < 0.33 ? ParagraphAlignment.LEFT
                    : xRatio > 0.66 ? ParagraphAlignment.RIGHT : ParagraphAlignment.CENTER);
            XWPFRun run = markParagraph.createRun();
            try {
                run.addPicture(new ByteArrayInputStream(signaturePng), Document.PICTURE_TYPE_PNG, "signature.png",
                        emu(widthTwips), emu(heightTwips));
            } catch (InvalidFormatException e) {
                throw unreadable(new IllegalStateException(e));
            }

            ByteArrayOutputStream out = new ByteArrayOutputStream(source.length + 64 * 1024);
            document.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            log.debug("Could not stamp a Word document with a signature", e);
            throw SignatureException.badRequest("SIGNATURE_PLACEMENT_DOCX_DAMAGED",
                    "This document could not be read - it may be damaged or incomplete.");
        } catch (RuntimeException e) {
            throw unreadable(e);
        }
    }

    @Override
    public byte[] appendBlankPage(byte[] source) {
        try (XWPFDocument document = open(source)) {
            XWPFParagraph paragraph = document.createParagraph();
            XWPFRun run = paragraph.createRun();
            run.addBreak(org.apache.poi.xwpf.usermodel.BreakType.PAGE);
            ByteArrayOutputStream out = new ByteArrayOutputStream(source.length + 8 * 1024);
            document.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw SignatureException.badRequest("SIGNATURE_PLACEMENT_DOCX_DAMAGED",
                    "This document could not be read - it may be damaged or incomplete.");
        } catch (RuntimeException e) {
            throw unreadable(e);
        }
    }

    private static int emu(long twips) {
        return (int) Math.min(Integer.MAX_VALUE, twips * 635L);
    }

    private record Page(long width, long height) {
    }

    private static Page pageSizeOf(XWPFDocument document) {
        CTSectPr section = document.getDocument().getBody().isSetSectPr()
                ? document.getDocument().getBody().getSectPr() : null;
        CTPageSz size = section != null && section.isSetPgSz() ? section.getPgSz() : null;
        return new Page(twips(size == null ? null : size.getW(), DEFAULT_PAGE_WIDTH_TWIPS),
                twips(size == null ? null : size.getH(), DEFAULT_PAGE_HEIGHT_TWIPS));
    }

    private static long twips(Object value, long fallback) {
        if (value instanceof BigInteger number && number.longValue() > 0) {
            return number.longValue();
        }
        if (value instanceof Number number && number.longValue() > 0) {
            return number.longValue();
        }
        return fallback;
    }

    private static XWPFDocument open(byte[] source) throws IOException {
        OoxmlSanityCheck.check(source, "document");
        try {
            return new XWPFDocument(new ByteArrayInputStream(source));
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
        log.warn("Could not process a Word document for signature placement - refusing it", cause);
        return SignatureException.badRequest("SIGNATURE_PLACEMENT_DOCX_DAMAGED",
                "This document could not be read - it may be damaged or in a format this server doesn't recognise.");
    }
}
