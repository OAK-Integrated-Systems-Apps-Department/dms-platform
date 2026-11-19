package com.oak.dms.search.extract;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.oak.dms.search.config.SearchProperties;
import com.oak.dms.search.index.SearchSourceRepository;
import com.oak.dms.search.index.SearchSourceRepository.OcrRow;
import com.oak.dms.search.index.SearchSourceRepository.VersionRow;
import com.oak.dms.storage.entity.StorageLocation;
import com.oak.dms.storage.enums.StoragePurpose;
import com.oak.dms.storage.exception.StorageObjectNotFoundException;
import com.oak.dms.storage.repository.StorageLocationRepository;
import com.oak.dms.storage.service.ObjectStorageServiceFactory;
import com.oak.dms.storage.service.StorageLocationResolver;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Reads the text of a document version for the index (D4). No per-format extractors: the text comes from sources
 * the platform already makes, in this order:
 * <ol>
 *   <li>plain-text files, read directly;</li>
 *   <li>the searchable PDF the OCR worker produced for scanned files;</li>
 *   <li>the OCR text ({@code ocr.json} top-level {@code text});</li>
 *   <li>the preview PDF rendition every supported file has (Office files are converted to it) - empty text there
 *       (an image-only PDF) falls through to "pending" while OCR is still coming, else "unavailable".</li>
 * </ol>
 * Everything is read and decrypted through the storage factory (never plaintext to disk) and the text is never
 * logged. A damaged, protected or oversized file is a final "unavailable" with a reason code and never throws, so
 * one bad file cannot block the others; a transient storage failure does throw, so the job retries.
 */
@Slf4j
@Component
public class TextExtractionService {

    private static final JsonFactory JSON_FACTORY = new JsonFactory();
    private static final Set<String> TEXT_EXTENSIONS = Set.of("txt", "csv", "tsv", "md", "log", "json", "xml", "html", "htm");

    private final SearchProperties props;
    private final SearchSourceRepository source;
    private final TextArtifactStore cache;
    private final StorageLocationRepository locations;
    private final StorageLocationResolver resolver;
    private final ObjectStorageServiceFactory storage;

    public TextExtractionService(SearchProperties props, SearchSourceRepository source, TextArtifactStore cache,
                                 StorageLocationRepository locations, StorageLocationResolver resolver,
                                 ObjectStorageServiceFactory storage) {
        this.props = props;
        this.source = source;
        this.cache = cache;
        this.locations = locations;
        this.resolver = resolver;
        this.storage = storage;
    }

    /** Text for the version, from the cache when its inputs are unchanged, else freshly extracted (and cached when final). */
    public ExtractedText extract(VersionRow v) {
        Optional<OcrRow> ocr = source.ocr(v.versionId());
        Optional<String> searchableKey = source.searchablePdfKey(v.versionId());
        String basis = String.join("|", String.valueOf(v.scanStatus()), String.valueOf(v.ocrStatus()),
                ocr.map(o -> o.status() + ":" + o.ref()).orElse("-"), searchableKey.orElse("-"),
                String.valueOf(v.renditionStatus()), String.valueOf(v.renditionObjectKey()),
                String.valueOf(v.storageObjectKey()), String.valueOf(v.sizeBytes()), String.valueOf(v.mimeType()));

        Optional<ExtractedText> cached = cache.read(v, basis);
        if (cached.isPresent()) {
            return cached.get();
        }
        ExtractedText result = compute(v, ocr, searchableKey);
        if (result.cacheable()) {
            try {
                cache.write(v, basis, result);
            } catch (RuntimeException e) {
                // The cache is an optimisation: failing to write it must not fail indexing.
                log.warn("Could not cache extracted text for version {} ({})", v.versionId(), e.getClass().getSimpleName());
            }
        }
        return result;
    }

    private ExtractedText compute(VersionRow v, Optional<OcrRow> ocr, Optional<String> searchableKey) {
        if (v.purged()) {
            return ExtractedText.unavailable("purged");
        }
        if (!"clean".equals(v.scanStatus())) {
            return "pending".equals(v.scanStatus()) ? ExtractedText.pending() : ExtractedText.unavailable("not_scanned");
        }
        long max = props.getExtraction().getMaxFileBytes();
        Instant deadline = Instant.now().plus(props.getExtraction().getTimeout());
        try {
            if (isPlainText(v)) {
                if (v.sizeBytes() > max) {
                    return ExtractedText.unavailable("too_large");
                }
                StorageLocation loc = versionLocation(v);
                String text = decode(readBounded(loc, v.storageObjectKey(), max));
                return finish("original", text);
            }

            // 2. the searchable PDF of a scanned file
            if (searchableKey.isPresent()) {
                Optional<String> text = pdfText(documentLocation(v), searchableKey.get(), max, deadline);
                if (text.isPresent() && !text.get().isBlank()) {
                    return ExtractedText.ready("searchable_pdf", text.get());
                }
            }
            // 3. the OCR text
            if (ocr.isPresent() && "completed".equals(ocr.get().status()) && ocr.get().ref() != null) {
                Optional<String> text = ocrText(documentLocation(v), ocr.get().ref(), max);
                if (text.isPresent() && !text.get().isBlank()) {
                    return ExtractedText.ready("ocr", cap(text.get()));
                }
            }
            // 4. the preview PDF
            if ("completed".equals(v.renditionStatus()) && v.renditionObjectKey() != null) {
                Optional<String> text = pdfText(versionLocation(v), v.renditionObjectKey(), max, deadline);
                if (text.isPresent() && !text.get().isBlank()) {
                    return ExtractedText.ready("preview_pdf", text.get());
                }
                return noTextYet(v, ocr);
            }
            return switch (String.valueOf(v.renditionStatus())) {
                case "pending", "processing" -> ExtractedText.pending();
                case "failed" -> ocrComing(ocr) ? ExtractedText.pending() : ExtractedText.unavailable("rendition_failed");
                default -> ocrComing(ocr) ? ExtractedText.pending() : ExtractedText.unavailable("unsupported");
            };
        } catch (InvalidPasswordException e) {
            return ExtractedText.unavailable("password_protected");
        } catch (PdfTextExtractor.ExtractionBlockedException e) {
            return ExtractedText.unavailable("protected");
        } catch (PdfTextExtractor.ExtractionTimeoutException e) {
            return ExtractedText.unavailable("timeout");
        } catch (TooLargeException e) {
            return ExtractedText.unavailable("too_large");
        } catch (StorageObjectNotFoundException e) {
            return ExtractedText.unavailable("artifact_missing");
        } catch (IOException | RuntimeException e) {
            if (e instanceof IOException) {
                return ExtractedText.unavailable("corrupt");
            }
            throw (RuntimeException) e; // storage/transport trouble is transient: let the job retry
        }
    }

