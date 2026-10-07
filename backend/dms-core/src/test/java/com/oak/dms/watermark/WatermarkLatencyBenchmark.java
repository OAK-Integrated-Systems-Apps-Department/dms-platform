package com.oak.dms.watermark;

import com.oak.dms.watermark.config.WatermarkProperties;
import com.oak.dms.watermark.dto.WatermarkSpec;
import com.oak.dms.watermark.enums.WatermarkPageScope;
import com.oak.dms.watermark.enums.WatermarkPlacement;
import com.oak.dms.watermark.format.DocxWatermarkService;
import com.oak.dms.watermark.format.FontProvider;
import com.oak.dms.watermark.format.OoxmlGuardsProbe;
import com.oak.dms.watermark.format.PptxWatermarkService;
import com.oak.dms.watermark.format.WatermarkFormatService;
import com.oak.dms.watermark.format.WatermarkOptions;
import com.oak.dms.watermark.format.WatermarkRenderer;
import com.oak.dms.watermark.format.XlsxWatermarkService;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.awt.geom.Rectangle2D;
import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How long the largest file each format allows actually takes, measured
 * through the real services and the real {@code OoxmlGuards} pass.
 *
 * <h2>Why this is not an ordinary test</h2>
 * It builds files at the configured per-format ceiling - 25 MB of Word, 15 MB
 * of PowerPoint, 10 MB of Excel - which takes minutes and a couple of
 * gigabytes of heap, and its numbers depend entirely on the machine. Running
 * it in CI would make the build slow and flaky and the result meaningless.
 * So it is off unless {@code WATERMARK_BENCHMARK=1}, and what it asserts is
 * only that nothing has gone catastrophically wrong (the 60 s format
 * timeout); the p95 judgement is a human reading the printed table.
 *
 * <h2>What it does and does not measure</h2>
 * It measures the part this change owns: the guard pass plus the format
 * service. It deliberately excludes storage, encryption and the queue, which
 * are shared with every other feature and are measured elsewhere - and
 * excludes queue WAIT, which is unbounded by design and is why the feature
 * is asynchronous in the first place. A user never waits on any of this: the
 * POST returns 202 in milliseconds. These numbers decide how long a copy
 * takes to BECOME ready, not how long a request takes.
 */
@Tag("benchmark")
@EnabledIfEnvironmentVariable(named = "WATERMARK_BENCHMARK", matches = "1",
        disabledReason = "Latency benchmark is run by hand - set WATERMARK_BENCHMARK=1")
class WatermarkLatencyBenchmark {

    private static final int RUNS = 3;

    private final WatermarkProperties properties = new WatermarkProperties();
    private final WatermarkRenderer renderer = new WatermarkRenderer(new FontProvider(properties), properties);

    @Test
    void theLargestAllowedFileOfEachFormatStaysWellInsideTheTimeout() {
        List<Result> results = new ArrayList<>();

        results.add(measure("docx", 25, OfficeFixtures.DOCX_MIME, "big.docx",
                new DocxWatermarkService(renderer, properties), bigDocx()));
        results.add(measure("pptx", 15, OfficeFixtures.PPTX_MIME, "big.pptx",
                new PptxWatermarkService(renderer, properties), bigPptx()));
        results.add(measure("xlsx", 10, OfficeFixtures.XLSX_MIME, "big.xlsx",
                new XlsxWatermarkService(renderer, properties), bigXlsx()));

        System.out.println();
        System.out.println("=== Watermark latency at the per-format size ceiling ===");
        System.out.printf("%-6s %10s %10s %10s %10s %12s%n",
                "format", "size MB", "best ms", "median ms", "worst ms", "of which");
        for (Result result : results) {
            System.out.printf("%-6s %10.1f %10d %10d %10d %12s%n", result.format(),
                    result.sizeBytes() / 1024.0 / 1024.0, result.bestMs(), result.medianMs(),
                    result.worstMs(), "guard " + result.guardMedianMs() + " ms");
        }
        System.out.println("Target: p95 < 5000 ms. Format timeout: "
                + properties.getFormatTimeout().toMillis() + " ms.");
        System.out.println();

        Duration timeout = properties.getFormatTimeout();
        for (Result result : results) {
            assertThat(result.worstMs())
                    .describedAs("%s at %.1f MB must finish well inside the format timeout",
                            result.format(), result.sizeBytes() / 1024.0 / 1024.0)
                    .isLessThan(timeout.toMillis());
        }
    }

