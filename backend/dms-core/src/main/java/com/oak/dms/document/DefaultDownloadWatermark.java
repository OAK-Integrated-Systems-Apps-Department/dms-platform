package com.oak.dms.document;

import com.oak.dms.checkout.EmbeddedSignatureDetector;
import com.oak.dms.document.dto.DownloadedDocument;
import com.oak.dms.watermark.service.WatermarkDefaultsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * The tenant's default download watermark, applied to an explicit download of a stored version (DOC-16): the document's
 * plain download ({@code GET /documents/{id}/content}) and the download of one named version, current or older
 * ({@code GET /documents/{id}/versions/{versionId}/content}). One place, so the two cannot disagree about what is
 * watermarked. Not used by an inline preview or by a bulk export, which have their own policies.
 * <p>
 * <b>Streaming first (DOC-02).</b> Whether the watermark applies at all is decided from the version's recorded details,
 * before a byte is read ({@link WatermarkDefaultsService#isDownloadWatermarkApplicable}); in the usual case - the tenant
 * has not turned it on, or the file is unsupported, over the size cap, or the template is retired - the stored stream is
 * returned untouched and never buffered. Only a file that will really be watermarked is read into memory (the watermark
 * libraries need all of it), and only within the per-format cap judged on the recorded size.
 * <p>
 * <b>Signed files are exempt, byte for byte (DOC-16).</b> Any change to a digitally signed PDF or Office file breaks its
 * signature, so such a file is returned exactly as stored and the watermark is skipped on purpose: this is a policy
 * exemption, not an error, and it is reported to the caller as {@link #SIGNED_CONTENT_EXEMPT} so the download's audit event
 * can say so. The check reuses {@link EmbeddedSignatureDetector} - the detector the check-out and editing guards already
 * trust - on the bytes already read for watermarking, so it costs no extra read. If the detector cannot tell, the
 * watermark code's own refusal of a signed file remains the safety net ({@link WatermarkDefaultsService} serves the original
 * on any failure). Signatures the DMS itself records are bound to the stored version, which this never changes.
 * <p>
 * It never fails a download: {@link WatermarkDefaultsService} serves the original whenever it cannot watermark.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultDownloadWatermark {

    /** The audit and log reason when a signed file is served without the watermark. */
    public static final String SIGNED_CONTENT_EXEMPT = "SIGNED_CONTENT_EXEMPT";

    private final WatermarkDefaultsService watermarkDefaultsService;

    /**
     * @param document  what to send: the stream to send, which may now be a watermarked copy held in memory
     * @param exemption {@link #SIGNED_CONTENT_EXEMPT} when the file was left alone because it is signed, else null
     */
    public record Outcome(DownloadedDocument document, String exemption) {
    }

    /**
     * @param downloaded the version's stored content, opened but not yet read
     * @param details    the requested version - its own number, name, type and recorded size - so a placeholder such as
     *                   {@code {documentVersion}} names this version, never the document's current one
     */
    public Outcome apply(DownloadedDocument downloaded, DocumentContentReader.VersionDetails details) {
        if (!watermarkDefaultsService.isDownloadWatermarkApplicable(details)) {
            return new Outcome(downloaded, null);
        }

        byte[] original;
        try (InputStream content = downloaded.content()) {
            original = content.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("Could not read document " + details.documentId() + " for download", e);
        }

        if (carriesSignature(details.originalFilename(), original)) {
            // Structured and free of content: ids and the reason only.
            log.info("Default download watermark not applied: reason={} documentId={} versionId={} versionNumber={}",
                    SIGNED_CONTENT_EXEMPT, details.documentId(), details.versionId(), details.versionNumber());
            return new Outcome(new DownloadedDocument(new ByteArrayInputStream(original), original.length,
                    downloaded.contentType(), downloaded.filename()), SIGNED_CONTENT_EXEMPT);
        }

        byte[] result = watermarkDefaultsService.applyIfConfigured(original, details);
        return new Outcome(new DownloadedDocument(new ByteArrayInputStream(result), result.length,
                downloaded.contentType(), downloaded.filename()), null);
    }

    private static boolean carriesSignature(String filename, byte[] bytes) {
        try {
            return EmbeddedSignatureDetector.canHoldSignature(filename)
                    && EmbeddedSignatureDetector.hasSignature(filename, new ByteArrayInputStream(bytes));
        } catch (IOException | RuntimeException e) {
            return false; // cannot tell: the watermark code's own refusal of a signed file still protects it
        }
    }
}
