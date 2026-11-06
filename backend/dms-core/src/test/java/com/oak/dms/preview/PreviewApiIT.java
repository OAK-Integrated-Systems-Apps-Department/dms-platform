package com.oak.dms.preview;

import com.oak.dms.user.User;
import com.oak.dms.workflow.support.WorkflowApiSupport;
import com.oak.dms.workflow.support.WorkflowFixtures;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

import java.io.ByteArrayOutputStream;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * The preview endpoints over HTTP, as real users: who may read and change the organisation's preview policy, the
 * page and thumbnail endpoints, and the Download button of a preview following the policy.
 */
@SpringBootTest
@Import(WorkflowFixtures.class)
class PreviewApiIT extends WorkflowApiSupport {

    private static final String VIEW = "docm.documents.view";

    private UUID tenantId;
    private User viewer;
    private User admin;
    private UUID documentId;

    @BeforeEach
    void world() throws Exception {
        tenantId = fx.tenant();
        UUID type = fx.documentType(tenantId, "Contract");
        viewer = fx.user(tenantId, "Vera");
        admin = fx.user(tenantId, "Adam");
        fx.role(tenantId, "Readers", new String[]{VIEW}, viewer);
        documentId = fx.document(tenantId, type, viewer, "Master agreement");
        UUID versionId = fx.jdbc().queryForObject(
                "select current_version_id from documents where document_id = ?", UUID.class, documentId);
        fx.putVersionContent(versionId, pdf(2));
    }

    @Test
    void everyoneWhoCanOpenAPreview_canReadThePolicy_whichIsAllowEverythingUntilSomeoneChangesIt() throws Exception {
        JsonNode policy = get200("/api/v1/preview/policy", viewer);

        assertThat(policy.get("print").asString()).isEqualTo("allow");
        assertThat(policy.get("download").asString()).isEqualTo("allow");
        assertThat(policy.get("copy").asString()).isEqualTo("allow");
    }

    @Test
    void onlyAnAdministratorChangesThePolicy_andNonsenseIsRefused() throws Exception {
        Map<String, String> wanted = Map.of("print", "watermark", "download", "block", "copy", "block");

        call(put("/api/v1/preview/policy"), fx.jwtFor(viewer), wanted, 403);
        assertThat(get200("/api/v1/preview/policy", viewer).get("download").asString()).isEqualTo("allow");

        adminPut("/api/v1/preview/policy", admin, wanted, 200);
        JsonNode now = get200("/api/v1/preview/policy", viewer);
        assertThat(now.get("print").asString()).isEqualTo("watermark");
        assertThat(now.get("download").asString()).isEqualTo("block");
        assertThat(now.get("copy").asString()).isEqualTo("block");

        JsonNode refused = adminPut("/api/v1/preview/policy", admin,
                Map.of("print", "allow", "download", "allow", "copy", "watermark"), 400);
        assertThat(refused.get("code").asString()).isEqualTo("PREVIEW_POLICY_INVALID");
    }

    @Test
    void theFailureListAndItsRetry_areForAdministratorsOnly() throws Exception {
        call(get("/api/v1/preview/failures"), fx.jwtFor(viewer), null, 403);
        call(post("/api/v1/preview/failures/" + UUID.randomUUID() + "/retry"), fx.jwtFor(viewer), null, 403);

        JsonNode list = call(get("/api/v1/preview/failures"), fx.tenantAdminJwtFor(admin), null, 200);
        assertThat(list.get("content").isArray()).isTrue();
        call(post("/api/v1/preview/failures/" + UUID.randomUUID() + "/retry"), fx.tenantAdminJwtFor(admin), null, 404);
    }