    private Result measure(String format, int targetMb, String mimeType, String filename,
                           WatermarkFormatService service, byte[] source) {
        WatermarkSpec spec = new WatermarkSpec("CONFIDENTIAL - Jane Okafor", 40, 30, -45, "#808080",
                WatermarkPlacement.TILED, WatermarkPageScope.ALL_PAGES);
        WatermarkOptions options = new WatermarkOptions(spec, mimeType, filename);

        // The guard pass timed separately, because "is the new zip walk the
        // expensive part?" is the first question anyone will ask about
        // these numbers, and the answer decides whether to tune the guard
        // or the format service.
        long[] guard = new long[RUNS];
        long[] timings = new long[RUNS];
        for (int run = 0; run < RUNS; run++) {
            long guardStartedAt = System.nanoTime();
            OoxmlGuardsProbe.check(source, properties.getLimits());
            guard[run] = (System.nanoTime() - guardStartedAt) / 1_000_000;

            long startedAt = System.nanoTime();
            try {
                service.watermark(new ByteArrayInputStream(source), options);
            } catch (Exception e) {
                throw new IllegalStateException("Benchmark run failed for " + format, e);
            }
            timings[run] = (System.nanoTime() - startedAt) / 1_000_000;
        }
        java.util.Arrays.sort(guard);
        java.util.Arrays.sort(timings);
        System.out.printf("  %s built at %.1f MB (target %d MB)%n", format,
                source.length / 1024.0 / 1024.0, targetMb);
        return new Result(format, source.length, timings[0], timings[RUNS / 2], timings[RUNS - 1],
                guard[RUNS / 2]);
    }

    /**
     * Bulk comes from incompressible random image bytes rather than from
     * text. A 25 MB .docx of repeated words would be a 25 MB file that
     * inflates to very little and gives a flattering number; real documents
     * that big are big because of their pictures, and those are what the
     * guard pass has to walk through.
     */
    private static byte[] bigDocx() {
        return OfficeFixtures.docx(document -> {
            Random random = new Random(42);
            for (int i = 0; i < 18; i++) {
                XWPFParagraph paragraph = document.createParagraph();
                paragraph.createRun().setText("Section " + i + " body text");
                try {
                    document.addPictureData(OfficeFixtures.noisyPng(random, 700),
                            org.apache.poi.xwpf.usermodel.Document.PICTURE_TYPE_PNG);
                } catch (org.apache.poi.openxml4j.exceptions.InvalidFormatException e) {
                    throw new IllegalStateException(e);
                }
            }
        });
    }

    private static byte[] bigPptx() {
        return OfficeFixtures.pptx(show -> {
            Random random = new Random(42);
            for (int i = 0; i < 15; i++) {
                XSLFSlide slide = show.createSlide();
                XSLFTextBox box = slide.createTextBox();
                box.setAnchor(new Rectangle2D.Double(40, 40, 400, 60));
                box.setText("Slide " + (i + 1));
                show.addPicture(OfficeFixtures.noisyPng(random, 600),
                        org.apache.poi.sl.usermodel.PictureData.PictureType.PNG);
            }
        });
    }

    /**
     * Excel's bulk is cells, not pictures - a 10 MB workbook is a few
     * hundred thousand of them, and that is what POI has to hold in memory.
     */
    private static byte[] bigXlsx() {
        return OfficeFixtures.xlsx(workbook -> {
            for (int sheet = 0; sheet < 6; sheet++) {
                var worksheet = workbook.createSheet("Data" + sheet);
                for (int rowIndex = 0; rowIndex < 36000; rowIndex++) {
                    Row row = worksheet.createRow(rowIndex);
                    for (int column = 0; column < 12; column++) {
                        row.createCell(column).setCellValue("r" + rowIndex + "c" + column + "-padding");
                    }
                }
            }
        });
    }

    private record Result(String format, int sizeBytes, long bestMs, long medianMs, long worstMs,
                          long guardMedianMs) {
    }
}
