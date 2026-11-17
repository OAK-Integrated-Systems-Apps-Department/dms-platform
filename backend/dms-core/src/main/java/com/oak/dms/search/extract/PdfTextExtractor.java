package com.oak.dms.search.extract;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.IOException;
import java.time.Instant;

/**
 * Text from a PDF, held in memory only: {@link IOUtils#createMemoryOnlyStreamCache()} stops PDFBox spilling
 * parsed streams to temp files, so no document text ever reaches the disk. Page, character and time caps apply,
 * checked between pages so a pathological file cannot hold a worker forever.
 */
public final class PdfTextExtractor {

    private PdfTextExtractor() {
    }

    /** The file is readable but its permissions forbid extracting content (owner-restricted PDF). */
    public static class ExtractionBlockedException extends IOException {
        public ExtractionBlockedException(String message) {
            super(message);
        }
    }

    /** The per-document time budget ran out. */
    public static class ExtractionTimeoutException extends IOException {
        public ExtractionTimeoutException() {
            super("text extraction timed out");
        }
    }

    public static String extract(byte[] pdf, int maxPages, int maxChars, Instant deadline) throws IOException {
        try (PDDocument doc = Loader.loadPDF(new RandomAccessReadBuffer(pdf), "", null, null,
                IOUtils.createMemoryOnlyStreamCache())) {
            if (doc.isEncrypted() && !doc.getCurrentAccessPermission().canExtractContent()) {
                throw new ExtractionBlockedException("content extraction not permitted");
            }
            PDFTextStripper stripper = new PDFTextStripper();
            int pages = Math.min(doc.getNumberOfPages(), Math.max(1, maxPages));
            StringBuilder text = new StringBuilder();
            for (int page = 1; page <= pages; page++) {
                if (Instant.now().isAfter(deadline) || Thread.currentThread().isInterrupted()) {
                    throw new ExtractionTimeoutException();
                }
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                text.append(stripper.getText(doc)).append('\n');
                if (text.length() >= maxChars) {
                    text.setLength(maxChars);
                    break;
                }
            }
            return text.toString();
        }
    }
}
