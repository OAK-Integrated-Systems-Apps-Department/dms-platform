package com.oak.dms.ownership;

import com.oak.dms.user.User;
import com.oak.dms.workflow.support.WorkflowFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** O7: the per-document history (newest first, names for deactivated and deleted users) and the filtered CSV export. */
@SpringBootTest
@Import(WorkflowFixtures.class)
class OwnershipHistoryIT extends OwnershipApiSupport {

    private UUID tenantId;
    private UUID type;
    private User admin;
    private User owner;
    private User second;
    private User third;

    @BeforeEach
    void world() {
        tenantId = fx.tenant();
        type = fx.documentType(tenantId, "Contract");
        admin = fx.user(tenantId, "Admin");
        owner = fx.user(tenantId, "Owner");
        second = fx.user(tenantId, "Second");
        third = fx.user(tenantId, "Third");
        fx.role(tenantId, "Viewers", new String[]{VIEW}, owner, second, third);
    }

    private void move(UUID documentId, User to) throws Exception {
        call(post("/api/v1/documents/" + documentId + "/ownership/transfer"), fx.tenantAdminJwtFor(admin),
                body("newOwnerUserId", to.getUserId()), 200);
    }

    private JsonNode history(User as, boolean tenantAdmin, UUID documentId, String query, int status) throws Exception {
        return call(get("/api/v1/documents/" + documentId + "/ownership-history" + query),
                tenantAdmin ? fx.tenantAdminJwtFor(as) : fx.jwtFor(as), null, status);
    }

