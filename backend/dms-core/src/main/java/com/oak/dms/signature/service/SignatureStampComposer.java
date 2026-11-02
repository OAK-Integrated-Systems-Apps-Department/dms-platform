package com.oak.dms.signature.service;

import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Composes the chosen visual mark (typed/drawn/uploaded) together with the
 * signer's name, position, department and the date/time into ONE transparent-
 * background PNG - what {@code SignaturePlacementService} actually stamps
 * onto the page, so the mark is never shown without this identifying text
 * next to it. Position/department are each omitted (not left blank) when the
 * signer hasn't set one - see {@code User#getPosition()}'s own doc comment.
 */
@Component
public class SignatureStampComposer {

    private static final int MARK_WIDTH = 260;
    private static final int MARK_MAX_HEIGHT = 90;
    private static final int TEXT_WIDTH = 320;
    private static final int LINE_HEIGHT = 18;
    private static final int GAP = 6;
    private static final int PADDING = 4;
    private static final Font NAME_FONT = new Font(Font.SANS_SERIF, Font.BOLD, 15);
    private static final Font DETAIL_FONT = new Font(Font.SANS_SERIF, Font.PLAIN, 13);
    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.ENGLISH);

    public record SignerInfo(String name, String position, String department, OffsetDateTime signedAt) {
    }

    public byte[] compose(byte[] markPng, SignerInfo signer) {
        BufferedImage mark = read(markPng);
        int scaledMarkWidth = Math.min(MARK_WIDTH, mark.getWidth());
        int scaledMarkHeight = Math.min(MARK_MAX_HEIGHT,
                Math.round((float) mark.getHeight() * scaledMarkWidth / mark.getWidth()));

        List<String> lines = new ArrayList<>();
        lines.add(signer.name());
        if (signer.position() != null && !signer.position().isBlank()) {
            lines.add(signer.position());
        }
        if (signer.department() != null && !signer.department().isBlank()) {
            lines.add(signer.department());
        }
        lines.add("Signed: " + signer.signedAt().format(TIMESTAMP_FORMAT));

        int width = Math.max(scaledMarkWidth, TEXT_WIDTH) + PADDING * 2;
        int textBlockHeight = lines.size() * LINE_HEIGHT;
        int height = scaledMarkHeight + GAP + textBlockHeight + PADDING * 2;

        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(mark, PADDING, PADDING, scaledMarkWidth, scaledMarkHeight, null);

            int y = PADDING + scaledMarkHeight + GAP;
            for (int i = 0; i < lines.size(); i++) {
                Font font = i == 0 ? NAME_FONT : DETAIL_FONT;
                g.setFont(font);
                g.setColor(Color.BLACK);
                FontMetrics metrics = g.getFontMetrics();
                g.drawString(lines.get(i), PADDING, y + metrics.getAscent());
                y += LINE_HEIGHT;
            }
        } finally {
            g.dispose();
        }
        return write(out);
    }

    private static BufferedImage read(byte[] png) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
            if (image == null) {
                throw new IllegalArgumentException("Could not read the signature mark image");
            }
            return image;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] write(BufferedImage image) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
