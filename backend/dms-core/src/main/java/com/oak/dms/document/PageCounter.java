package com.oak.dms.document;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.poi.hpsf.DocumentSummaryInformation;
import org.apache.poi.hpsf.PropertySetFactory;
import org.apache.poi.hpsf.SummaryInformation;
import org.apache.poi.poifs.filesystem.DirectoryEntry;
import org.apache.poi.poifs.filesystem.DocumentInputStream;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * How many pages a stored file has - read from the file, never guessed from its size. Two kinds
 * of answer: {@code counted} (the pages or slides are counted in the file itself: PDF, PowerPoint
 * slide parts, TIFF frames) and {@code reported} (the page total the saving program wrote into the
 * file: Word and OpenDocument statistics, legacy Office summary information - accurate when the
 * file was saved by Office/LibreOffice, but it's their number, not ours). Formats without pages -
 * spreadsheets, images, text, email, archives - give nothing, so they're left out of page totals
 * rather than counted as a page each.
 */
public final class PageCounter {

    public enum Source { COUNTED, REPORTED }

    public record Pages(int count, Source source) {
    }

    /** Formats a page count can be read from. */
    static final Set<String> PAGED = Set.of("pdf", "docx", "pptx", "doc", "ppt", "odt", "odp", "tif", "tiff");

    private static final Pattern SLIDE_PART = Pattern.compile("ppt/slides/slide\\d+\\.xml");
    private static final Pattern APP_PAGES = Pattern.compile("<Pages>\\s*(\\d+)\\s*</Pages>");
    private static final Pattern ODF_PAGES = Pattern.compile("meta:page-count=\"(\\d+)\"");

    private PageCounter() {
    }

    public static boolean paged(String filename) {
        return PAGED.contains(extension(filename));
    }

    /**
     * @param file a local copy of the file (read in place - large PDFs are not held in memory)
     * @return the pages, or empty when the format has none or the file can't be read
     */
    public static Optional<Pages> count(String filename, File file) {
        try {
            return switch (extension(filename)) {
                case "pdf" -> pdf(file);
                case "pptx" -> slideParts(file);
                case "docx" -> ooxmlPages(file);
                case "odt", "odp" -> odfPages(file);
                case "doc", "ppt" -> ole2(file);
                case "tif", "tiff" -> tiffFrames(file);
                default -> Optional.empty();
            };
        } catch (Exception | LinkageError e) {
            // Damaged, encrypted or unusual files simply aren't counted.
            return Optional.empty();
        }
    }

    private static Optional<Pages> pdf(File file) throws IOException {
        try (PDDocument pdf = Loader.loadPDF(file, IOUtils.createTempFileOnlyStreamCache())) {
            return positive(pdf.getNumberOfPages(), Source.COUNTED);
        }
    }

    /** A PowerPoint deck's slides are its ppt/slides/slideN.xml parts. */
    private static Optional<Pages> slideParts(File file) throws IOException {
        try (ZipFile zip = new ZipFile(file)) {
            int slides = 0;
            for (Enumeration<? extends ZipEntry> entries = zip.entries(); entries.hasMoreElements(); ) {
                if (SLIDE_PART.matcher(entries.nextElement().getName()).matches()) {
                    slides++;
                }
            }
            return positive(slides, Source.COUNTED);
        }
    }

    /** Word keeps the page total in docProps/app.xml, as of the last save. */
    private static Optional<Pages> ooxmlPages(File file) throws IOException {
        return fromZipEntry(file, "docProps/app.xml", APP_PAGES);
    }

    /** OpenDocument keeps it in meta.xml's document statistics. */
    private static Optional<Pages> odfPages(File file) throws IOException {
        return fromZipEntry(file, "meta.xml", ODF_PAGES);
    }

    private static Optional<Pages> fromZipEntry(File file, String entryName, Pattern pattern) throws IOException {
        try (ZipFile zip = new ZipFile(file)) {
            ZipEntry entry = zip.getEntry(entryName);
            if (entry == null) {
                return Optional.empty();
            }
            try (InputStream in = zip.getInputStream(entry)) {
                // These parts are small; read a bounded amount.
                String xml = new String(in.readNBytes(256 * 1024), StandardCharsets.UTF_8);
                Matcher m = pattern.matcher(xml);
                return m.find() ? positive(Integer.parseInt(m.group(1)), Source.REPORTED) : Optional.empty();
            }
        }
    }

    /** Legacy .doc keeps a page count in its summary information, .ppt a slide count in the document summary. */
    private static Optional<Pages> ole2(File file) throws Exception {
        try (POIFSFileSystem fs = new POIFSFileSystem(file, true)) {
            DirectoryEntry root = fs.getRoot();
            if (root.hasEntry(SummaryInformation.DEFAULT_STREAM_NAME)) {
                try (DocumentInputStream in = fs.createDocumentInputStream(SummaryInformation.DEFAULT_STREAM_NAME)) {
                    int pages = ((SummaryInformation) PropertySetFactory.create(in)).getPageCount();
                    if (pages > 0) {
                        return positive(pages, Source.REPORTED);
                    }
                }
            }
            if (root.hasEntry(DocumentSummaryInformation.DEFAULT_STREAM_NAME)) {
                try (DocumentInputStream in = fs.createDocumentInputStream(
                        DocumentSummaryInformation.DEFAULT_STREAM_NAME)) {
                    int slides = ((DocumentSummaryInformation) PropertySetFactory.create(in)).getSlideCount();
                    return positive(slides, Source.REPORTED);
                }
            }
            return Optional.empty();
        }
    }

    private static Optional<Pages> tiffFrames(File file) throws IOException {
        try (ImageInputStream in = ImageIO.createImageInputStream(file)) {
            if (in == null) {
                return Optional.empty();
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                return Optional.empty();
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, false, true);
                return positive(reader.getNumImages(true), Source.COUNTED);
            } finally {
                reader.dispose();
            }
        }
    }

    private static Optional<Pages> positive(int count, Source source) {
        return count > 0 ? Optional.of(new Pages(count, source)) : Optional.empty();
    }

    static String extension(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
