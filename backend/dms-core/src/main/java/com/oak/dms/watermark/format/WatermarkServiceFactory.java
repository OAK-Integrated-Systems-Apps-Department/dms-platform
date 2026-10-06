package com.oak.dms.watermark.format;

import com.oak.dms.watermark.exception.WatermarkException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Picks the format service for a document's VERIFIED content type, and is
 * the single source of truth for which formats this build actually supports.
 *
 * <h2>Never the extension, never the client's Content-Type</h2>
 * The type passed here is the canonical {@code document_versions.mime_type},
 * re-checked against the actual bytes by {@code FileTypeInspector} just
 * before the work starts - so a file renamed to {@code .pdf} is handled as
 * whatever it really is, which is to say refused.
 * <p>
 * The one place that is not quite enough: docx, xlsx and pptx share a MIME
 * family and a magic signature (they are all ZIPs with the same marker
 * entry), so they are told apart by their canonical MIME string. If a file's
 * real internals disagree with that, POI says so when it opens it, and the
 * resulting exception becomes a 422 rather than a wrong-format guess.
 *
 * <h2>It advertises what exists, not what is planned</h2>
 * {@link #supportedFormats()} is built from the registered services, so a
 * format appears in the refusal message exactly when something can actually
 * produce it. The alternative - a hard-coded sentence - drifts the moment a
 * format is planned but not yet written: users would be told Word and Excel
 * were supported, get a 202, and then a job that failed for reasons they
 * could do nothing about.
 */
@Component
@RequiredArgsConstructor
public class WatermarkServiceFactory {

    /**
     * How each supported type is named to a person. Covers the formats this
     * feature will ever handle, including ones not yet implemented - an
     * entry here is only ever USED when a service for that type exists, so
     * listing a future format costs nothing and getting the name right at
     * the same time as the service is one less thing to remember.
     */
    private static final Map<String, String> FORMAT_NAMES = new LinkedHashMap<>();

    static {
        FORMAT_NAMES.put("application/pdf", "PDF");
        FORMAT_NAMES.put("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "Word (.docx)");
        FORMAT_NAMES.put("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "Excel (.xlsx)");
        FORMAT_NAMES.put("application/vnd.openxmlformats-officedocument.presentationml.presentation",
                "PowerPoint (.pptx)");
        FORMAT_NAMES.put("image/jpeg", "JPEG");
        FORMAT_NAMES.put("image/png", "PNG");
    }

    private final List<WatermarkFormatService> services;

    /**
     * @throws WatermarkException 415 for a format this build doesn't
     *         watermark - which is most of what this DMS accepts at
     *         ingestion, and is deliberately a clear refusal rather than a
     *         silent passthrough of the unmarked original
     */
    public WatermarkFormatService forMimeType(String mimeType) {
        for (WatermarkFormatService service : services) {
            if (service.supports(mimeType)) {
                return service;
            }
        }
        throw WatermarkException.unsupportedType(
                "Watermarking isn't available for this kind of file. Supported: " + supportedFormats() + ".");
    }

    /** Whether a type can be watermarked at all - the request-time check, before any job is created. */
    public boolean isSupported(String mimeType) {
        return services.stream().anyMatch(service -> service.supports(mimeType));
    }

    /**
     * The formats this build supports, named for a person and in a stable
     * order. Derived from the registered services every time rather than
     * cached, because it is only ever read on a refusal.
     */
    public String supportedFormats() {
        return FORMAT_NAMES.entrySet().stream()
                .filter(entry -> isSupported(entry.getKey()))
                .map(Map.Entry::getValue)
                .collect(Collectors.joining(", "));
    }
}
