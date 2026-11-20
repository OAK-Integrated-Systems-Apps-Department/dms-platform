package com.oak.dms.watermark;

import com.oak.dms.security.rbac.catalog.PermissionCodes;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * DOC-04, end to end over HTTP on the two document content routes: the real controller, access checks, storage and
 * encryption. An XML document (which a browser opened from its address bar would render by its namespace) is never
 * sent inline and always carries the sandbox CSP; PDFs and raster images keep their inline behaviour; the bytes,
 * Content-Length, filename and {@code nosniff} are exactly what they were. The workflow supporting-document route is
 * covered in {@code SupportingDocumentContentHeadersIT}.
 */
@SpringBootTest
class ContentServingPolicyIT extends WatermarkFixtures {

    private static final String SANDBOX = "sandbox; default-src 'none'";

    @Autowired private WebApplicationContext context;

    private MockMvc mvc;
    private Fixture fixture;

    private Fixture documentOf(String filename, String mime, byte[] content) {
        Fixture f = fixtureWithContent(filename, mime, content);
        grant(f, PermissionCodes.DOCM_DOCUMENTS_VIEW);
        grant(f, PermissionCodes.DVC_VERSIONS_VIEW);
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        fixture = f;
        return f;
    }

    private MockHttpServletResponse fetch(String path) throws Exception {
        UUID keycloakId = userRepository.findById(fixture.userId()).orElseThrow().getKeycloakId();
        MockHttpServletRequestBuilder request = get(path).with(jwt()
                .jwt(b -> b.subject(keycloakId.toString()).claim("tenant_id", fixture.tenantId().toString()))
                .authorities());
        MvcResult started = mvc.perform(request).andExpect(request().asyncStarted()).andReturn();
        return mvc.perform(asyncDispatch(started)).andExpect(status().isOk()).andReturn().getResponse();
    }

    private String current(boolean inline) {
        return "/api/v1/documents/" + fixture.documentId() + "/content" + (inline ? "?inline=true" : "");
    }

    private String version(boolean inline) {
        return "/api/v1/documents/" + fixture.documentId() + "/versions/" + fixture.versionId() + "/content"
                + (inline ? "?inline=true" : "");
    }

    private static byte[] xhtmlAsXml() {
        // A harmless stand-in for the case that matters: an XHTML-namespaced document stored as .xml.
        return ("<?xml version=\"1.0\"?><html xmlns=\"http://www.w3.org/1999/xhtml\"><body>"
                + "<p>test</p></body></html>").getBytes(StandardCharsets.UTF_8);
    }

    private void assertUnchanged(MockHttpServletResponse response, byte[] original, String filename) {
        assertThat(response.getContentAsByteArray()).isEqualTo(original);
        assertThat(response.getHeader("Content-Length")).isEqualTo(String.valueOf(original.length));
        assertThat(response.getHeader("Content-Disposition")).endsWith("; filename=\"" + filename + "\"");
        assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
    }

    @Test
    void anXmlDocument_requestedInline_isSentAsAnAttachment_withTheSandboxCsp_onBothRoutes() throws Exception {
        byte[] xml = xhtmlAsXml();
        documentOf("report.xml", "application/xml", xml);

        for (String path : new String[]{current(true), version(true)}) {
            MockHttpServletResponse response = fetch(path);
            assertThat(response.getHeader("Content-Type")).as(path).startsWith("application/xml");
            assertThat(response.getHeader("Content-Disposition")).as(path).startsWith("attachment;");
            assertThat(response.getHeader("Content-Security-Policy")).as(path).isEqualTo(SANDBOX);
            assertUnchanged(response, xml, "report.xml");
        }
    }

    @Test
    void anXmlDocument_notRequestedInline_staysAnAttachment_andIsSandboxedToo() throws Exception {
        byte[] xml = xhtmlAsXml();
        documentOf("report.xml", "application/xml", xml);

        for (String path : new String[]{current(false), version(false)}) {
            MockHttpServletResponse response = fetch(path);
            assertThat(response.getHeader("Content-Disposition")).as(path).startsWith("attachment;");
            assertThat(response.getHeader("Content-Security-Policy")).as(path).isEqualTo(SANDBOX);
            assertUnchanged(response, xml, "report.xml");
        }
    }

    @Test
    void jsonAndPlainText_requestedInline_areAttachments_withTheSandbox() throws Exception {
        byte[] json = "{\"a\": 1}".getBytes(StandardCharsets.UTF_8);
        documentOf("data.json", "application/json", json);
        MockHttpServletResponse jsonResponse = fetch(current(true));
        assertThat(jsonResponse.getHeader("Content-Disposition")).startsWith("attachment;");
        assertThat(jsonResponse.getHeader("Content-Security-Policy")).isEqualTo(SANDBOX);
        assertUnchanged(jsonResponse, json, "data.json");

        byte[] text = "plain notes".getBytes(StandardCharsets.UTF_8);
        documentOf("notes.txt", "text/plain", text);
        MockHttpServletResponse textResponse = fetch(version(true));
        assertThat(textResponse.getHeader("Content-Disposition")).startsWith("attachment;");
        assertThat(textResponse.getHeader("Content-Security-Policy")).isEqualTo(SANDBOX);
        assertUnchanged(textResponse, text, "notes.txt");
    }

    @Test
    void aPdf_requestedInline_staysInline_withoutTheSandbox_onBothRoutes() throws Exception {
        byte[] pdf = minimalPdf();
        documentOf("memo.pdf", PDF_MIME, pdf);

        for (String path : new String[]{current(true), version(true)}) {
            MockHttpServletResponse response = fetch(path);
            assertThat(response.getHeader("Content-Disposition")).as(path).startsWith("inline;");
            assertThat(response.getHeader("Content-Security-Policy")).as(path).isNull();
            assertUnchanged(response, pdf, "memo.pdf");
        }
    }

    @Test
    void aPdf_notRequestedInline_isStillAnAttachment_asBefore() throws Exception {
        byte[] pdf = minimalPdf();
        documentOf("memo.pdf", PDF_MIME, pdf);
        MockHttpServletResponse response = fetch(current(false));
        assertThat(response.getHeader("Content-Disposition")).startsWith("attachment;");
        assertThat(response.getHeader("Content-Security-Policy")).isNull();
        assertUnchanged(response, pdf, "memo.pdf");
    }

    @Test
    void aPngImage_requestedInline_staysInline() throws Exception {
        byte[] png = new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 0};
        documentOf("scan.png", "image/png", png);

        MockHttpServletResponse response = fetch(current(true));
        assertThat(response.getHeader("Content-Type")).startsWith("image/png");
        assertThat(response.getHeader("Content-Disposition")).startsWith("inline;");
        assertUnchanged(response, png, "scan.png");
    }

    @Test
    void anSvgType_isNeverInline_evenIfOneWereStored() throws Exception {
        // .svg cannot be uploaded (the extension allow-list refuses it), but if a version ever carried the type the
        // policy still sends it as an attachment - exercised here with the stored type set directly.
        byte[] svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"/>".getBytes(StandardCharsets.UTF_8);
        documentOf("drawing.xml", "application/xml", svg);
        jdbc.update("update document_versions set mime_type = 'image/svg+xml' where version_id = ?",
                fixture.versionId());

        MockHttpServletResponse response = fetch(current(true));
        assertThat(response.getHeader("Content-Type")).startsWith("image/svg+xml");
        assertThat(response.getHeader("Content-Disposition")).startsWith("attachment;");
        assertThat(response.getHeader("Content-Security-Policy")).isEqualTo(SANDBOX);
    }
}
