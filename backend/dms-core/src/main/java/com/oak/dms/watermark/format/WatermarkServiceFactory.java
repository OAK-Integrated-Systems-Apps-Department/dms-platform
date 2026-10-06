package com.oak.dms.watermark.format;

import com.oak.dms.watermark.exception.WatermarkException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Picks the format service for a document's VERIFIED content type.
 * <p>
 * Never the file extension, and never the Content-Type a client sent. The
 * type passed here is the canonical {@code document_versions.mime_type},
 * re-checked against the actual bytes by {@code FileTypeInspector} just
 * before the work starts - so a file renamed to {@code .pdf} is handled as
 * whatever it really is, which is to say refused.
 * <p>
 * The one place that is not quite enough: docx, xlsx and pptx share a MIME
 * family and a magic signature (they are all ZIPs with the same marker
 * entry), so they are told apart by their canonical MIME string. If a file's
 * real internals disagree with that, POI says so when it opens it, and the
 * resulting exception becomes a 422 rather than a wrong-format guess.
 */
@Component
@RequiredArgsConstructor
public class WatermarkServiceFactory {

    private final List<WatermarkFormatService> services;

    /**
     * @throws WatermarkException 415 for a format Phase 1 doesn't watermark -
     *         which is most of what this DMS accepts at ingestion, and is
     *         deliberately a clear refusal rather than a silent passthrough
     *         of the unmarked original
     */
    public WatermarkFormatService forMimeType(String mimeType) {
        if (mimeType != null) {
            for (WatermarkFormatService service : services) {
                if (service.supports(mimeType)) {
                    return service;
                }
            }
        }
        throw WatermarkException.unsupportedType(
                "Watermarking isn't available for this kind of file yet. Supported: PDF, Word (.docx), "
                        + "Excel (.xlsx), PowerPoint (.pptx), JPEG and PNG.");
    }

    /** Whether a type can be watermarked at all - for the request-time check, before any job is created. */
    public boolean isSupported(String mimeType) {
        if (mimeType == null) {
            return false;
        }
        return services.stream().anyMatch(service -> service.supports(mimeType));
    }
}
