package com.oak.dms.ownership;

import com.oak.dms.department.entity.Department;
import com.oak.dms.user.User;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** O8: find by owner on the browse endpoint (access filtering unchanged) and the document page's owner info. */
@SpringBootTest
@Import(WorkflowFixtures.class)
class OwnershipFilterIT extends OwnershipApiSupport {

    private UUID tenantId;
    private UUID type;
    private User admin;
    private User alice;
    private User bob;
    private User reader;
    private Department finance;
    private Department legal;

    @BeforeEach
    void world() {
        tenantId = fx.tenant();
        type = fx.documentType(tenantId, "Contract");
        admin = fx.user(tenantId, "Admin");
        alice = fx.user(tenantId, "Alice");
        bob = fx.user(tenantId, "Bob");
        reader = fx.user(tenantId, "Reader");
        finance = fx.department(tenantId, "Finance", alice);
        legal = fx.department(tenantId, "Legal", bob);
        fx.role(tenantId, "Viewers", new String[]{VIEW}, alice, bob, reader);
    }

    private List<String> titles(User as, boolean tenantAdmin, String query) throws Exception {
        JsonNode page = call(get("/api/v1/documents?" + query), tenantAdmin ? fx.tenantAdminJwtFor(as) : fx.jwtFor(as), null, 200);
        List<String> titles = new ArrayList<>();
        page.get("content").forEach(d -> titles.add(d.get("title").asString()));
        return titles;
    }

    @Test
    void ownerAndDepartmentFiltersCombineWithTheOtherFilters() throws Exception {
        fx.document(tenantId, type, alice, "Alice budget");
        fx.document(tenantId, type, alice, "Alice plan");
        fx.document(tenantId, type, bob, "Bob budget");
        UUID folder = folder(tenantId, "Archive", false, "view");
        UUID filed = fx.document(tenantId, type, alice, "Alice filed");
        file(filed, folder);

        assertThat(titles(reader, false, "all=true&ownerId=" + alice.getUserId()))
                .containsExactlyInAnyOrder("Alice budget", "Alice plan", "Alice filed");
        assertThat(titles(reader, false, "all=true&ownerDepartmentId=" + legal.getDepartmentId())).containsExactly("Bob budget");
        assertThat(titles(reader, false, "all=true&ownerId=" + alice.getUserId() + "&q=budget")).containsExactly("Alice budget");
        assertThat(titles(reader, false, "all=true&ownerId=" + alice.getUserId() + "&ownerDepartmentId=" + legal.getDepartmentId())).isEmpty();
        assertThat(titles(reader, false, "folderId=" + folder + "&ownerId=" + alice.getUserId())).containsExactly("Alice filed");
        assertThat(titles(reader, false, "ownerId=" + alice.getUserId())).as("top level only")
                .containsExactlyInAnyOrder("Alice budget", "Alice plan");
        assertThat(titles(admin, true, "all=true&ownerDepartmentId=" + finance.getDepartmentId())).hasSize(3);
        assertThat(titles(reader, false, "all=true")).hasSize(4);
    }

    @Test
    void theFilterNeverRevealsDocumentsTheCallerCannotOpen() throws Exception {
        fx.document(tenantId, type, alice, "Alice open");
        UUID secret = fx.document(tenantId, type, alice, "Alice secret");
        restrictTo(tenantId, secret, "edit", alice);
        UUID closedFolder = folder(tenantId, "Closed", true, "edit", alice);
        UUID inClosed = fx.document(tenantId, type, alice, "Alice in closed folder");
        file(inClosed, closedFolder);

        assertThat(titles(reader, false, "all=true&ownerId=" + alice.getUserId())).containsExactly("Alice open");
        assertThat(titles(reader, false, "all=true&ownerDepartmentId=" + finance.getDepartmentId())).containsExactly("Alice open");
        assertThat(titles(alice, false, "all=true&ownerId=" + alice.getUserId()))
                .containsExactlyInAnyOrder("Alice open", "Alice secret", "Alice in closed folder");
        assertThat(titles(admin, true, "all=true&ownerId=" + alice.getUserId())).hasSize(3);
    }

