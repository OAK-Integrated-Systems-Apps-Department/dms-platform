package com.oak.dms.document;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.extractor.ExtractorFactory;
import org.apache.poi.extractor.POITextExtractor;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The text of a document, reduced to a 64-bit SimHash: documents whose text is
 * nearly the same get fingerprints that differ in only a few bits, whatever
 * their file names, formats or small edits.
 * <p>
 * Text comes from PDF (PDFBox), Word/Excel/PowerPoint old and new (Apache
 * POI) and plain-text formats. Images and email aren't read. Parsing is
 * bounded - at most {@link #MAX_BYTES} of input and {@link #MAX_CHARS} of text -
 * and a file that can't be parsed simply has no fingerprint.
 */
public final class DocumentFingerprints {

    public static final long MAX_BYTES = 25L * 1024 * 1024;
    static final int MAX_CHARS = 400_000;
    /** Fewer words than this can't be compared meaningfully. */
    static final int MIN_WORDS = 40;
    /** At most this many bits apart (of 64) counts as similar - about 90% the same text. */
    public static final int SIMILAR_WITHIN_BITS = 6;

    private static final Set<String> TEXT = Set.of("txt", "md", "csv", "tsv", "json", "xml", "rtf");
    private static final Set<String> OFFICE = Set.of("doc", "docx", "xls", "xlsx", "ppt", "pptx");

    private DocumentFingerprints() {
    }

    public static boolean readable(String filename) {
        String extension = DocumentRenditionService.extension(filename);
        return "pdf".equals(extension) || TEXT.contains(extension) || OFFICE.contains(extension);
    }

    /** The fingerprint of a file's text; empty when it has no usable text. */
    public static Optional<Long> of(String filename, byte[] content) {
        if (content.length > MAX_BYTES || !readable(filename)) {
            return Optional.empty();
        }
        try {
            return simhash(text(DocumentRenditionService.extension(filename), content));
        } catch (Exception | LinkageError e) {
            return Optional.empty(); // unreadable, encrypted, malformed - no fingerprint
        }
    }

    /** How alike two fingerprints are, 0-100. */
    public static int similarity(long a, long b) {
        return (int) Math.round(100.0 * (64 - Long.bitCount(a ^ b)) / 64);
    }

    static String text(String extension, byte[] content) throws IOException {
        if ("pdf".equals(extension)) {
            try (var pdf = Loader.loadPDF(content)) {
                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setEndPage(Math.min(pdf.getNumberOfPages(), 200));
                return stripper.getText(pdf);
            }
        }
        if (OFFICE.contains(extension)) {
            try (POITextExtractor extractor = ExtractorFactory.createExtractor(new ByteArrayInputStream(content))) {
                return extractor.getText();
            }
        }
        String text = new String(content, StandardCharsets.UTF_8);
        return "rtf".equals(extension) ? text.replaceAll("\\\\[a-z]+-?\\d* ?|[{}]", " ") : text;
    }

    /** Word 3-gram SimHash over the normalised text; empty when there are too few words. */
    static Optional<Long> simhash(String text) {
        if (text == null) {
            return Optional.empty();
        }
        String clipped = text.length() > MAX_CHARS ? text.substring(0, MAX_CHARS) : text;
        List<String> words = new ArrayList<>();
        for (String word : clipped.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (!word.isEmpty()) {
                words.add(word);
            }
        }
        if (words.size() < MIN_WORDS) {
            return Optional.empty();
        }
        int[] weights = new int[64];
        for (int i = 0; i + 2 < words.size(); i++) {
            long hash = fnv1a64(words.get(i) + ' ' + words.get(i + 1) + ' ' + words.get(i + 2));
            for (int bit = 0; bit < 64; bit++) {
                weights[bit] += ((hash >>> bit) & 1) == 1 ? 1 : -1;
            }
        }
        long fingerprint = 0;
        for (int bit = 0; bit < 64; bit++) {
            if (weights[bit] > 0) {
                fingerprint |= 1L << bit;
            }
        }
        return Optional.of(fingerprint);
    }

    private static long fnv1a64(String value) {
        long hash = 0xcbf29ce484222325L;
        for (byte b : value.getBytes(StandardCharsets.UTF_8)) {
            hash ^= (b & 0xff);
            hash *= 0x100000001b3L;
        }
        // Mix, so nearby inputs spread over all 64 bits.
        hash ^= hash >>> 33;
        hash *= 0xff51afd7ed558ccdL;
        hash ^= hash >>> 33;
        return hash;
    }
}
