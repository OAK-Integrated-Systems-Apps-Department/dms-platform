package com.oak.dms.watermark;

import com.oak.dms.watermark.config.WatermarkProperties;
import com.oak.dms.watermark.exception.WatermarkException;
import com.oak.dms.watermark.format.DocxWatermarkService;
import com.oak.dms.watermark.format.FontProvider;
import com.oak.dms.watermark.format.ImageWatermarkService;
import com.oak.dms.watermark.format.PdfWatermarkService;
import com.oak.dms.watermark.format.PptxWatermarkService;
import com.oak.dms.watermark.format.WatermarkFormatService;
import com.oak.dms.watermark.format.WatermarkRenderer;
import com.oak.dms.watermark.format.WatermarkServiceFactory;
import com.oak.dms.watermark.format.XlsxWatermarkService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What the factory tells a user, now that all six formats exist.
 * <p>
 * This is the test that would have caught the drift the factory was built to
 * prevent: a hard-coded sentence promising Word and Excel before anything
 * could produce them. The list is derived from the registered services, so
 * this asserts the two halves agree.
 */
class WatermarkServiceFactoryTest {

    private WatermarkServiceFactory factory;

    @BeforeEach
    void setUp() {
        WatermarkProperties properties = new WatermarkProperties();
        WatermarkRenderer renderer = new WatermarkRenderer(new FontProvider(properties), properties);
        List<WatermarkFormatService> services = List.of(
                new PdfWatermarkService(renderer, properties),
                new DocxWatermarkService(renderer, properties),
                new XlsxWatermarkService(renderer, properties),
                new PptxWatermarkService(renderer, properties),
                new ImageWatermarkService(renderer, properties));
        factory = new WatermarkServiceFactory(services);
    }

    @Test
    void allSixPhaseOneFormatsAreSupported() {
        assertThat(factory.isSupported("application/pdf")).isTrue();
        assertThat(factory.isSupported(OfficeFixtures.DOCX_MIME)).isTrue();
        assertThat(factory.isSupported(OfficeFixtures.XLSX_MIME)).isTrue();
        assertThat(factory.isSupported(OfficeFixtures.PPTX_MIME)).isTrue();
        assertThat(factory.isSupported("image/jpeg")).isTrue();
        assertThat(factory.isSupported("image/png")).isTrue();
    }

    @Test
    void eachTypeResolvesToItsOwnService() {
        assertThat(factory.forMimeType("application/pdf")).isInstanceOf(PdfWatermarkService.class);
        assertThat(factory.forMimeType(OfficeFixtures.DOCX_MIME)).isInstanceOf(DocxWatermarkService.class);
        assertThat(factory.forMimeType(OfficeFixtures.XLSX_MIME)).isInstanceOf(XlsxWatermarkService.class);
        assertThat(factory.forMimeType(OfficeFixtures.PPTX_MIME)).isInstanceOf(PptxWatermarkService.class);
        assertThat(factory.forMimeType("image/png")).isInstanceOf(ImageWatermarkService.class);
    }

    @Test
    void theRefusalMessageNowNamesAllSixInAStableOrder() {
        assertThat(factory.supportedFormats())
                .isEqualTo("PDF, Word (.docx), Excel (.xlsx), PowerPoint (.pptx), JPEG, PNG");
    }

    /**
     * Legacy Office formats stay a 415. They are a different file format
     * entirely - OLE2 compound documents, not packages - and Phase 1 does
     * not convert.
     */
    @Test
    void legacyOfficeFormatsAreStillRefusedAsUnsupported() {
        for (String legacy : List.of("application/msword", "application/vnd.ms-excel",
                "application/vnd.ms-powerpoint", "text/plain", "application/zip")) {
            assertThat(factory.isSupported(legacy)).describedAs(legacy).isFalse();
            assertThatThrownBy(() -> factory.forMimeType(legacy))
                    .describedAs(legacy)
                    .isInstanceOf(WatermarkException.class)
                    .hasFieldOrPropertyWithValue("status", HttpStatus.UNSUPPORTED_MEDIA_TYPE)
                    .hasMessageContaining("PDF, Word (.docx), Excel (.xlsx), PowerPoint (.pptx), JPEG, PNG");
        }
    }

    /**
     * The guarantee the factory exists for: a format is advertised exactly
     * when something can produce it. With only the PDF service registered,
     * the message must not mention Word.
     */
    @Test
    void aFormatWithNoServiceIsNotAdvertised() {
        WatermarkProperties properties = new WatermarkProperties();
        WatermarkRenderer renderer = new WatermarkRenderer(new FontProvider(properties), properties);
        WatermarkServiceFactory pdfOnly = new WatermarkServiceFactory(
                List.of(new PdfWatermarkService(renderer, properties)));

        assertThat(pdfOnly.supportedFormats()).isEqualTo("PDF");
        assertThat(pdfOnly.isSupported(OfficeFixtures.DOCX_MIME)).isFalse();
    }
}
