package com.oak.dms.signature.service.format;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/** A tiny transparent PNG, standing in for a real signature mark in these tests. */
final class SignaturePngFixture {

    private SignaturePngFixture() {
    }

    static byte[] bytes() {
        try {
            BufferedImage image = new BufferedImage(40, 20, BufferedImage.TYPE_INT_ARGB);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
