package com.oak.dms.watermark.format;

import com.oak.dms.watermark.config.WatermarkProperties;
import com.oak.dms.watermark.exception.WatermarkException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.imageio.IIOException;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;

/**
 * Watermarks a JPEG or PNG by compositing the mark over the picture.
 *
 * <h2>The order of operations matters</h2>
 * <ol>
 *   <li><b>Measure before decoding.</b> A JPEG's dimensions live in its
 *       header; the pixels do not. A 50-megapixel photo is a few megabytes
 *       on disk and ~200MB once decoded into an {@code int[]}, so the size
 *       cap is checked from the header and the file refused before
 *       {@code ImageIO.read} is ever called. Checking after decoding would
 *       mean the cap only fires once the damage is done.</li>
 *   <li><b>Apply EXIF orientation.</b> See {@link ExifOrientation} - a
 *       portrait phone photo decodes sideways, and a mark drawn before the
 *       turn ends up running up the side of the picture.</li>
 *   <li><b>Convert the colour model.</b> Indexed, grayscale and 16-bit
 *       images can't represent the blend of a semi-transparent mark.</li>
 *   <li><b>Composite, then encode.</b></li>
 * </ol>
 *
 * <h2>What the copy loses</h2>
 * The output is re-encoded, so it carries no EXIF: no GPS, no camera, no
 * capture time. That is unavoidable once the orientation has been baked into
 * the pixels, and arguably right for a distribution copy, but it is a real
 * difference from the original and is documented on the endpoint.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImageWatermarkService implements WatermarkFormatService {

    private static final String MIME_JPEG = "image/jpeg";
    private static final String MIME_PNG = "image/png";
    /** JPEG quality for the copy. High enough that re-encoding is not what anyone notices. */
    private static final float JPEG_QUALITY = 0.92f;
    /**
     * The width at which the requested point size means one pixel. A 40pt
     * mark should look the same on a 900px scan and a 6000px photo, so the
     * mark is scaled against the picture rather than fixed in pixels.
     */
    private static final double REFERENCE_WIDTH_PX = 1000;

    private final WatermarkRenderer renderer;
    private final WatermarkProperties properties;

    @Override
    public java.util.Set<String> mimeTypes() {
        return java.util.Set.of(MIME_JPEG, MIME_PNG);
    }

    @Override
    public byte[] watermark(InputStream input, WatermarkOptions options) throws IOException {
        byte[] source = input.readAllBytes();
        boolean png = MIME_PNG.equals(options.mimeType());

        checkPixelCountBeforeDecoding(source);

        BufferedImage decoded = decode(source);
        BufferedImage upright = png ? decoded : ExifOrientation.apply(decoded, ExifOrientation.of(source));
        // PNG keeps its alpha; JPEG has none, so transparency is flattened
        // onto white rather than encoded as black.
        BufferedImage canvas = Images.toDrawable(upright, png);

        // Resolved once, before anything is drawn: one image, but the same
        // rule as the paged formats, and the refusal still happens up front.
        draw(canvas, options, renderer.resolveFont(options.spec().text()));
        return png ? encodePng(canvas) : encodeJpeg(canvas);
    }

    // --------------------------------------------------------------- read

    /**
     * Refuses an oversized image from its header alone. Also the first place
     * a file that isn't really an image at all gets caught, since a reader
     * that can't even report dimensions certainly can't decode it.
     */
    private void checkPixelCountBeforeDecoding(byte[] source) throws IOException {
        long maxPixels = properties.getLimits().getMaxImagePixels();
        try (var stream = ImageIO.createImageInputStream(new java.io.ByteArrayInputStream(source))) {
            Iterator<ImageReader> readers = stream == null
                    ? java.util.Collections.emptyIterator()
                    : ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) {
                throw WatermarkException.unprocessable("WATERMARK_IMAGE_UNREADABLE",
                        "This image could not be read. It may be damaged or saved in an unusual way.");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(stream, true, true);
                long pixels = (long) reader.getWidth(0) * reader.getHeight(0);
                if (pixels > maxPixels) {
                    throw WatermarkException.tooLarge("This image is " + (pixels / 1_000_000) + " megapixels, and "
                            + (maxPixels / 1_000_000) + " is the most that can be watermarked.");
                }
            } finally {
                reader.dispose();
            }
        } catch (IIOException e) {
            throw WatermarkException.unprocessable("WATERMARK_IMAGE_UNREADABLE",
                    "This image could not be read. It may be damaged or saved in an unusual way.");
        }
    }

    private BufferedImage decode(byte[] source) throws IOException {
        BufferedImage image;
        try {
            image = ImageIO.read(new java.io.ByteArrayInputStream(source));
        } catch (IIOException e) {
            // Where a CMYK JPEG lands: ImageIO has no reader for Adobe's
            // inverted-CMYK variant and says so rather than guessing.
            throw WatermarkException.unprocessable("WATERMARK_IMAGE_UNSUPPORTED_COLOUR",
                    "This image uses a colour format (such as CMYK) that can't be watermarked. "
                            + "Save it as a standard RGB JPEG or PNG and try again.");
        }
        if (image == null) {
            throw WatermarkException.unprocessable("WATERMARK_IMAGE_UNREADABLE",
                    "This image could not be read. It may be damaged or saved in an unusual way.");
        }
        return image;
    }

    // --------------------------------------------------------------- draw

    private void draw(BufferedImage canvas, WatermarkOptions options, FontChoice font) {
        RenderTarget target = RenderTarget.ofPixels(canvas.getWidth(), canvas.getHeight(), REFERENCE_WIDTH_PX);
        BufferedImage mark = renderer.render(options.spec(), target, font);

        Graphics2D graphics = canvas.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            // SrcOver with the mark's own per-pixel alpha: opacity is already
            // baked into the rendered image, so no extra composite alpha here
            // (which would multiply the two and wash the mark out).
            graphics.setComposite(AlphaComposite.SrcOver);
            // The render cap may have shrunk the mark below the picture's
            // size; drawn scaled so it still covers the whole frame.
            graphics.drawImage(mark, 0, 0, canvas.getWidth(), canvas.getHeight(), null);
        } finally {
            graphics.dispose();
        }
    }

    // -------------------------------------------------------------- write

    private byte[] encodePng(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
        if (!ImageIO.write(image, "png", out)) {
            throw WatermarkException.unavailable("WATERMARK_NO_PNG_WRITER",
                    "This server has no PNG encoder available, so watermarks can't be produced.");
        }
        return out.toByteArray();
    }

    private byte[] encodeJpeg(BufferedImage image) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw WatermarkException.unavailable("WATERMARK_NO_JPEG_WRITER",
                    "This server has no JPEG encoder available, so watermarks can't be produced.");
        }
        ImageWriter writer = writers.next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(64 * 1024);
        try (ImageOutputStream out = ImageIO.createImageOutputStream(bytes)) {
            ImageWriteParam params = writer.getDefaultWriteParam();
            if (params.canWriteCompressed()) {
                params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                params.setCompressionQuality(JPEG_QUALITY);
            }
            writer.setOutput(out);
            writer.write(null, new javax.imageio.IIOImage(image, null, null), params);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }
}
