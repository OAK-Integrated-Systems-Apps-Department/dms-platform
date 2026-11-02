package com.oak.dms.ownership;

import com.oak.dms.department.entity.Department;
import com.oak.dms.security.keycloak.KeycloakUserService;
import com.oak.dms.user.User;
import com.oak.dms.workflow.support.WorkflowFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/** O9: ownership notifications - one summary per recipient for a bulk run, access-aware text for a single transfer, the holding alert. */
@SpringBootTest
@Import(WorkflowFixtures.class)
class OwnershipNotificationIT extends OwnershipApiSupport {

    @MockitoBean
    KeycloakUserService keycloak;

    private UUID tenantId;
    private UUID type;
    private User admin;
    private User owner;
    private User other;
    private User successor;
    private User keeper;
    private Department finance;

    @BeforeEach
    void world() {
        tenantId = fx.tenant();
        type = fx.documentType(tenantId, "Contract");
        admin = fx.user(tenantId, "Admin");
        owner = fx.user(tenantId, "Owner");
        other = fx.user(tenantId, "Other");
        successor = fx.user(tenantId, "Successor");
        keeper = fx.user(tenantId, "Keeper");
        finance = fx.department(tenantId, "Finance", owner, other);
        fx.role(tenantId, "Viewers", new String[]{VIEW}, owner, other, successor, keeper);
        when(keycloak.listRealmRoleMemberIds("TENANT_ADMIN")).thenReturn(List.of(admin.getKeycloakId()));
        when(keycloak.getPlatformRealmRoles(admin.getKeycloakId())).thenReturn(List.of("TENANT_ADMIN"));
    }

    private List<Map<String, Object>> notifications(User recipient, String category) {
        return fx.jdbc().queryForList("select title, body, link_url, object_id from notifications where tenant_id = ?"
                + " and recipient_user_id = ? and category_code = ? order by created_at", tenantId, recipient.getUserId(), category);
    }

    private long all(String category) {
        return fx.count("select count(*) from notifications where tenant_id = ? and category_code = ?", tenantId, category);
    }

    private JsonNode single(UUID doc, User to, Object... extra) throws Exception {
        var request = body("newOwnerUserId", to.getUserId());
        for (int i = 0; i < extra.length; i += 2) {
            request.put((String) extra[i], extra[i + 1]);
        }
        return call(post("/api/v1/documents/" + doc + "/ownership/transfer"), fx.tenantAdminJwtFor(admin), request, 200);
    }

    @Test
    void singleTransferNamesTheDocumentToRecipientsWhoCanOpenIt() throws Exception {
        UUID doc = fx.document(tenantId, type, owner, "Master agreement");
        single(doc, successor);

        var received = notifications(successor, "document.ownership.received");
        assertThat(received).hasSize(1);
        assertThat(received.get(0).get("title").toString()).contains("Master agreement");
        assertThat(received.get(0).get("link_url")).isEqualTo("/documents/" + doc);
        var released = notifications(owner, "document.ownership.released");
        assertThat(released).hasSize(1);
        assertThat(released.get(0).get("title").toString()).contains("Master agreement");
        assertThat(notifications(admin, "document.ownership.received")).isEmpty();
        assertThat(all("document.ownership.received") + all("document.ownership.released")).isEqualTo(2);
    }

    @Test
    void recipientsWhoCannotOpenTheDocumentGetNeutralText() throws Exception {
        UUID folder = folder(tenantId, "Closed", true, "edit", owner);
        UUID doc = fx.document(tenantId, type, owner, "Confidential merger plan");
        file(doc, folder);

        single(doc, successor, "grantAccess", false);     // the new owner is NOT let in

        var received = notifications(successor, "document.ownership.received");
        assertThat(received).hasSize(1);
        assertThat(received.get(0).toString()).doesNotContain("Confidential merger plan");
        assertThat(received.get(0).get("link_url")).isNull();
        // the previous owner can still open it, so they see the title
        assertThat(notifications(owner, "document.ownership.released").get(0).get("title").toString())
                .contains("Confidential merger plan");

        // with access granted, the new owner gets the real text
        UUID doc2 = fx.document(tenantId, type, owner, "Another confidential plan");
        file(doc2, folder);
        single(doc2, keeper);
        assertThat(notifications(keeper, "document.ownership.received").get(0).get("title").toString())
                .contains("Another confidential plan");
    }

