package com.oak.dms.ownership;

import com.oak.dms.department.entity.Department;
import com.oak.dms.user.User;
import com.oak.dms.workflow.support.WorkflowFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/** O5: the department's holding owner (API, audit), the D5 fallback chain, the holding state and the pending list (D13). */
@SpringBootTest
@Import(WorkflowFixtures.class)
class OwnershipHoldingIT extends OwnershipApiSupport {

    /** The tenant admins come from Keycloak's TENANT_ADMIN realm role; the test says who they are. */
    @MockitoBean
    com.oak.dms.security.keycloak.KeycloakUserService keycloak;

    private UUID tenantId;
    private UUID type;
    private User admin;
    private User owner;
    private User successor;
    private User keeper;
    private User head;
    private Department finance;

    @BeforeEach
    void world() {
        tenantId = fx.tenant();
        type = fx.documentType(tenantId, "Contract");
        admin = fx.user(tenantId, "Admin");
        owner = fx.user(tenantId, "Owner");
        successor = fx.user(tenantId, "Successor");
        keeper = fx.user(tenantId, "Keeper");
        head = fx.user(tenantId, "Head");
        finance = fx.department(tenantId, "Finance", owner);
        fx.role(tenantId, "Viewers", new String[]{VIEW}, owner, successor, keeper, head);
        when(keycloak.listRealmRoleMemberIds("TENANT_ADMIN")).thenReturn(List.of(admin.getKeycloakId()));
        when(keycloak.getPlatformRealmRoles(admin.getKeycloakId())).thenReturn(List.of("TENANT_ADMIN"));
    }

    private JsonNode holdingTransfer(UUID sourceUser, Object... extra) throws Exception {
        var request = body("sourceUserId", sourceUser, "mode", "all", "target", body("type", "holding"));
        for (int i = 0; i < extra.length; i += 2) {
            request.put((String) extra[i], extra[i + 1]);
        }
        return call(post("/api/v1/ownership/transfers"), fx.tenantAdminJwtFor(admin), request, 200);
    }

    private JsonNode updateDepartment(Department department, Object... extra) throws Exception {
        var request = body("name", department.getName(), "status", "active");
        for (int i = 0; i < extra.length; i += 2) {
            request.put((String) extra[i], extra[i + 1]);
        }
        return call(put("/api/v1/departments/" + department.getDepartmentId()), fx.tenantAdminJwtFor(admin), request, 200);
    }

    @Test
    void departmentHoldingOwnerCanBeSetKeptAndCleared() throws Exception {
        JsonNode set = updateDepartment(finance, "holdingOwnerUserId", keeper.getUserId());
        assertThat(set.get("holdingOwnerUserId").asString()).isEqualTo(keeper.getUserId().toString());
        assertThat(set.get("holdingOwnerName").asString()).isEqualTo("Keeper");
        assertThat(auditCount(tenantId, "DEPARTMENT_HOLDING_OWNER_CHANGED")).isEqualTo(1);

        // a caller that doesn't know the field must not wipe it
        JsonNode kept = updateDepartment(finance, "name", "Finance & Treasury");
        assertThat(kept.get("holdingOwnerUserId").asString()).isEqualTo(keeper.getUserId().toString());
        assertThat(kept.get("name").asString()).isEqualTo("Finance & Treasury");
        assertThat(auditCount(tenantId, "DEPARTMENT_HOLDING_OWNER_CHANGED")).isEqualTo(1);

        JsonNode read = call(get("/api/v1/departments/" + finance.getDepartmentId()), fx.tenantAdminJwtFor(admin), null, 200);
        assertThat(read.get("holdingOwnerName").asString()).isEqualTo("Keeper");

        JsonNode cleared = updateDepartment(finance, "clearHoldingOwner", true);
        assertThat(cleared.get("holdingOwnerUserId").isNull()).isTrue();
        assertThat(auditCount(tenantId, "DEPARTMENT_HOLDING_OWNER_CHANGED")).isEqualTo(2);
    }

