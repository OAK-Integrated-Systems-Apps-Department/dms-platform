package com.oak.dms.document;

import com.oak.dms.document.dto.DownloadedDocument;
import com.oak.dms.security.auth.AuthorizationService;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.workflow.controller.WorkflowInstanceController;
import com.oak.dms.workflow.service.WorkflowSupportingDocumentService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * DOC-04, the workflow supporting-document route. It has no {@code inline} parameter - it is always an in-page
 * request - and used to send every stored type inline. Now the same policy as the document routes applies: only PDF
 * and raster images are inline, everything else is an attachment, and everything but PDF carries the sandbox CSP.
 * The service is stubbed (building a whole workflow run is not the point): what is under test is the controller's
 * response for the document it is handed.
 */
@SpringBootTest
class SupportingDocumentContentHeadersIT {

    private static final String SANDBOX = "sandbox; default-src 'none'";

    @MockitoBean private WorkflowSupportingDocumentService supportingDocumentService;
    @MockitoBean private AuthorizationService authorizationService;
    @Autowired private WorkflowInstanceController controller;

    @BeforeEach
    void signedIn() {
        when(authorizationService.hasPermission(anyString())).thenReturn(true);
        TenantContext.set(UUID.randomUUID(), UUID.randomUUID());
    }

    @AfterEach
    void signedOut() {
        TenantContext.clear();
    }

    private ResponseEntity<StreamingResponseBody> serve(String contentType, String filename, byte[] bytes) {
        UUID instanceId = UUID.randomUUID();
        UUID supportingId = UUID.randomUUID();
        when(supportingDocumentService.content(instanceId, supportingId))
                .thenReturn(new DownloadedDocument(new ByteArrayInputStream(bytes), bytes.length, contentType, filename));
        return controller.supportingDocumentContent(instanceId, supportingId);
    }

    private static void assertBodyAndBasics(ResponseEntity<StreamingResponseBody> response, byte[] bytes,
                                            String filename) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        response.getBody().writeTo(out);
        assertThat(out.toByteArray()).isEqualTo(bytes);
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_LENGTH)).isEqualTo(String.valueOf(bytes.length));
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)).endsWith("; filename=\"" + filename + "\"");
        assertThat(response.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
    }

    @Test
    void anXmlSupportingDocument_isAnAttachment_withTheSandbox() throws Exception {
        byte[] xml = "<?xml version=\"1.0\"?><a/>".getBytes();
        ResponseEntity<StreamingResponseBody> response = serve("application/xml", "evidence.xml", xml);
        assertThat(response.getHeaders().getContentType().toString()).startsWith("application/xml");
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)).startsWith("attachment;");
        assertThat(response.getHeaders().getFirst("Content-Security-Policy")).isEqualTo(SANDBOX);
        assertBodyAndBasics(response, xml, "evidence.xml");
    }

    @Test
    void jsonTextAndUnknownTypes_areAttachments_withTheSandbox() throws Exception {
        for (String type : new String[]{"application/json", "text/plain", "application/octet-stream"}) {
            byte[] bytes = "x".getBytes();
            ResponseEntity<StreamingResponseBody> response = serve(type, "evidence.bin", bytes);
            assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)).as(type).startsWith("attachment;");
            assertThat(response.getHeaders().getFirst("Content-Security-Policy")).as(type).isEqualTo(SANDBOX);
        }
    }

    @Test
    void aPdfSupportingDocument_staysInline_withoutTheSandbox() throws Exception {
        byte[] pdf = "%PDF-1.4\n".getBytes();
        ResponseEntity<StreamingResponseBody> response = serve("application/pdf", "evidence.pdf", pdf);
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)).startsWith("inline;");
        assertThat(response.getHeaders().getFirst("Content-Security-Policy")).isNull();
        assertBodyAndBasics(response, pdf, "evidence.pdf");
    }

    @Test
    void aPngSupportingDocument_staysInline() throws Exception {
        byte[] png = new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a};
        ResponseEntity<StreamingResponseBody> response = serve("image/png", "scan.png", png);
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)).startsWith("inline;");
        assertBodyAndBasics(response, png, "scan.png");
    }

    @Test
    void aSvgType_isNeverInline_onThisRouteEither() throws Exception {
        ResponseEntity<StreamingResponseBody> response = serve("image/svg+xml", "drawing.svg", "<svg/>".getBytes());
        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION)).startsWith("attachment;");
        assertThat(response.getHeaders().getFirst("Content-Security-Policy")).isEqualTo(SANDBOX);
    }
}
