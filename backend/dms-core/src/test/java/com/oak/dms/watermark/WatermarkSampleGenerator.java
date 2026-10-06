package com.oak.dms.watermark;

import com.oak.dms.watermark.config.WatermarkProperties;
import com.oak.dms.watermark.dto.WatermarkSpec;
import com.oak.dms.watermark.enums.WatermarkPageScope;
import com.oak.dms.watermark.enums.WatermarkPlacement;
import com.oak.dms.watermark.format.DocxWatermarkService;
import com.oak.dms.watermark.format.FontProvider;
import com.oak.dms.watermark.format.ImageWatermarkService;
import com.oak.dms.watermark.format.PdfWatermarkService;
import com.oak.dms.watermark.format.PptxWatermarkService;
import com.oak.dms.watermark.format.WatermarkFormatService;
import com.oak.dms.watermark.format.WatermarkOptions;
import com.oak.dms.watermark.format.WatermarkRenderer;
import com.oak.dms.watermark.format.XlsxWatermarkService;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Produces one watermarked file per format, placement and page scope, turns
 * every Office file into a PDF through Gotenberg, and rasterises every page
 * into PNGs in a folder you can open and LOOK at.
 *
 * <h2>Why this exists and why it is not a test</h2>
 * Every bug this feature has had that mattered was invisible to assertions.
 * The tiling that produced a completely blank page, the Arabic that came out
 * as unjoined isolated letters, the Hebrew that came out left to right, the
 * font that resolved differently under concurrency - all of them passed
 * "there is a picture in the output" and all of them were obvious within two
 * seconds of looking at a rendered page. Structural tests prove the XML is
 * in the right part; they cannot prove the result is a watermark a human
 * would recognise.
 * <p>
 * It asserts almost nothing on purpose. It is a darkroom, not a gate: it
 * makes the evidence, a person judges it. Gating CI on image comparison
 * would mean a golden-image suite that breaks on every font package bump.
 *
 * <h2>Running it</h2>
 * {@code tools/watermark-samples.sh}, or by hand: start a Gotenberg with a
 * reachable port and set two environment variables -
 * {@code WATERMARK_SAMPLES_DIR} (where to write; chosen deliberately OUTSIDE
 * the repository so samples are never committed) and optionally
 * {@code WATERMARK_SAMPLES_GOTENBERG} (default
 * {@code http://localhost:3099}). Without the first variable this does not
 * run at all, which is how it stays out of CI.
 */
@Tag("samples")
@EnabledIfEnvironmentVariable(named = "WATERMARK_SAMPLES_DIR", matches = ".+",
        disabledReason = "Sample generation is run by hand - see tools/watermark-samples.sh")
class WatermarkSampleGenerator {

    /**
     * Deliberately includes a non-Latin name. A watermark carries the name
     * of the person who took the copy, and "it works" has meant "it works
     * for ASCII" in every round of this feature so far.
     */
    private static final Map<String, String> TEXTS = new LinkedHashMap<>(Map.of(
            "latin", "CONFIDENTIAL - Jane Okafor",
            "arabic", "سري - محمد الأحمد",
            "devanagari", "गोपनीय - प्रिया शर्मा"));

    private WatermarkProperties properties;
    private WatermarkRenderer renderer;

    @Test
    void writeSamples() throws Exception {
        Path root = Path.of(System.getenv("WATERMARK_SAMPLES_DIR"));
        Files.createDirectories(root);
        properties = new WatermarkProperties();
        renderer = new WatermarkRenderer(new FontProvider(properties), properties);

        Map<String, Source> sources = new LinkedHashMap<>();
        sources.put("pdf", new Source(samplePdf(), "application/pdf", "sample.pdf",
                new PdfWatermarkService(renderer, properties)));
        sources.put("docx", new Source(OfficeFixtures.multiSectionDocx(), OfficeFixtures.DOCX_MIME,
                "sample.docx", new DocxWatermarkService(renderer, properties)));
        sources.put("xlsx", new Source(OfficeFixtures.richXlsx(), OfficeFixtures.XLSX_MIME,
                "sample.xlsx", new XlsxWatermarkService(renderer, properties)));
        sources.put("pptx", new Source(OfficeFixtures.plainPptx(3), OfficeFixtures.PPTX_MIME,
                "sample.pptx", new PptxWatermarkService(renderer, properties)));
        sources.put("png", new Source(samplePng(), "image/png", "sample.png",
                new ImageWatermarkService(renderer, properties)));

        List<String> written = new ArrayList<>();
        for (Map.Entry<String, Source> format : sources.entrySet()) {
            for (WatermarkPlacement placement : WatermarkPlacement.values()) {
                written.add(produce(root, format.getKey(), format.getValue(), placement,
                        WatermarkPageScope.ALL_PAGES, -45, "latin"));
            }
            // first-page-only means something different in each format, and
            // the differences are the thing worth looking at.
            written.add(produce(root, format.getKey(), format.getValue(), WatermarkPlacement.TILED,
                    WatermarkPageScope.FIRST_PAGE_ONLY, -45, "latin"));
            // No rotation, so a reviewer can see the mark is not simply
            // always drawn at the same angle regardless of the request.
            written.add(produce(root, format.getKey(), format.getValue(), WatermarkPlacement.CENTERED,
                    WatermarkPageScope.ALL_PAGES, 0, "latin"));
            for (String script : List.of("arabic", "devanagari")) {
                written.add(produce(root, format.getKey(), format.getValue(), WatermarkPlacement.TILED,
                        WatermarkPageScope.ALL_PAGES, -45, script));
            }
        }

        rasterise(root);
        Files.writeString(root.resolve("README.txt"), readme(written));
        System.out.println("\n=== Watermark samples written to: " + root.toAbsolutePath() + " ===");
        System.out.println("Open the 'pages' folder and look at the PNGs.\n");
    }

    private String produce(Path root, String format, Source source, WatermarkPlacement placement,
                           WatermarkPageScope scope, int rotation, String script) throws Exception {
        WatermarkSpec spec = new WatermarkSpec(TEXTS.get(script), 40, 30, rotation, "#c00000",
                placement, scope);
        byte[] output = source.service().watermark(new ByteArrayInputStream(source.bytes()),
                new WatermarkOptions(spec, source.mimeType(), source.filename()));

        String name = format + "__" + placement.name().toLowerCase(java.util.Locale.ROOT)
                + "__" + scope.name().toLowerCase(java.util.Locale.ROOT)
                + "__rot" + rotation + "__" + script + "." + format;
        Path file = root.resolve("files").resolve(name);
        Files.createDirectories(file.getParent());
        Files.write(file, output);
        return name;
    }

    /**
     * Every produced file as PNG pages. Office files go through Gotenberg
     * (the project's own LibreOffice container) first, because nothing in
     * this JVM can lay out a .docx - which is precisely why the structural
     * tests cannot answer the question this folder answers.
     */
    private void rasterise(Path root) throws Exception {
        Path pages = root.resolve("pages");
        Files.createDirectories(pages);
        String gotenberg = System.getenv().getOrDefault("WATERMARK_SAMPLES_GOTENBERG",
                "http://localhost:3099");

        try (var files = Files.list(root.resolve("files"))) {
            for (Path file : files.sorted().toList()) {
                String name = file.getFileName().toString();
                byte[] bytes = Files.readAllBytes(file);
                try {
                    if (name.endsWith(".png") || name.endsWith(".jpg")) {
                        Files.copy(file, pages.resolve(name), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        continue;
                    }
                    byte[] pdf = name.endsWith(".pdf") ? bytes : toPdf(gotenberg, name, bytes);
                    writePages(pages, name, pdf);
                } catch (Exception e) {
                    // One format failing to convert must not cost the whole
                    // folder - the other samples are still worth looking at.
                    System.out.println("Could not rasterise " + name + ": " + e);
                }
            }
        }
    }

    private byte[] toPdf(String gotenberg, String filename, byte[] bytes) throws Exception {
        String boundary = "----watermarksamples" + System.nanoTime();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"files\"; filename=\""
                + filename + "\"\r\nContent-Type: application/octet-stream\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        body.write(bytes);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(gotenberg + "/forms/libreoffice/convert"))
                .timeout(Duration.ofMinutes(2))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build();
        // Not try-with-resources: HttpClient only became AutoCloseable in
        // Java 21 and this module compiles at language level 17.
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10)).build();
        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Gotenberg answered " + response.statusCode() + " - is it "
                    + "running and reachable at " + gotenberg + "? (tools/watermark-samples.sh starts one)");
        }
        return response.body();
    }

    private void writePages(Path pages, String name, byte[] pdf) throws Exception {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            PDFRenderer pdfRenderer = new PDFRenderer(document);
            int limit = Math.min(document.getNumberOfPages(), 3);
            for (int page = 0; page < limit; page++) {
                BufferedImage image = pdfRenderer.renderImageWithDPI(page, 110);
                ImageIO.write(image, "png",
                        pages.resolve(name + ".p" + (page + 1) + ".png").toFile());
            }
        }
    }

    private static byte[] samplePdf() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (PDDocument document = new PDDocument()) {
            for (int page = 0; page < 2; page++) {
                PDPage pdPage = new PDPage(page == 0 ? PDRectangle.A4
                        : new PDRectangle(PDRectangle.A4.getHeight(), PDRectangle.A4.getWidth()));
                document.addPage(pdPage);
                try (PDPageContentStream content = new PDPageContentStream(document, pdPage)) {
                    content.beginText();
                    content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 14);
                    content.newLineAtOffset(72, pdPage.getMediaBox().getHeight() - 80);
                    content.showText("Sample page " + (page + 1) + " - body text that must stay readable");
                    content.endText();
                }
            }
            document.save(out);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return out.toByteArray();
    }

    private static byte[] samplePng() {
        BufferedImage image = new BufferedImage(1200, 800, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, 1200, 800);
        graphics.setColor(new Color(220, 230, 240));
        for (int x = 0; x < 1200; x += 80) {
            graphics.fillRect(x, 0, 40, 800);
        }
        graphics.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, "png", out);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return out.toByteArray();
    }

    private String readme(List<String> written) {
        return """
                Watermark samples
                =================

                files/   the watermarked documents exactly as the service produced them
                pages/   every one of those rendered to PNG so it can be looked at

                Office files were converted to PDF by Gotenberg (LibreOffice) before
                rendering, so what you see is close to what a recipient opening the file
                in LibreOffice would see. Check them in desktop Word, Excel and
                PowerPoint too: those lay out headers and anchors slightly differently,
                and the in-app preview (SheetJS / docx-preview) may not show the mark
                at all.

                What to look for
                ----------------
                * tiled      - the mark covers the whole page, including the corners,
                               staggered so there are no clear horizontal lanes
                * centered   - one mark, in the middle, legible
                * corner     - a small badge top-right, fully on the page
                * header-footer - a line at the top AND one at the bottom
                * rot0 files - the mark is horizontal, not diagonal
                * first_page_only - docx: page 1 only, and page 1 keeps its letterhead
                                    xlsx: the first VISIBLE sheet only
                                    pptx: slide 1 only
                * arabic     - letters JOINED and running right to left
                * devanagari - conjuncts formed, not broken into separate letters
                * every file - the original content is still readable underneath

                %d files generated.
                """.formatted(written.size());
    }

    private record Source(byte[] bytes, String mimeType, String filename, WatermarkFormatService service) {
    }
}
