package com.oak.dms.document;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/** DOC-04: which stored types may be sent inline, and which responses get the sandbox CSP. */
class ContentServingPolicyTest {

    @ParameterizedTest
    @ValueSource(strings = {"application/pdf", "image/png", "image/jpeg", "image/gif", "image/webp", "image/bmp"})
    void pdfAndPassiveRasterImages_stayInline_whenAskedFor(String type) {
        assertThat(ContentServingPolicy.dispositionType(true, type)).isEqualTo("inline");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "application/xml", "text/xml", "application/json", "text/plain", "text/csv", "text/markdown",
            "text/html", "application/xhtml+xml", "image/svg+xml", "application/rtf", "message/rfc822",
            "application/zip", "application/octet-stream", "image/tiff", "image/heic", "image/vnd.dwg",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document"})
    void everythingElse_isAnAttachment_evenWhenInlineIsAskedFor(String type) {
        assertThat(ContentServingPolicy.dispositionType(true, type)).isEqualTo("attachment");
    }

    @ParameterizedTest
    @ValueSource(strings = {"application/pdf", "image/png", "application/xml", "image/svg+xml", "text/html"})
    void withoutInline_itIsAlwaysAnAttachment_asBefore(String type) {
        assertThat(ContentServingPolicy.dispositionType(false, type)).isEqualTo("attachment");
    }

    @Test
    void svgIsNotAnAllowedImage_whateverItsCaseOrParameters() {
        assertThat(ContentServingPolicy.dispositionType(true, "IMAGE/SVG+XML")).isEqualTo("attachment");
        assertThat(ContentServingPolicy.dispositionType(true, "image/svg+xml; charset=utf-8")).isEqualTo("attachment");
        assertThat(ContentServingPolicy.dispositionType(true, "image/svg")).isEqualTo("attachment");
    }

    @Test
    void typeMatching_ignoresCaseAndParameters_andTreatsAMissingTypeAsUnsafe() {
        assertThat(ContentServingPolicy.dispositionType(true, "Application/PDF")).isEqualTo("inline");
        assertThat(ContentServingPolicy.dispositionType(true, " image/png ; foo=bar")).isEqualTo("inline");
        assertThat(ContentServingPolicy.dispositionType(true, null)).isEqualTo("attachment");
        assertThat(ContentServingPolicy.dispositionType(true, "")).isEqualTo("attachment");
        assertThat(ContentServingPolicy.needsSandbox(null)).isTrue();
    }

    @Test
    void theSandboxCsp_goesOnEveryTypeExceptPdf() {
        assertThat(ContentServingPolicy.needsSandbox("application/pdf")).isFalse();
        assertThat(ContentServingPolicy.needsSandbox("application/pdf; foo=bar")).isFalse();
        for (String type : new String[]{"application/xml", "application/json", "text/plain", "image/png",
                "image/svg+xml", "application/zip", "application/octet-stream"}) {
            assertThat(ContentServingPolicy.needsSandbox(type)).as(type).isTrue();
        }
    }

    @Test
    void withSandbox_addsExactlyTheApprovedPolicy_andLeavesOtherHeadersAlone() {
        ResponseEntity<Void> xml = ContentServingPolicy.withSandbox(ResponseEntity.ok(), "application/xml")
                .header("X-Content-Type-Options", "nosniff").build();
        assertThat(xml.getHeaders().getFirst("Content-Security-Policy")).isEqualTo("sandbox; default-src 'none'");
        assertThat(xml.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");

        ResponseEntity<Void> pdf = ContentServingPolicy.withSandbox(ResponseEntity.ok(), "application/pdf").build();
        assertThat(pdf.getHeaders().getFirst("Content-Security-Policy")).isNull();
    }
}