    @Test
    void pagesAndThumbnails_areServedAsPicturesThatTheBrowserMustAskForAgain() throws Exception {
        JsonNode deck = get200("/api/v1/documents/" + documentId + "/preview/pages", viewer);
        assertThat(deck.get("pages").asInt()).isEqualTo(2);
        assertThat(deck.get("watermarked").asBoolean()).isFalse();

        MockHttpServletResponse page = getRaw("/api/v1/documents/" + documentId + "/preview/pages/2?width=500", fx.jwtFor(viewer));
        assertThat(page.getStatus()).isEqualTo(200);
        assertThat(page.getContentType()).isEqualTo("image/jpeg");
        assertThat(page.getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store");
        assertThat(page.getContentAsByteArray()).isNotEmpty();
        assertThat(getRaw("/api/v1/documents/" + documentId + "/preview/pages/3", fx.jwtFor(viewer)).getStatus())
                .isEqualTo(404);

        MockHttpServletResponse thumb = getRaw("/api/v1/documents/" + documentId + "/thumbnail", fx.jwtFor(viewer));
        assertThat(thumb.getStatus()).isEqualTo(200);
        assertThat(thumb.getContentType()).isEqualTo("image/jpeg");
        String tag = thumb.getHeader(HttpHeaders.ETAG);
        assertThat(tag).isNotBlank();
        // The browser keeps it, but asks every time - so access is checked every time.
        assertThat(thumb.getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-cache");
        MvcResult again = mvc.perform(get("/api/v1/documents/" + documentId + "/thumbnail")
                .header(HttpHeaders.IF_NONE_MATCH, tag).with(fx.jwtFor(viewer))).andReturn();
        assertThat(again.getResponse().getStatus()).isEqualTo(304);
    }

    @Test
    void someoneWithoutAccess_getsNoPageAndNoThumbnail_evenThoughTheyAreStored() throws Exception {
        getRaw("/api/v1/documents/" + documentId + "/thumbnail", fx.jwtFor(viewer));
        User stranger = fx.user(tenantId, "Sam");
        fx.role(tenantId, "Other readers", new String[]{VIEW}, stranger);
        UUID elsewhere = fx.tenant();
        User outsider = fx.user(elsewhere, "Olga");
        fx.role(elsewhere, "Outside readers", new String[]{VIEW}, outsider);

        // Another organisation's user can't see it at all...
        assertThat(getRaw("/api/v1/documents/" + documentId + "/thumbnail", fx.jwtFor(outsider)).getStatus()).isEqualTo(404);
        assertThat(getRaw("/api/v1/documents/" + documentId + "/preview/pages/1", fx.jwtFor(outsider)).getStatus()).isEqualTo(404);
        // ...and nobody without the view permission can either.
        User noRole = fx.user(tenantId, "Nora");
        assertThat(getRaw("/api/v1/documents/" + documentId + "/thumbnail", fx.jwtFor(noRole)).getStatus()).isEqualTo(403);
    }

    @Test
    void theDownloadButtonOfAPreview_followsThePolicy() throws Exception {
        String path = "/api/v1/documents/" + documentId + "/content?channel=preview";

        // allow: the ordinary download.
        MockHttpServletResponse plain = getRaw(path, fx.jwtFor(viewer));
        assertThat(plain.getStatus()).isEqualTo(200);
        assertThat(plain.getHeader(HttpHeaders.CONTENT_DISPOSITION)).contains("attachment").doesNotContain("watermarked");

        adminPut("/api/v1/preview/policy", admin, Map.of("print", "allow", "download", "block", "copy", "allow"), 200);
        MockHttpServletResponse blocked = getRaw(path, fx.jwtFor(viewer));
        assertThat(blocked.getStatus()).isEqualTo(403);
        assertThat(blocked.getContentAsString()).contains("PREVIEW_DOWNLOAD_BLOCKED");
        // The preview channel is the only thing restricted: the ordinary download follows the document's permission.
        assertThat(getRaw("/api/v1/documents/" + documentId + "/content", fx.jwtFor(viewer)).getStatus()).isEqualTo(200);

        adminPut("/api/v1/preview/policy", admin, Map.of("print", "allow", "download", "watermark", "copy", "allow"), 200);
        MockHttpServletResponse stamped = getRaw(path, fx.jwtFor(viewer));
        assertThat(stamped.getStatus()).isEqualTo(200);
        assertThat(stamped.getContentType()).isEqualTo("application/pdf");
        assertThat(stamped.getHeader(HttpHeaders.CONTENT_DISPOSITION)).contains("(watermarked).pdf");
        try (PDDocument copy = Loader.loadPDF(stamped.getContentAsByteArray())) {
            assertThat(copy.getNumberOfPages()).isEqualTo(2);
        }
    }

    private static byte[] pdf(int pages) throws Exception {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (int i = 0; i < pages; i++) {
                document.addPage(new PDPage(PDRectangle.A4));
            }
            document.save(out);
            return out.toByteArray();
        }
    }
}
