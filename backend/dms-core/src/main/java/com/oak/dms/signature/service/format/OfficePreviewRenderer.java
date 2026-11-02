package com.oak.dms.signature.service.format;

import com.oak.dms.signature.exception.SignatureException;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;

/**
 * Converts office bytes to a PDF purely to RENDER a placement preview - the
 * actual stamp always goes onto the original format's own bytes natively
 * (see {@code DocxDocumentStamper}/{@code XlsxDocumentStamper}), never onto
 * this converted copy. Deliberately separate from {@code DocumentRenditionService}:
 * that class caches a document's "current version" preview across requests
 * and carries its own audit/storage semantics; this converts whatever
 * arbitrary baseline bytes the signing flow is holding in memory for exactly
 * one call, with no cache and no storage, since reusing that caching layer
 * for a one-shot placement render would mean either polluting a document's
 * real preview cache with placement-time renders, or threading version
 * identity through a flow that otherwise never needs it.
 * <p>
 * Shares the same {@code dms.preview.converter-url} Gotenberg instance;
 * unavailability here fails closed with SIGNATURE_PLACEMENT_PREVIEW_UNAVAILABLE
 * rather than blocking the stamp itself, which needs no converter at all.
 */
@Slf4j
@Component
public class OfficePreviewRenderer {

    private final RestClient converter;

    public OfficePreviewRenderer(RestClient.Builder restClients,
                                 @Value("${dms.preview.converter-url:}") String converterUrl,
                                 @Value("${dms.preview.timeout:PT90S}") Duration timeout) {
        if (converterUrl == null || converterUrl.isBlank()) {
            this.converter = null;
        } else {
            org.springframework.http.client.SimpleClientHttpRequestFactory requests =
                    new org.springframework.http.client.SimpleClientHttpRequestFactory();
            requests.setConnectTimeout(Duration.ofSeconds(5));
            requests.setReadTimeout(timeout);
            this.converter = restClients.clone().baseUrl(converterUrl).requestFactory(requests).build();
        }
    }

    /** {@code source} (named {@code extension} for Gotenberg's own format sniffing) converted to a PDF. */
    public byte[] toPdf(byte[] source, String extension) {
        if (converter == null) {
            throw SignatureException.unavailable("SIGNATURE_PLACEMENT_PREVIEW_UNAVAILABLE",
                    "Previews aren't set up on this server for this file type.");
        }
        try {
            MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
            form.add("files", new ByteArrayResource(source) {
                @Override
                public String getFilename() {
                    return "document." + extension;
                }
            });
            byte[] pdf = converter.post().uri("/forms/libreoffice/convert").contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(form).retrieve().body(byte[].class);
            if (pdf == null || pdf.length < 5 || pdf[0] != '%' || pdf[1] != 'P' || pdf[2] != 'D' || pdf[3] != 'F') {
                throw SignatureException.unavailable("SIGNATURE_PLACEMENT_PREVIEW_UNAVAILABLE",
                        "Could not prepare a preview of this document just now.");
            }
            return pdf;
        } catch (ResourceAccessException e) {
            log.warn("Preview converter unavailable for signature placement: {}", e.getMessage());
            throw SignatureException.unavailable("SIGNATURE_PLACEMENT_PREVIEW_UNAVAILABLE",
                    "Previews are unavailable just now - try again shortly.");
        } catch (RestClientException e) {
            log.warn("Could not convert a document for signature placement preview: {}", e.getMessage());
            throw SignatureException.unavailable("SIGNATURE_PLACEMENT_PREVIEW_UNAVAILABLE",
                    "Could not prepare a preview of this document just now.");
        }
    }

    public int pageCount(byte[] pdf) {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            return document.getNumberOfPages();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException("Could not read a converted preview PDF", e);
        }
    }

    public byte[] renderPage(byte[] pdf, int page, int width) {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            if (page < 0 || page >= document.getNumberOfPages()) {
                throw SignatureException.badRequest("SIGNATURE_PLACEMENT_PAGE_OUT_OF_RANGE",
                        "That page doesn't exist in this document.");
            }
            PDPage pdPage = document.getPage(page);
            PDRectangle box = pdPage.getCropBox() != null ? pdPage.getCropBox() : pdPage.getMediaBox();
            float scale = Math.max(1, width) / box.getWidth();
            BufferedImage image = new PDFRenderer(document).renderImage(page, scale, ImageType.RGB);
            return jpeg(image);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException("Could not render a converted preview page", e);
        }
    }

    private static byte[] jpeg(BufferedImage image) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ImageOutputStream stream = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(stream);
            ImageWriteParam params = writer.getDefaultWriteParam();
            params.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            params.setCompressionQuality(0.9f);
            writer.write(null, new IIOImage(image, null, null), params);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }
}