    @Test
    void relationshipBrowsingStillHonoursTheOwnerFilter() throws Exception {
        UUID a = fx.document(tenantId, type, alice, "Alice linked");
        UUID b = fx.document(tenantId, type, bob, "Bob linked");
        UUID relType = UUID.randomUUID();
        fx.jdbc().update("insert into relationship_types (relationship_type_id, tenant_id, code, category, label, inverse_label,"
                + " directionality) values (?, ?, 'RELATED', 'association', 'Related', 'Related', 'symmetric')", relType, tenantId);
        fx.jdbc().update("insert into document_relationships (tenant_id, relationship_type_id, source_document_id,"
                + " target_document_id) values (?, ?, ?, ?)", tenantId, relType, a, b);
        assertThat(titles(reader, false, "relationshipType=RELATED")).containsExactlyInAnyOrder("Alice linked", "Bob linked");
        assertThat(titles(reader, false, "relationshipType=RELATED&ownerId=" + bob.getUserId())).containsExactly("Bob linked");
    }

    @Test
    void listItemsCarryTheOwnerDepartmentAndState() throws Exception {
        UUID doc = fx.document(tenantId, type, alice, "Alice doc");
        JsonNode page = call(get("/api/v1/documents?all=true"), fx.jwtFor(reader), null, 200);
        JsonNode row = page.get("content").get(0);
        assertThat(row.get("ownerDepartmentId").asString()).isEqualTo(finance.getDepartmentId().toString());
        assertThat(row.get("ownershipState").asString()).isEqualTo("assigned");
        JsonNode detail = call(get("/api/v1/documents/" + doc), fx.jwtFor(reader), null, 200);
        assertThat(detail.get("ownerDepartmentId").asString()).isEqualTo(finance.getDepartmentId().toString());
        assertThat(detail.get("ownershipState").asString()).isEqualTo("assigned");
    }

    @Test
    void ownerInfoNamesTheOwnerAndSaysWhetherTheCallerMayTransfer() throws Exception {
        UUID doc = fx.document(tenantId, type, alice, "Info doc");
        JsonNode viewer = call(get("/api/v1/documents/" + doc + "/ownership"), fx.jwtFor(reader), null, 200);
        assertThat(viewer.get("owner").get("name").asString()).isEqualTo("Alice");
        assertThat(viewer.get("owner").get("status").asString()).isEqualTo("active");
        assertThat(viewer.get("ownerDepartment").get("name").asString()).isEqualTo("Finance");
        assertThat(viewer.get("canTransfer").asBoolean()).isFalse();
        assertThat(call(get("/api/v1/documents/" + doc + "/ownership"), fx.tenantAdminJwtFor(admin), null, 200)
                .get("canTransfer").asBoolean()).isTrue();

        User manager = fx.user(tenantId, "Manager");
        makeHead(finance.getDepartmentId(), manager);
        fx.role(tenantId, "Dot manager", new String[]{INITIATE, VIEW}, manager);
        assertThat(call(get("/api/v1/documents/" + doc + "/ownership"), fx.jwtFor(manager), null, 200)
                .get("canTransfer").asBoolean()).isTrue();
        User otherHead = fx.user(tenantId, "OtherHead");
        makeHead(legal.getDepartmentId(), otherHead);
        fx.role(tenantId, "Dot other", new String[]{INITIATE, VIEW}, otherHead);
        assertThat(call(get("/api/v1/documents/" + doc + "/ownership"), fx.jwtFor(otherHead), null, 200)
                .get("canTransfer").asBoolean()).isFalse();

        fx.jdbc().update("update users set is_deleted = true, status = 'disabled' where user_id = ?", alice.getUserId());
        assertThat(call(get("/api/v1/documents/" + doc + "/ownership"), fx.jwtFor(reader), null, 200)
                .get("owner").get("status").asString()).isEqualTo("deactivated");

        restrictTo(tenantId, doc, "edit", bob);
        call(get("/api/v1/documents/" + doc + "/ownership"), fx.jwtFor(reader), null, 404);
        UUID other = fx.tenant();
        call(get("/api/v1/documents/" + doc + "/ownership"), fx.tenantAdminJwtFor(fx.user(other, "OtherAdmin")), null, 404);
    }
}
