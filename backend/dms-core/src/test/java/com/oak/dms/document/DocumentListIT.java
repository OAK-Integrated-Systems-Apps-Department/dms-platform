package com.oak.dms.document;

import com.oak.dms.user.User;
import com.oak.dms.workflow.support.WorkflowApiSupport;
import com.oak.dms.workflow.support.WorkflowFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** GET /api/v1/documents - the document list behind the supporting-document picker. */
@SpringBootTest
@Import(WorkflowFixtures.class)
class DocumentListIT extends WorkflowApiSupport {

    private UUID tenantId;
    private User viewer;
    private UUID contractType;
    private UUID invoiceType;
    private UUID agreement;
    private UUID annex;
    private UUID invoice;

    @BeforeEach
    void world() {
        tenantId = fx.tenant();
        viewer = fx.user(tenantId, "Viewer");
        fx.role(tenantId, "Viewer " + UUID.randomUUID(), new String[]{"docm.documents.view"}, viewer);
        contractType = fx.documentType(tenantId, "Contract");
        invoiceType = fx.documentType(tenantId, "Invoice");
        // Created in this order, so newest first is invoice, annex, agreement.
        agreement = fx.document(tenantId, contractType, viewer, "Supply Agreement 2026");
        annex = fx.document(tenantId, contractType, viewer, "Supply annex B");
        invoice = fx.document(tenantId, invoiceType, viewer, "March invoice");
    }

    private JsonNode list(String query) throws Exception {
        return get200("/api/v1/documents" + query, viewer);
    }

    private static List<UUID> ids(JsonNode page) {
        List<UUID> ids = new ArrayList<>();
        page.get("content").forEach(d -> ids.add(UUID.fromString(d.get("documentId").asString())));
        return ids;
    }

    @Test
    void searchMatchesTitleCaseInsensitively_newestFirst() throws Exception {
        assertThat(ids(list("?search=SUPPLY"))).containsExactly(annex, agreement);
        assertThat(ids(list("?search=ANNEX b"))).containsExactly(annex);
        assertThat(ids(list(""))).containsExactly(invoice, annex, agreement);

        JsonNode row = list("?search=march").get("content").get(0);
        assertThat(row.get("title").asString()).isEqualTo("March invoice");
        assertThat(row.get("documentTypeName").asString()).isEqualTo("Invoice");
        assertThat(row.get("latestVersionId").isNull()).isFalse();
        assertThat(row.get("createdAt").isNull()).isFalse();
        assertThat(row.get("createdByName").asString()).isEqualTo(viewer.getDisplayName());
    }

    @Test
    void typeFilter_andExclusion() throws Exception {
        assertThat(ids(list("?documentTypeId=" + invoiceType))).containsExactly(invoice);
        assertThat(ids(list("?documentTypeId=" + contractType + "&excludeDocumentId=" + agreement)))
                .containsExactly(annex);
    }

    @Test
    void pagination_andDeletedDocumentsAreLeftOut() throws Exception {
        JsonNode second = list("?page=1&size=1");
        assertThat(ids(second)).containsExactly(annex);
        assertThat(second.get("totalElements").asLong()).isEqualTo(3);
        assertThat(second.get("totalPages").asInt()).isEqualTo(3);
        assertThat(list("?size=500").get("size").asInt()).as("size is capped").isEqualTo(50);

        fx.jdbc().update("update documents set is_deleted = true, deleted_at = now() where document_id = ?", annex);
        assertThat(ids(list(""))).containsExactly(invoice, agreement);
    }

    @Test
    void needsViewPermission_andNeverShowsAnotherTenantsDocuments() throws Exception {
        User noView = fx.user(tenantId, "NoView");
        call(get("/api/v1/documents"), fx.jwtFor(noView), null, 403);

        UUID otherTenant = fx.tenant();
        User otherViewer = fx.user(otherTenant, "Other");
        fx.role(otherTenant, "Viewer " + UUID.randomUUID(), new String[]{"docm.documents.view"}, otherViewer);
        UUID otherDocument = fx.document(otherTenant, fx.documentType(otherTenant, "Contract"), otherViewer,
                "Supply Agreement (other tenant)");

        assertThat(ids(list("?search=supply"))).doesNotContain(otherDocument).containsExactly(annex, agreement);
        assertThat(ids(call(get("/api/v1/documents?search=supply"), fx.jwtFor(otherViewer), null, 200)))
                .containsExactly(otherDocument);
        // Even naming this tenant's type returns nothing for the other tenant.
        assertThat(call(get("/api/v1/documents?documentTypeId=" + contractType), fx.jwtFor(otherViewer), null, 200)
                .get("totalElements").asLong()).isZero();
    }
}
