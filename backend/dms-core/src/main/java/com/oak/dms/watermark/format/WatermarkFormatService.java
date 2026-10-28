package com.oak.dms.watermark.format;

import java.io.IOException;
import java.io.InputStream;
import java.util.Set;

/**
 * One file format's way of putting the watermark onto a document, keeping
 * the document in its own format. Chosen by
 * {@link WatermarkServiceFactory} from the verified content type.
 * <p>
 * Implementations share one rule: the output is the input plus a mark, and
 * nothing else. Existing content, structure, metadata and security settings
 * stay exactly as they were - a watermarked copy that quietly lost a sheet's
 * charts, or a PDF's permissions, would be worse than no copy at all.
 * <p>
 * {@code byte[]} rather than a stream on both sides is deliberate: the input
 * size is capped per format well before this is reached
 * ({@code dms.watermark.max-bytes}), and every one of these libraries needs
 * random access to the whole file anyway, so streaming would buy nothing but
 * a temp file holding plaintext.
 */
public interface WatermarkFormatService {

    /**
     * The content types this service really handles.
     * <p>
     * Declared rather than answered by a {@code supports} method each
     * implementation writes for itself, so {@link WatermarkServiceFactory}
     * can also build the list of supported formats it TELLS users about.
     * A hard-coded list in an error message drifts: it would have promised
     * Word, Excel and PowerPoint from the moment it was written, and every
     * user who tried one would have got a 202 and then a job that failed.
     * Here, a format is advertised exactly when a service for it exists.
     */
    Set<String> mimeTypes();

    default boolean supports(String mimeType) {
        return mimeType != null && mimeTypes().contains(mimeType);
    }

    /**
     * @param input the document's bytes; closed by the caller
     * @throws IOException                                        if the bytes can't be read
     * @throws com.oak.dms.watermark.exception.WatermarkException if the file is encrypted, signed,
     *                                                            corrupt or past a configured cap -
     *                                                            always with a message meant for the
     *                                                            person who asked
     */
    byte[] watermark(InputStream input, WatermarkOptions options) throws IOException;
}
