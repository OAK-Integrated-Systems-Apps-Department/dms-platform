package com.oak.dms.document;

import com.oak.dms.document.dto.DownloadedDocument;
import com.oak.dms.watermark.controller.WatermarkController;
import com.oak.dms.watermark.service.WatermarkJobService;
import com.oak.dms.workflow.controller.WorkflowInstanceController;
import com.oak.dms.workflow.service.WorkflowSupportingDocumentService;
import org.apache.catalina.Context;
import org.apache.catalina.Wrapper;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.startup.Tomcat;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotatedBeanDefinitionReader;
import org.springframework.web.context.support.GenericWebApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DOC-15 on the wire. The defect was invisible to MockMvc and to direct controller calls: Tomcat itself silently drops a
 * {@code Content-Disposition} header that holds a character above U+00FF. So this test serves the <b>real controllers</b>
 * ({@code DocumentController}, {@code WorkflowInstanceController}, {@code WatermarkController}) from a real embedded
 * Tomcat (the version the application pins) through Spring MVC, with their services mocked, and reads the raw response bytes
 * from a socket. It checks the header is present once, ASCII, correct for the disposition decided by
 * {@link ContentServingPolicy} (DOC-04), and that the other DOC-04 headers and the streamed body are untouched.
 * <p>
 * Authorization ({@code @PreAuthorize}) and the services behind the controllers are out of scope here - they are covered
 * by the existing integration tests; no method security is configured in this mini context.
 */
class DownloadHeadersWireTest {

    private static final String SINHALA = "ගොනුව";
    private static final String TAMIL = "கோப்பு";

    /** What the mocked services serve for the request under test. */
    private static final AtomicReference<Served> SERVED = new AtomicReference<>();

    record Served(String name, String contentType, byte[] body) {
        DownloadedDocument document() {
            return new DownloadedDocument(new ByteArrayInputStream(body), body.length, contentType, name);
        }
    }

    record Raw(int status, List<String[]> headers, byte[] headerBytes, byte[] body) {
        List<String> all(String name) {
            List<String> values = new ArrayList<>();
            for (String[] h : headers) {
                if (h[0].equalsIgnoreCase(name)) {
                    values.add(h[1]);
                }
            }
            return values;
        }

        String one(String name) {
            List<String> v = all(name);
            return v.isEmpty() ? null : v.get(0);
        }
    }

    private static Tomcat tomcat;
    private static int port;

