package com.oak.dms.signature.service;

import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Renders a typed signature's name into a small transparent-background PNG,
 * for {@code DocumentStampService} to stamp onto a page - a "typed" visual
 * signature is only ever a font+text in the UI (`font-signature`, a script
 * font), with no image stored for it ({@code UserVisualSignature}'s own doc
 * comment), so placing it on the actual document needs one rendered here on
 * demand. Not the exact same typeface as the frontend's Google Font (no way
 * to share a web font with a server-side AWT renderer without bundling it) -
 * a built-in italic serif is a reasonable stand-in for a cursive signature
 * look without adding a font file to the backend.
 */
@Component
public class TypedSignatureRenderer {

    private static final int FONT_SIZE = 64;
    private static final int PADDING = 12;

    public byte[] render(String text) {
        Font font = new Font(Font.SERIF, Font.ITALIC, FONT_SIZE);

        BufferedImage measuring = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        FontMetrics metrics = measuring.createGraphics().getFontMetrics(font);
        int width = metrics.stringWidth(text) + PADDING * 2;
        int height = metrics.getHeight() + PADDING * 2;

        BufferedImage image = new BufferedImage(Math.max(1, width), Math.max(1, height), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setFont(font);
            g.setColor(Color.BLACK);
            g.drawString(text, PADDING, PADDING + metrics.getAscent());
        } finally {
            g.dispose();
        }

        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not render a typed signature", e);
        }
    }
}