    @Test
    void bulkTransferSendsOneSummaryPerRecipientAndNeverATitle() throws Exception {
        for (int i = 0; i < 4; i++) {
            fx.document(tenantId, type, owner, "Secret title " + i);
        }
        for (int i = 0; i < 2; i++) {
            fx.document(tenantId, type, other, "Other title " + i);
        }
        List<UUID> ids = new ArrayList<>(fx.jdbc().queryForList("select document_id from documents where tenant_id = ?", UUID.class, tenantId));

        call(post("/api/v1/ownership/transfers"), fx.tenantAdminJwtFor(admin), selected(ids, successor), 200);

        var received = notifications(successor, "document.ownership.received");
        assertThat(received).hasSize(1);
        assertThat(received.get(0).get("title")).isEqualTo("6 documents transferred to you");
        assertThat(received.get(0).get("link_url")).isEqualTo("/documents?ownerId=" + successor.getUserId());
        var ownerNote = notifications(owner, "document.ownership.released");
        var otherNote = notifications(other, "document.ownership.released");
        assertThat(ownerNote).hasSize(1);
        assertThat(otherNote).hasSize(1);
        assertThat(ownerNote.get(0).get("title")).isEqualTo("4 of your documents were transferred");
        assertThat(otherNote.get(0).get("title")).isEqualTo("2 of your documents were transferred");
        assertThat(all("document.ownership.received")).isEqualTo(1);
        assertThat(all("document.ownership.released")).isEqualTo(2);
        assertThat(fx.count("select count(*) from notifications where tenant_id = ? and (title like '%Secret title%'"
                + " or body like '%Secret title%')", tenantId)).isZero();
    }

    @Test
    void holdingAssignmentAlertsTheHoldingOwnerAndTheAdmins() throws Exception {
        fx.document(tenantId, type, owner, "Parked one");
        fx.document(tenantId, type, owner, "Parked two");
        call(put("/api/v1/departments/" + finance.getDepartmentId()), fx.tenantAdminJwtFor(admin),
                body("name", "Finance", "status", "active", "holdingOwnerUserId", keeper.getUserId()), 200);

        call(post("/api/v1/ownership/transfers"), fx.tenantAdminJwtFor(admin),
                body("sourceUserId", owner.getUserId(), "mode", "all", "target", body("type", "holding")), 200);

        var holder = notifications(keeper, "document.ownership.holding");
        assertThat(holder).hasSize(1);
        assertThat(holder.get(0).get("title")).isEqualTo("2 documents waiting for a new owner");
        assertThat(holder.get(0).get("link_url")).isEqualTo("/documents?ownerId=" + keeper.getUserId());
        // the admin did the action, so they are not told; another admin would be
        assertThat(notifications(admin, "document.ownership.holding")).isEmpty();
        assertThat(all("document.ownership.received")).isZero();
        assertThat(notifications(owner, "document.ownership.released")).hasSize(1);

        User admin2 = fx.user(tenantId, "Admin2");
        when(keycloak.listRealmRoleMemberIds("TENANT_ADMIN")).thenReturn(List.of(admin.getKeycloakId(), admin2.getKeycloakId()));
        fx.document(tenantId, type, other, "Parked three");
        call(post("/api/v1/ownership/transfers"), fx.tenantAdminJwtFor(admin),
                body("sourceUserId", other.getUserId(), "mode", "all", "target", body("type", "holding")), 200);
        var adminNote = notifications(admin2, "document.ownership.holding");
        assertThat(adminNote).hasSize(1);
        assertThat(adminNote.get(0).get("link_url")).isEqualTo("/admin/ownership?tab=pending");
    }

    @Test
    void emptyRunsAndOffboardedUsersSendNothingExtra() throws Exception {
        call(post("/api/v1/ownership/transfers"), fx.tenantAdminJwtFor(admin), allOf(owner, successor), 200);
        assertThat(all("document.ownership.received") + all("document.ownership.released") + all("document.ownership.holding"))
                .isZero();

        UUID doc = fx.document(tenantId, type, owner, "Goodbye doc");
        call(post("/api/v1/users/" + owner.getUserId() + "/offboard"), fx.tenantAdminJwtFor(admin),
                body("reason", "Left", "mode", "transfer", "newOwnerUserId", successor.getUserId()), 200);
        assertThat(ownerOf(doc)).isEqualTo(successor.getUserId().toString());
        assertThat(notifications(successor, "document.ownership.received")).hasSize(1);
        assertThat(notifications(owner, "document.ownership.released")).as("a deactivated user is not messaged").isEmpty();
    }

    @Test
    void categoriesAreSeededWithTheRightTiers() {
        assertThat(fx.count("select count(*) from notification_categories where category_code = 'document.ownership.received'"
                + " and tier = 'standard' and module = 'documents'")).isEqualTo(1);
        assertThat(fx.count("select count(*) from notification_categories where category_code = 'document.ownership.released'"
                + " and tier = 'optional' and not default_email_enabled")).isEqualTo(1);
        assertThat(fx.count("select count(*) from notification_categories where category_code = 'document.ownership.holding'"
                + " and tier = 'standard'")).isEqualTo(1);
    }
}