    @BeforeAll
    static void start() throws Exception {
        DocumentService documentService = mock(DocumentService.class);
        DocumentVersionService versionService = mock(DocumentVersionService.class);
        DocumentRenditionService renditionService = mock(DocumentRenditionService.class);
        WorkflowSupportingDocumentService supporting = mock(WorkflowSupportingDocumentService.class);
        WatermarkJobService jobService = mock(WatermarkJobService.class);

        when(documentService.downloadDocument(any())).thenAnswer(c -> SERVED.get().document());
        when(documentService.previewDocument(any())).thenAnswer(c -> SERVED.get().document());
        when(versionService.download(any(), any())).thenAnswer(c -> SERVED.get().document());
        when(versionService.preview(any(), any())).thenAnswer(c -> SERVED.get().document());
        when(renditionService.preview(any(UUID.class))).thenAnswer(c -> SERVED.get().document());
        when(renditionService.preview(any(UUID.class), any(UUID.class))).thenAnswer(c -> SERVED.get().document());
        when(supporting.content(any(), any())).thenAnswer(c -> SERVED.get().document());
        when(jobService.download(any())).thenAnswer(c -> {
            Served s = SERVED.get();
            return new WatermarkJobService.DownloadedWatermark(new ByteArrayInputStream(s.body()), s.body().length,
                    s.contentType(), s.name());
        });

        Map<Class<?>, Object> mocks = new LinkedHashMap<>();
        for (Object m : new Object[]{documentService, versionService, renditionService, supporting, jobService}) {
            for (Class<?> type : allTypes(m.getClass())) {
                mocks.putIfAbsent(type, m);
            }
        }

        // Every mock is created HERE, on the test thread, before Tomcat starts: a mock first created on a Tomcat thread would be
        // generated in the web-app class loader and cached by Mockito, and later test classes mocking the same types would then
        // fail with ClassNotFoundException (found by running the whole unit suite in one JVM).
        DocumentController documentController = build(DocumentController.class, mocks);
        WorkflowInstanceController workflowController = build(WorkflowInstanceController.class, mocks);
        WatermarkController watermarkController = build(WatermarkController.class, mocks);

        GenericWebApplicationContext web = new GenericWebApplicationContext();
        new AnnotatedBeanDefinitionReader(web).register(WebConfig.class);
        web.registerBean(DocumentController.class, () -> documentController);
        web.registerBean(WorkflowInstanceController.class, () -> workflowController);
        web.registerBean(WatermarkController.class, () -> watermarkController);

        Path base = Files.createTempDirectory("doc15wire");
        tomcat = new Tomcat();
        tomcat.setBaseDir(base.toString());
        Connector connector = new Connector();
        connector.setPort(0);
        tomcat.setConnector(connector);
        Context ctx = tomcat.addContext("", base.toString());
        Wrapper wrapper = Tomcat.addServlet(ctx, "dispatcher", new DispatcherServlet(web));
        wrapper.setAsyncSupported(true);          // StreamingResponseBody needs it, as in the application
        wrapper.setLoadOnStartup(1);
        ctx.addServletMappingDecoded("/", "dispatcher");
        tomcat.start();
        port = tomcat.getConnector().getLocalPort();
    }

    @AfterAll
    static void stop() throws Exception {
        if (tomcat != null) {
            tomcat.stop();
            tomcat.destroy();
        }
    }

    private static List<Class<?>> allTypes(Class<?> mockClass) {
        List<Class<?>> types = new ArrayList<>();
        for (Class<?> c = mockClass; c != null && c != Object.class; c = c.getSuperclass()) {
            types.add(c);
            types.addAll(List.of(c.getInterfaces()));
        }
        return types;
    }

