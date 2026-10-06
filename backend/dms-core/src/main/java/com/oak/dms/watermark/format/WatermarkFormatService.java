package com.oak.dms.watermark.format;

import java.io.IOException;
import java.io.InputStream;

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

    /** Whether this service handles that verified content type. */
    boolean supports(String mimeType);

    /**
     * @param input the document's bytes; closed by the caller
     * @throws IOException                                                if the bytes can't be read
     * @throws com.oak.dms.watermark.exception.WatermarkException         if the file is encrypted, signed,
     *                                                                    corrupt or past a configured cap -
     *                                                                    always with a message meant for the
     *                                                                    person who asked
     */
    byte[] watermark(InputStream input, WatermarkOptions options) throws IOException;
}
