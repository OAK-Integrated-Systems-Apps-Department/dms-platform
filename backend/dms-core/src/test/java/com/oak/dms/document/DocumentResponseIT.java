package com.oak.dms.document;

import com.oak.dms.user.User;
import com.oak.dms.workflow.support.WorkflowApiSupport;
import com.oak.dms.workflow.support.WorkflowFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * {@code GET /documents/{id}} carries the document type's name, so a viewer
 * holding only {@code docm.documents.view} sees it without the
 * {@code mdtm.manage-content-types.*} permission that
 * {@code GET /document-types/{id}} needs.
 */
@SpringBootTest
@Import(WorkflowFixtures.class)
class DocumentResponseIT extends WorkflowApiSupport {

    @Test
    void documentResponse_includesTypeName_forAPlainViewer() throws Exception {
        UUID tenantId = fx.tenant();
        User owner = fx.user(tenantId, "Owner");
        User viewer = fx.user(tenantId, "Viewer");
        fx.role(tenantId, "Viewer " + UUID.randomUUID(), new String[]{"docm.documents.view"}, viewer);
        UUID typeId = fx.documentType(tenantId, "Contract");
        UUID documentId = fx.document(tenantId, typeId, owner, "Supply agreement");

        JsonNode document = call(get("/api/v1/documents/" + documentId), fx.jwtFor(viewer), null, 200);

        assertThat(document.get("documentTypeId").asString()).isEqualTo(typeId.toString());
        assertThat(document.get("documentTypeName").asString()).isEqualTo("Contract");
        call(get("/api/v1/document-types/" + typeId), fx.jwtFor(viewer), null, 403);
    }

    @Test
    void documentResponse_typeNameIsNull_whenTheTypeWasDeleted() throws Exception {
        UUID tenantId = fx.tenant();
        User owner = fx.user(tenantId, "Owner");
        fx.role(tenantId, "Viewer " + UUID.randomUUID(), new String[]{"docm.documents.view"}, owner);
        UUID typeId = fx.documentType(tenantId, "Invoice");
        UUID documentId = fx.document(tenantId, typeId, owner, "March invoice");
        fx.jdbc().update("update document_types set is_deleted = true, deleted_at = now() where document_type_id = ?",
                typeId);

        JsonNode document = call(get("/api/v1/documents/" + documentId), fx.jwtFor(owner), null, 200);

        assertThat(document.path("documentTypeName").isNull() || document.path("documentTypeName").isMissingNode()).isTrue();
    }
}