    private ExtractedText noTextYet(VersionRow v, Optional<OcrRow> ocr) {
        return ocrComing(ocr) || "pending".equals(v.ocrStatus()) || "processing".equals(v.ocrStatus())
                ? ExtractedText.pending() : ExtractedText.unavailable("no_text");
    }

    private static boolean ocrComing(Optional<OcrRow> ocr) {
        return ocr.isPresent() && ("pending".equals(ocr.get().status()) || "processing".equals(ocr.get().status()));
    }

    private ExtractedText finish(String src, String text) {
        String t = cap(text);
        return t.isBlank() ? ExtractedText.unavailable("no_text") : ExtractedText.ready(src, t);
    }

    private String cap(String text) {
        int max = props.getExtraction().getMaxChars();
        return text.length() > max ? text.substring(0, max) : text;
    }

    private boolean isPlainText(VersionRow v) {
        String mime = v.mimeType() == null ? "" : v.mimeType().toLowerCase(Locale.ROOT);
        int semi = mime.indexOf(';');
        if (semi >= 0) {
            mime = mime.substring(0, semi).trim();
        }
        if (props.getExtraction().getPlainTextTypes().contains(mime)) {
            return true;
        }
        String name = v.fileName() == null ? "" : v.fileName().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return dot >= 0 && TEXT_EXTENSIONS.contains(name.substring(dot + 1));
    }

    private Optional<String> pdfText(StorageLocation location, String key, long max, Instant deadline) throws IOException {
        byte[] pdf;
        try {
            pdf = readBounded(location, key, max);
        } catch (StorageObjectNotFoundException gone) {
            return Optional.empty();
        }
        return Optional.of(PdfTextExtractor.extract(pdf, props.getExtraction().getMaxPages(),
                props.getExtraction().getMaxChars(), deadline));
    }

    /** The top-level {@code "text"} string of {@code ocr.json}, streamed: the pages/blocks/boxes are skipped unread. */
    private Optional<String> ocrText(StorageLocation location, String key, long max) throws IOException {
        byte[] json;
        try {
            json = readBounded(location, key, max);
        } catch (StorageObjectNotFoundException gone) {
            return Optional.empty();
        }
        try (JsonParser p = JSON_FACTORY.createParser(json)) {
            if (p.nextToken() != JsonToken.START_OBJECT) {
                return Optional.empty();
            }
            while (p.nextToken() == JsonToken.FIELD_NAME) {
                String name = p.currentName();
                JsonToken value = p.nextToken();
                if ("text".equals(name) && value == JsonToken.VALUE_STRING) {
                    return Optional.of(p.getText());
                }
                p.skipChildren();
            }
        }
        return Optional.empty();
    }

    private static class TooLargeException extends IOException {
        TooLargeException() {
            super("too large");
        }
    }

    private byte[] readBounded(StorageLocation location, String key, long max) throws IOException {
        try (InputStream in = storage.resolve(location).download(location, key).content()) {
            byte[] bytes = in.readNBytes((int) Math.min(max + 1, Integer.MAX_VALUE - 16));
            if (bytes.length > max) {
                throw new TooLargeException();
            }
            return bytes;
        }
    }

    private static String decode(byte[] bytes) {
        int offset = bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF ? 3 : 0;
        return new String(bytes, offset, bytes.length - offset, StandardCharsets.UTF_8);
    }

    /** Where this version's own file and its preview PDF live. */
    private StorageLocation versionLocation(VersionRow v) {
        return locations.findById(v.storageLocationId())
                .orElseThrow(() -> new IllegalStateException("storage location of version " + v.versionId() + " is missing"));
    }

    /** Where the OCR worker's artifacts are written (the tenant's document location, as {@code OcrArtifactWriter} does). */
    private StorageLocation documentLocation(VersionRow v) {
        return resolver.resolveForTenant(v.tenantId(), StoragePurpose.document);
    }
}
