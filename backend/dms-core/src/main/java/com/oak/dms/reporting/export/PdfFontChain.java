package com.oak.dms.reporting.export;

import org.apache.fontbox.ttf.OTFParser;
import org.apache.fontbox.ttf.TTFParser;
import org.apache.fontbox.ttf.TrueTypeCollection;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.io.RandomAccessReadBufferedFile;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Chooses, for every character of a report, a font that can draw it - and embeds only the fonts actually used.
 * <p>
 * Font files come from the server's own font directories ({@code /usr/share/fonts} in the runtime image, which installs the Noto
 * families for CJK, Arabic, Tamil and others; {@code C:\Windows\Fonts} on a developer's machine). The built-in Helvetica covers
 * Latin text when no font file is found. A character that NO font covers is printed as {@code ?} - a visible placeholder - and
 * never fails the run (brief section 5).
 * <p>
 * Known limit: PDFBox places glyphs one by one and does no text shaping. CJK, Cyrillic, Greek and similar print correctly;
 * scripts that need shaping (Arabic joining and direction, Sinhala and Tamil vowel signs) print with the right glyphs but not
 * with typographically correct forms.
 */
final class PdfFontChain implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(PdfFontChain.class);

    /** Parsing a font file is not free; a document asking for exotic characters stops looking after this many. */
    private static final int MAX_FACE_PARSES = 160;
    private static final List<String> PREFERRED = List.of("dejavusans.ttf", "notosans-regular.ttf", "liberationsans-regular.ttf",
            "arial.ttf", "segoeui.ttf");
    private static final String PLACEHOLDER = "?";

    /** A parsed font file (for coverage); it is embedded in the PDF only when a character is actually drawn with it. */
    private final class Face {
        private final TrueTypeFont parsed;
        private PDFont pdf;

        Face(TrueTypeFont parsed) {
            this.parsed = parsed;
        }

        TrueTypeFont parsed() {
            return parsed;
        }

        PDFont pdf() {
            if (pdf == null) {
                try {
                    pdf = PDType0Font.load(document, parsed, true);
                } catch (IOException | RuntimeException e) {
                    log.debug("Could not embed a font: {}", e.toString());
                    return null;
                }
            }
            return pdf;
        }
    }

    /** A piece of text to draw in one font. */
    record Run(PDFont font, String text) {
    }

    private final PDDocument document;
    private final List<Path> candidates;
    private int nextCandidate = 0;
    private int parses = 0;
    private final List<Face> faces = new ArrayList<>();
    private final Map<Integer, PDFont> chosen = new HashMap<>();
    private final Set<Integer> missing = new HashSet<>();
    private final PDFont helvetica = new PDType1Font(Standard14Fonts.FontName.HELVETICA);

    PdfFontChain(PDDocument document, List<Path> fontDirectories) {
        this.document = document;
        this.candidates = discover(fontDirectories);
    }

    /** The font files under the given directories, preferred ones first. */
    static List<Path> discover(List<Path> directories) {
        List<Path> files = new ArrayList<>();
        for (Path dir : directories) {
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(dir, 6)) {
                walk.filter(Files::isRegularFile).filter(p -> {
                    String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                    return n.endsWith(".ttf") || n.endsWith(".otf") || n.endsWith(".ttc");
                }).forEach(files::add);
            } catch (IOException | RuntimeException e) {
                log.debug("Could not list fonts under {}", dir, e);
            }
        }
        files.sort((a, b) -> {
            int pa = rank(a);
            int pb = rank(b);
            return pa != pb ? Integer.compare(pa, pb) : a.toString().compareTo(b.toString());
        });
        return files;
    }

    private static int rank(Path p) {
        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
        int preferred = PREFERRED.indexOf(n);
        if (preferred >= 0) {
            return preferred;
        }
        if (n.contains("noto") && n.contains("regular")) {
            return 100;
        }
        return n.contains("noto") ? 200 : 300;
    }

    /** The text split into runs, each in a font that can draw it; unsupported characters become {@code ?}. */
    List<Run> runs(String text) {
        List<Run> runs = new ArrayList<>();
        PDFont current = null;
        StringBuilder buffer = new StringBuilder();
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            PDFont font = fontFor(cp);
            String piece;
            if (font == null) {
                font = placeholderFont();
                piece = PLACEHOLDER;
            } else {
                piece = new String(Character.toChars(cp));
            }
            if (font != current && buffer.length() > 0) {
                runs.add(new Run(current, buffer.toString()));
                buffer.setLength(0);
            }
            current = font;
            buffer.append(piece);
        }
        if (buffer.length() > 0) {
            runs.add(new Run(current, buffer.toString()));
        }
        return runs;
    }

    /** Width in points of text at a size, drawn the way {@link #runs} would. */
    float width(String text, float size) {
        float total = 0;
        for (Run run : runs(text)) {
            try {
                total += run.font().getStringWidth(run.text()) / 1000f * size;
            } catch (IOException | IllegalArgumentException e) {
                total += size * run.text().length() * 0.6f;
            }
        }
        return total;
    }

    private PDFont placeholderFont() {
        PDFont font = fontFor('?');
        return font != null ? font : helvetica;
    }

    private PDFont fontFor(int cp) {
        PDFont known = chosen.get(cp);
        if (known != null) {
            return known;
        }
        if (missing.contains(cp)) {
            return null;
        }
        PDFont fromOpen = coveredByOpenFace(cp);
        if (fromOpen != null) {
            return fromOpen;
        }
        while (nextCandidate < candidates.size() && parses < MAX_FACE_PARSES) {
            Path file = candidates.get(nextCandidate++);
            faces.addAll(open(file));
            fromOpen = coveredByOpenFace(cp);
            if (fromOpen != null) {
                return fromOpen;
            }
        }
        // The built-in Helvetica draws Latin-1 and the common Windows-1252 punctuation.
        if (cp < 0x2000 || cp == 0x2026 || cp == 0x20AC) {
            try {
                helvetica.encode(new String(Character.toChars(cp)));
                return remember(cp, helvetica);
            } catch (IOException | IllegalArgumentException notDrawable) {
                // fall through to the placeholder
            }
        }
        missing.add(cp);
        return null;
    }

    private PDFont coveredByOpenFace(int cp) {
        for (Face face : faces) {
            if (covers(face.parsed(), cp)) {
                PDFont font = face.pdf();
                if (font != null) {
                    return remember(cp, font);
                }
            }
        }
        return null;
    }

    private PDFont remember(int cp, PDFont font) {
        chosen.put(cp, font);
        return font;
    }

    private static boolean covers(TrueTypeFont font, int cp) {
        try {
            var lookup = font.getUnicodeCmapLookup(false);
            return lookup != null && lookup.getGlyphId(cp) > 0;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** Opens a font file (a collection can hold several faces) and embeds each as a subset. */
    private List<Face> open(Path file) {
        List<Face> opened = new ArrayList<>();
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        try {
            File f = file.toFile();
            if (name.endsWith(".ttc")) {
                TrueTypeCollection collection = new TrueTypeCollection(f);
                openedCollections.add(collection);
                collection.processAllFonts(ttf -> {
                    parses++;
                    opened.add(new Face(ttf));
                });
            } else {
                parses++;
                TrueTypeFont ttf = name.endsWith(".otf")
                        ? new OTFParser().parse(new RandomAccessReadBufferedFile(f))
                        : new TTFParser().parse(new RandomAccessReadBufferedFile(f));
                opened.add(new Face(ttf));
            }
        } catch (IOException | RuntimeException e) {
            log.debug("Skipping font file {}: {}", file, e.toString());
        }
        return opened;
    }

    private final List<TrueTypeCollection> openedCollections = new ArrayList<>();

    @Override
    public void close() {
        for (Face face : faces) {
            try {
                face.parsed().close();
            } catch (IOException ignored) {
                // nothing useful to do
            }
        }
        for (TrueTypeCollection collection : openedCollections) {
            try {
                collection.close();
            } catch (IOException ignored) {
                // nothing useful to do
            }
        }
    }
}