    @Test
    void holdingOwnerMustBeAnActiveUserOfTheTenant() throws Exception {
        User disabled = fx.user(tenantId, "Disabled");
        fx.jdbc().update("update users set status = 'disabled' where user_id = ?", disabled.getUserId());
        User foreign = fx.user(fx.tenant(), "Foreign");
        for (UUID bad : List.of(foreign.getUserId(), UUID.randomUUID())) {
            call(put("/api/v1/departments/" + finance.getDepartmentId()), fx.tenantAdminJwtFor(admin),
                    body("name", "Finance", "status", "active", "holdingOwnerUserId", bad), 404);
        }
        call(put("/api/v1/departments/" + finance.getDepartmentId()), fx.tenantAdminJwtFor(admin),
                body("name", "Finance", "status", "active", "holdingOwnerUserId", disabled.getUserId()), 409);
        assertThat(fx.jdbc().queryForObject("select holding_owner_user_id from departments where department_id = ?",
                UUID.class, finance.getDepartmentId())).isNull();
    }

    @Test
    void chainPrefersHoldingOwnerThenHeadThenAdmin() throws Exception {
        UUID d1 = fx.document(tenantId, type, owner, "One");
        // 1. holding owner set -> keeper
        updateDepartment(finance, "holdingOwnerUserId", keeper.getUserId(), "headUserId", head.getUserId());
        JsonNode first = holdingTransfer(owner.getUserId());
        assertThat(first.get("holding").asBoolean()).isTrue();
        assertThat(first.get("newOwner").get("name").asString()).isEqualTo("Keeper");
        assertThat(ownerOf(d1)).isEqualTo(keeper.getUserId().toString());
        assertThat(fx.jdbc().queryForObject("select ownership_state from documents where document_id = ?", String.class, d1))
                .isEqualTo("holding");
        assertThat(fx.jdbc().queryForObject("select owner_department_id from documents where document_id = ?", UUID.class, d1))
                .isEqualTo(finance.getDepartmentId());
        assertThat(fx.count("select count(*) from ownership_transfers where document_id = ? and reason = 'holding'", d1)).isEqualTo(1);

        // 2. the holding owner is the person leaving -> falls to the head
        UUID d2 = fx.document(tenantId, type, keeper, "Two");
        fx.jdbc().update("update users set department_id = ? where user_id = ?", finance.getDepartmentId(), keeper.getUserId());
        assertThat(holdingTransfer(keeper.getUserId(), "excludeDocumentIds", List.of(d1)).get("newOwner").get("name").asString())
                .isEqualTo("Head");
        assertThat(ownerOf(d2)).isEqualTo(head.getUserId().toString());

        // 3. holding owner deactivated, no head -> the tenant admin
        UUID d3 = fx.document(tenantId, type, owner, "Three");
        fx.jdbc().update("update users set is_deleted = true, status = 'disabled' where user_id = ?", keeper.getUserId());
        fx.jdbc().update("update departments set head_user_id = null where department_id = ?", finance.getDepartmentId());
        JsonNode third = holdingTransfer(owner.getUserId());
        assertThat(third.get("newOwner").get("name").asString()).isEqualTo("Admin");
        assertThat(ownerOf(d3)).isEqualTo(admin.getUserId().toString());
    }

    @Test
    void noHoldingOwnerAvailableIsRefused() throws Exception {
        fx.document(tenantId, type, owner, "Orphan");
        when(keycloak.listRealmRoleMemberIds("TENANT_ADMIN")).thenReturn(List.of());
        call(post("/api/v1/ownership/transfers"), fx.tenantAdminJwtFor(admin),
                body("sourceUserId", owner.getUserId(), "mode", "all", "target", body("type", "holding")), 422);
    }