    private String csv(User as, boolean tenantAdmin, String query, int status) throws Exception {
        var request = get("/api/v1/ownership/history/export" + query);
        var response = mvc.perform(request.with(tenantAdmin ? fx.tenantAdminJwtFor(as) : fx.jwtFor(as))).andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(status);
        return response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    @Test
    void historyIsNewestFirstWithNamesStatusesAndTheActor() throws Exception {
        UUID doc = fx.document(tenantId, type, owner, "History doc");
        move(doc, second);
        move(doc, third);

        JsonNode page = history(admin, true, doc, "", 200);

        assertThat(page.get("total").asInt()).isEqualTo(2);
        JsonNode newest = page.get("items").get(0);
        assertThat(newest.get("previousOwner").get("name").asString()).isEqualTo("Second");
        assertThat(newest.get("newOwner").get("name").asString()).isEqualTo("Third");
        assertThat(newest.get("actor").get("name").asString()).isEqualTo("Admin");
        assertThat(newest.get("reason").asString()).isEqualTo("manual");
        assertThat(page.get("items").get(1).get("previousOwner").get("name").asString()).isEqualTo("Owner");
        assertThat(history(admin, true, doc, "?size=1&page=1", 200).get("items").get(0).get("newOwner").get("name").asString())
                .isEqualTo("Second");
    }

    @Test
    void namesStillResolveForDeactivatedAndPermanentlyDeletedUsers() throws Exception {
        UUID doc = fx.document(tenantId, type, owner, "Names doc");
        move(doc, second);
        move(doc, third);
        fx.jdbc().update("update users set is_deleted = true, status = 'disabled' where user_id = ?", second.getUserId());

        JsonNode deactivated = history(admin, true, doc, "", 200).get("items");
        assertThat(deactivated.get(0).get("previousOwner").get("name").asString()).isEqualTo("Second");
        assertThat(deactivated.get(0).get("previousOwner").get("status").asString()).isEqualTo("deactivated");

        // purge the account entirely: the snapshot stored on the history row takes over
        // (notifications and their append-only delivery log still point at the account, so lift the FK checks for this one delete)
        fx.jdbc().execute((java.sql.Connection c) -> {
            try (var st = c.createStatement()) {
                st.execute("set session_replication_role = replica");
                st.execute("delete from users where user_id = '" + second.getUserId() + "'");
                st.execute("set session_replication_role = origin");
            }
            return null;
        });
        JsonNode purged = history(admin, true, doc, "", 200).get("items");
        assertThat(purged.get(0).get("previousOwner").get("name").asString()).isEqualTo("Second");
        assertThat(purged.get(0).get("previousOwner").get("status").isNull()).isTrue();
        assertThat(purged.get(1).get("newOwner").get("name").asString()).isEqualTo("Second");
    }

    @Test
    void historyIsAppendOnlyEvenForTheOwnerRole() throws Exception {
        UUID doc = fx.document(tenantId, type, owner, "Immutable doc");
        move(doc, second);
        assertThatThrownBy(() -> fx.jdbc().update("update ownership_transfers set note = 'tampered' where document_id = ?", doc))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThatThrownBy(() -> fx.jdbc().update("delete from ownership_transfers where document_id = ?", doc))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("append-only");
        assertThat(historyRows(tenantId, doc)).isEqualTo(1);
    }

    @Test
    void permissionsHiddenDocumentsAndTenantIsolation() throws Exception {
        UUID doc = fx.document(tenantId, type, owner, "Guarded history");
        move(doc, second);
        User viewer = fx.user(tenantId, "Viewer");
        fx.role(tenantId, "History viewer", new String[]{HISTORY, VIEW}, viewer);
        history(owner, false, doc, "", 403);                         // no history permission
        history(viewer, false, doc, "", 200);
        restrictTo(tenantId, doc, "edit", second);
        history(viewer, false, doc, "", 404);                        // can't open the document
        history(admin, true, doc, "", 200);
        UUID other = fx.tenant();
        history(fx.user(other, "OtherAdmin"), true, doc, "", 404);
        history(admin, true, UUID.randomUUID(), "", 404);
        UUID purged = fx.document(tenantId, type, owner, "Purged history");
        fx.jdbc().update("update documents set is_deleted = true, purged_at = now() where document_id = ?", purged);
        history(admin, true, purged, "", 404);
    }

    @Test
    void exportHasTheRowsFiltersAndIsSafeAgainstFormulas() throws Exception {
        UUID doc = fx.document(tenantId, type, owner, "=HYPERLINK(\"http://evil\")");
        UUID plain = fx.document(tenantId, type, owner, "Plain, with comma");
        move(doc, second);
        move(plain, second);
        move(plain, third);

        String all = csv(admin, true, "", 200);
        String[] lines = all.split("\r\n");
        assertThat(lines).hasSize(4);
        assertThat(lines[0]).startsWith("Transferred at,Document id,Document title,Previous owner");
        assertThat(all).contains("'=HYPERLINK(").doesNotContain(",=HYPERLINK(").contains("\"Plain, with comma\"");
        assertThat(all).contains("Owner").contains("Second").contains("Third").contains("manual");

        assertThat(csv(admin, true, "?documentId=" + plain, 200).split("\r\n")).hasSize(3);
        assertThat(csv(admin, true, "?userId=" + third.getUserId(), 200).split("\r\n")).hasSize(2);
        assertThat(csv(admin, true, "?reason=holding", 200).split("\r\n")).hasSize(1);
        assertThat(csv(admin, true, "?from=2999-01-01T00:00:00Z", 200).split("\r\n")).hasSize(1);
        assertThat(csv(admin, true, "?to=2999-01-01T00:00:00Z", 200).split("\r\n")).hasSize(4);
        String batch = fx.jdbc().queryForObject("select batch_id::text from ownership_transfers where document_id = ? limit 1", String.class, doc);
        assertThat(csv(admin, true, "?batchId=" + batch, 200).split("\r\n")).hasSize(2);
    }

    @Test
    void exportNeedsTheExportPermissionAndHidesDocumentsFromNonAdmins() throws Exception {
        UUID open = fx.document(tenantId, type, owner, "Open one");
        UUID secret = fx.document(tenantId, type, owner, "Secret one");
        move(open, second);
        move(secret, second);
        restrictTo(tenantId, secret, "edit", second);

        User viewOnly = fx.user(tenantId, "ViewOnly");
        fx.role(tenantId, "History only", new String[]{HISTORY, VIEW}, viewOnly);
        csv(viewOnly, false, "", 403);

        User exporter = fx.user(tenantId, "Exporter");
        fx.role(tenantId, "Exporter", new String[]{EXPORT, VIEW}, exporter);
        String rows = csv(exporter, false, "", 200);
        assertThat(rows).contains("Open one").doesNotContain("Secret one");
        assertThat(csv(admin, true, "", 200)).contains("Secret one");

        // another tenant's admin gets only their own (empty) history
        UUID other = fx.tenant();
        String foreign = csv(fx.user(other, "OtherAdmin"), true, "", 200);
        assertThat(foreign).doesNotContain("Open one").doesNotContain("Secret one");
        assertThat(foreign.split("\r\n")).hasSize(1);
    }
}