    /** A controller built by its single constructor, with a mock for every dependency (the given ones, where they fit). */
    private static <T> T build(Class<T> type, Map<Class<?>, Object> real) {
        try {
            Constructor<?> ctor = type.getDeclaredConstructors()[0];
            ctor.setAccessible(true);
            Object[] args = new Object[ctor.getParameterCount()];
            Class<?>[] types = ctor.getParameterTypes();
            for (int i = 0; i < args.length; i++) {
                args[i] = real.containsKey(types[i]) ? real.get(types[i]) : mock(types[i]);
            }
            return type.cast(ctor.newInstance(args));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @org.springframework.context.annotation.Configuration
    @org.springframework.web.servlet.config.annotation.EnableWebMvc
    static class WebConfig {
    }

    // ------------------------------------------------------------------ HTTP

    private static Raw get(String path) throws Exception {
        try (Socket s = new Socket("127.0.0.1", port)) {
            s.setSoTimeout(15000);
            s.getOutputStream().write(("GET " + path + " HTTP/1.1\r\nHost: x\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            s.getOutputStream().flush();
            InputStream in = s.getInputStream();
            ByteArrayOutputStream all = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                all.write(buf, 0, n);
            }
            byte[] bytes = all.toByteArray();
            int split = indexOf(bytes, "\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            assertThat(split).as("a complete response head").isPositive();
            byte[] head = java.util.Arrays.copyOfRange(bytes, 0, split);
            String[] lines = new String(head, StandardCharsets.ISO_8859_1).split("\r\n");
            int status = Integer.parseInt(lines[0].split(" ")[1]);
            List<String[]> headers = new ArrayList<>();
            for (int i = 1; i < lines.length; i++) {
                int colon = lines[i].indexOf(':');
                headers.add(new String[]{lines[i].substring(0, colon), lines[i].substring(colon + 1).trim()});
            }
            byte[] body = java.util.Arrays.copyOfRange(bytes, split + 4, bytes.length);
            return new Raw(status, headers, head, body);
        }
    }

    private static int indexOf(byte[] data, byte[] pattern) {
        outer:
        for (int i = 0; i + pattern.length <= data.length; i++) {
            for (int j = 0; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static String decodeStar(String header) {
        int i = header.indexOf("filename*=UTF-8''");
        if (i < 0) {
            return null;
        }
        String s = header.substring(i + "filename*=UTF-8''".length());
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int k = 0; k < s.length(); k++) {
            if (s.charAt(k) == '%') {
                out.write(Integer.parseInt(s.substring(k + 1, k + 3), 16));
                k += 2;
            } else {
                out.write(s.charAt(k));
            }
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ what is served

    /** Every route that serves a file, with the disposition the controller itself decides on. */
    private record Route(String label, String path, boolean decidedByType, String fixedDisposition) {
    }

    private static List<Route> routes() {
        UUID id = UUID.randomUUID();
        UUID version = UUID.randomUUID();
        return List.of(
                new Route("DocumentController.downloadDocument (inline=false)", "/api/v1/documents/" + id + "/content", true, "attachment"),
                new Route("DocumentController.downloadDocument (inline=true)", "/api/v1/documents/" + id + "/content?inline=true", true, null),
                new Route("DocumentController.downloadVersion (inline=false)", "/api/v1/documents/" + id + "/versions/" + version + "/content", true, "attachment"),
                new Route("DocumentController.downloadVersion (inline=true)", "/api/v1/documents/" + id + "/versions/" + version + "/content?inline=true", true, null),
                new Route("DocumentController.previewPdf", "/api/v1/documents/" + id + "/preview.pdf", false, "inline"),
                new Route("DocumentController.previewVersionPdf", "/api/v1/documents/" + id + "/versions/" + version + "/preview.pdf", false, "inline"),
                new Route("WorkflowInstanceController.supportingDocumentContent", "/api/v1/workflow/instances/" + id + "/supporting-documents/" + version + "/content", true, null),
                new Route("WatermarkController.result", "/api/v1/watermark-jobs/" + id + "/result", false, "attachment"));
    }

    private static final String[] NAMES = {
            "report.pdf", SINHALA + ".pdf", TAMIL + ".pdf", "📄report.pdf", "café.pdf", "Report 100%.pdf",
            "50%20off.pdf", "a;b.pdf", "a\"b.pdf", "a\\b.pdf", "../report.pdf", "invoice‮txt.pdf",
            "x\r\nSet-Cookie: a=b\r\n\r\n.pdf", "‮​", SINHALA.repeat(60) + ".pdf"};

    @Test
    void everyFileRouteSendsOneAsciiContentDispositionForEveryName() throws Exception {
        byte[] body = "%PDF-1.4 test body".getBytes(StandardCharsets.US_ASCII);
        for (Route route : routes()) {
            for (String name : NAMES) {
                SERVED.set(new Served(name, "application/pdf", body));

                Raw raw = get(route.path());

                String where = route.label() + " name=" + name.replace("\r", "<CR>").replace("\n", "<LF>");
                assertThat(raw.status()).as("status: " + where).isEqualTo(200);
                assertThat(raw.all("Content-Disposition")).as("Content-Disposition present exactly once: " + where).hasSize(1);
                String header = raw.one("Content-Disposition");
                for (char c : header.toCharArray()) {
                    assertThat((int) c).as("ASCII: " + where).isBetween(0x20, 0x7e);
                }
                String expectedType = route.decidedByType()
                        ? ContentServingPolicy.dispositionType(route.path().contains("inline=true") || route.fixedDisposition() == null,
                        "application/pdf")
                        : route.fixedDisposition();
                assertThat(header).as(where).startsWith(expectedType + "; filename=\"");
                assertThat(raw.body()).as("body streamed unchanged: " + where).isEqualTo(body);
                // the sanitised real name is what a client reading filename* gets (or the plain name when it is plain ASCII)
                String carried = decodeStar(header) != null ? decodeStar(header) : header.replaceAll("^.*filename=\"([^\"]*)\".*$", "$1");
                assertThat(carried).as("name carried: " + where).isEqualTo(ContentDispositionHeader.normalise(name));
                // no header line other than the real ones: nothing a name could inject
                assertThat(raw.all("Set-Cookie")).as("no injected header: " + where).isEmpty();
                assertThat(raw.all("X-Evil")).isEmpty();
            }
        }
    }

    @Test
    void sinhalaAndTamilNamesNoLongerLoseTheirHeaderTheDefectOfDoc15() throws Exception {
        for (String name : new String[]{SINHALA + ".pdf", TAMIL + ".pdf", "📄.pdf"}) {
            SERVED.set(new Served(name, "application/octet-stream", "x".getBytes(StandardCharsets.US_ASCII)));

            Raw raw = get("/api/v1/documents/" + UUID.randomUUID() + "/content");

            assertThat(raw.one("Content-Disposition")).as("header must not be dropped for " + name)
                    .startsWith("attachment; filename=\"document.pdf\"; filename*=UTF-8''%");
            assertThat(decodeStar(raw.one("Content-Disposition"))).isEqualTo(name);
        }
    }

    // ------------------------------------------------------------------ DOC-04 preserved

    @Test
    void theInlineDecisionStaysWithTheContentTypePolicyWhateverTheName() throws Exception {
        Map<String, Boolean> typeToInline = new LinkedHashMap<>();       // content type -> may be shown inline
        typeToInline.put("application/xml", false);
        typeToInline.put("application/json", false);
        typeToInline.put("image/svg+xml", false);
        typeToInline.put("text/plain", false);
        typeToInline.put("text/html", false);
        typeToInline.put("application/pdf", true);
        typeToInline.put("image/png", true);
        typeToInline.put("image/jpeg", true);
        byte[] body = "<?xml version=\"1.0\"?><a/>".getBytes(StandardCharsets.UTF_8);
        String[] hostile = {SINHALA + ".xml", TAMIL + ".json", "📄.svg", "a\"b;c 100%.txt", "..\\..\\x.html",
                "x\r\nContent-Type: text/html\r\n\r\n.pdf", "invoice‮gpj.exe.png"};
        UUID id = UUID.randomUUID();
        UUID version = UUID.randomUUID();
        String[] paths = {"/api/v1/documents/" + id + "/content?inline=true",
                "/api/v1/documents/" + id + "/versions/" + version + "/content?inline=true",
                "/api/v1/workflow/instances/" + id + "/supporting-documents/" + version + "/content"};

        for (Map.Entry<String, Boolean> type : typeToInline.entrySet()) {
            for (String name : hostile) {
                for (String path : paths) {
                    SERVED.set(new Served(name, type.getKey(), body));

                    Raw raw = get(path);

                    String where = type.getKey() + " " + path + " " + name.replace("\r", "<CR>").replace("\n", "<LF>");
                    assertThat(raw.status()).as(where).isEqualTo(200);
                    assertThat(raw.all("Content-Disposition")).as(where).hasSize(1);
                    assertThat(raw.one("Content-Disposition")).as(where)
                            .startsWith((type.getValue() ? "inline" : "attachment") + "; filename=\"");
                    assertThat(raw.one("Content-Type")).as(where).startsWith(type.getKey());
                    assertThat(raw.one("X-Content-Type-Options")).as(where).isEqualTo("nosniff");
                    if (type.getKey().equals("application/pdf")) {
                        assertThat(raw.all("Content-Security-Policy")).as(where).isEmpty();
                    } else {
                        assertThat(raw.one("Content-Security-Policy")).as(where).isEqualTo(ContentServingPolicy.SANDBOX_CSP);
                    }
                    assertThat(raw.all("Content-Type")).as("no second Content-Type from a hostile name: " + where).hasSize(1);
                    assertThat(raw.body()).as(where).isEqualTo(body);
                }
            }
        }
    }
}