    @Test
    void aRealTransferTakesADocumentOutOfHolding() throws Exception {
        UUID d1 = fx.document(tenantId, type, owner, "Parked");
        updateDepartment(finance, "holdingOwnerUserId", keeper.getUserId());
        holdingTransfer(owner.getUserId());
        assertThat(fx.jdbc().queryForObject("select ownership_state from documents where document_id = ?", String.class, d1))
                .isEqualTo("holding");

        // handing it to the very person who holds it is still a real change: parked -> assigned
        call(post("/api/v1/documents/" + d1 + "/ownership/transfer"), fx.tenantAdminJwtFor(admin),
                body("newOwnerUserId", keeper.getUserId(), "newOwnerDepartmentId", finance.getDepartmentId()), 200);
        assertThat(fx.jdbc().queryForObject("select ownership_state from documents where document_id = ?", String.class, d1))
                .isEqualTo("assigned");
        assertThat(historyRows(tenantId, d1)).isEqualTo(2);
    }

    @Test
    void pendingListShowsHoldingAndDeactivatedOwnerDocuments() throws Exception {
        UUID parked = fx.document(tenantId, type, owner, "Parked doc");
        updateDepartment(finance, "holdingOwnerUserId", keeper.getUserId());
        holdingTransfer(owner.getUserId());
        UUID orphan = fx.document(tenantId, type, successor, "Orphaned doc");
        fx.jdbc().update("update users set department_id = ? where user_id = ?", finance.getDepartmentId(), successor.getUserId());
        fx.jdbc().update("update documents set owner_department_id = ? where document_id = ?", finance.getDepartmentId(), orphan);
        fx.jdbc().update("update users set is_deleted = true, status = 'disabled' where user_id = ?", successor.getUserId());
        UUID binned = fx.document(tenantId, type, successor, "Binned orphan");
        fx.jdbc().update("update documents set is_deleted = true, owner_department_id = ? where document_id = ?",
                finance.getDepartmentId(), binned);
        UUID purged = fx.document(tenantId, type, successor, "Purged orphan");
        fx.jdbc().update("update documents set is_deleted = true, purged_at = now() where document_id = ?", purged);
        fx.document(tenantId, type, head, "Healthy doc");

        JsonNode pending = call(get("/api/v1/ownership/pending"), fx.tenantAdminJwtFor(admin), null, 200);
        assertThat(pending.get("total").asInt()).isEqualTo(3);
        String reasons = pending.get("items").toString();
        assertThat(reasons).contains("Parked doc").contains("Orphaned doc").contains("Binned orphan")
                .doesNotContain("Purged orphan").doesNotContain("Healthy doc");
        for (JsonNode item : pending.get("items")) {
            String title = item.get("title").asString();
            assertThat(item.get("pendingReason").asString()).isEqualTo(title.startsWith("Parked") ? "holding" : "owner_deactivated");
            if (title.startsWith("Orphaned")) {
                assertThat(item.get("owner").get("name").asString()).isEqualTo("Successor");
                assertThat(item.get("owner").get("status").asString()).isEqualTo("deactivated");
            }
        }
        assertThat(call(get("/api/v1/ownership/pending?departmentId=" + finance.getDepartmentId() + "&q=orphan"),
                fx.tenantAdminJwtFor(admin), null, 200).get("total").asInt()).isEqualTo(2);

        // a manager of that department sees only what they can open and a count of the rest
        User manager = fx.user(tenantId, "Manager");
        makeHead(finance.getDepartmentId(), manager);
        fx.role(tenantId, "Dot", new String[]{SELECT, VIEW}, manager);
        restrictTo(tenantId, orphan, "edit", head);
        JsonNode managerView = call(get("/api/v1/ownership/pending"), fx.jwtFor(manager), null, 200);
        assertThat(managerView.get("hiddenCount").asInt()).isEqualTo(1);
        assertThat(managerView.toString()).doesNotContain("Orphaned doc").doesNotContain(orphan.toString());
    }
}
