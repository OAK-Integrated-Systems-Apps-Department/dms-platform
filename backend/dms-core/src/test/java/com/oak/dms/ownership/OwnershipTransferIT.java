package com.oak.dms.ownership;

import com.oak.dms.department.entity.Department;
import com.oak.dms.user.User;
import com.oak.dms.workflow.support.WorkflowFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;

import java.sql.Connection;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** O3: transferring one document - the happy path, validation, D4 access, scope, recycle bin, lock timeout. */
@SpringBootTest
@Import(WorkflowFixtures.class)
class OwnershipTransferIT extends OwnershipApiSupport {

    private UUID tenantId;
    private UUID type;
    private User admin;
    private User owner;
    private User successor;
    private Department finance;
    private Department legal;

    @BeforeEach
    void world() {
        tenantId = fx.tenant();
        type = fx.documentType(tenantId, "Contract");
        admin = fx.user(tenantId, "Admin");
        owner = fx.user(tenantId, "Owner");
        successor = fx.user(tenantId, "Successor");
        finance = fx.department(tenantId, "Finance", owner);
        legal = fx.department(tenantId, "Legal", successor);
        fx.role(tenantId, "Viewers", new String[]{VIEW}, owner, successor);
    }

    private JsonNode transfer(User as, boolean tenantAdmin, UUID documentId, Object body, int status) throws Exception {
        return call(post("/api/v1/documents/" + documentId + "/ownership/transfer"),
                tenantAdmin ? fx.tenantAdminJwtFor(as) : fx.jwtFor(as), body, status);
    }

    private String snapshotOfUntouchedRows(UUID documentId) {
        return fx.jdbc().queryForObject("select md5(coalesce((select string_agg(v::text, '|' order by v.version_id)"
                + " from document_versions v where v.document_id = ?), '') || coalesce((select string_agg(r::text, '|'"
                + " order by r.relationship_id) from document_relationships r where r.source_document_id = ?"
                + " or r.target_document_id = ?), ''))", String.class, documentId, documentId, documentId);
    }

    @Test
    void transferMovesOwnerWritesHistoryAuditAndLeavesTheRestAlone() throws Exception {
        UUID documentId = fx.document(tenantId, type, owner, "Master agreement");
        UUID other = fx.document(tenantId, type, owner, "Other");
        UUID relType = UUID.randomUUID();
        fx.jdbc().update("insert into relationship_types (relationship_type_id, tenant_id, code, category, label, inverse_label,"
                + " directionality) values (?, ?, 'RELATED', 'association', 'Related', 'Related', 'symmetric')", relType, tenantId);
        fx.jdbc().update("insert into document_relationships (tenant_id, relationship_type_id, source_document_id,"
                + " target_document_id) values (?, ?, ?, ?)", tenantId, relType, documentId, other);
        String before = snapshotOfUntouchedRows(documentId);
        long versionsBefore = fx.count("select count(*) from document_versions where document_id = ?", documentId);

        JsonNode result = transfer(admin, true, documentId, body("newOwnerUserId", successor.getUserId(), "note", "Handover"), 200);

        assertThat(ownerOf(documentId)).isEqualTo(successor.getUserId().toString());
        assertThat(fx.jdbc().queryForObject("select owner_department_id from documents where document_id = ?", UUID.class,
                documentId)).isEqualTo(legal.getDepartmentId());
        assertThat(result.get("previousOwner").get("name").asString()).isEqualTo("Owner");
        assertThat(result.get("newOwner").get("name").asString()).isEqualTo("Successor");
        assertThat(result.get("newOwnerDepartment").get("name").asString()).isEqualTo("Legal");

        // history keeps the prior owner (and names as snapshots); audit and batch rows exist
        var row = fx.jdbc().queryForMap("select * from ownership_transfers where document_id = ?", documentId);
        assertThat(row.get("previous_owner_user_id")).isEqualTo(owner.getUserId());
        assertThat(row.get("previous_owner_department_id")).isEqualTo(finance.getDepartmentId());
        assertThat(row.get("new_owner_user_id")).isEqualTo(successor.getUserId());
        assertThat(row.get("actor_user_id")).isEqualTo(admin.getUserId());
        assertThat(row.get("reason")).isEqualTo("manual");
        assertThat(row.get("note")).isEqualTo("Handover");
        assertThat(row.get("previous_owner_name")).isEqualTo("Owner");
        assertThat(row.get("new_owner_name")).isEqualTo("Successor");
        assertThat(row.get("actor_name")).isEqualTo("Admin");
        assertThat(auditCount(tenantId, "DOCUMENT_OWNERSHIP_TRANSFERRED")).isEqualTo(1);
        assertThat(fx.count("select count(*) from ownership_transfer_batches where tenant_id = ? and kind = 'single'"
                + " and total_count = 1 and transferred_count = 1", tenantId)).isEqualTo(1);

        // D12: versions and relationships are untouched
        assertThat(snapshotOfUntouchedRows(documentId)).isEqualTo(before);
        assertThat(fx.count("select count(*) from document_versions where document_id = ?", documentId)).isEqualTo(versionsBefore);
        assertThat(fx.count("select count(*) from document_relationships where tenant_id = ?", tenantId)).isEqualTo(1);
        // the second document is untouched
        assertThat(ownerOf(other)).isEqualTo(owner.getUserId().toString());
    }

    @Test
    void secondTransferKeepsTheChain() throws Exception {
        UUID documentId = fx.document(tenantId, type, owner, "Chain");
        User third = fx.user(tenantId, "Third");
        transfer(admin, true, documentId, body("newOwnerUserId", successor.getUserId()), 200);
        transfer(admin, true, documentId, body("newOwnerUserId", third.getUserId()), 200);

        assertThat(historyRows(tenantId, documentId)).isEqualTo(2);
        var rows = fx.jdbc().queryForList("select previous_owner_user_id, new_owner_user_id from ownership_transfers"
                + " where document_id = ? order by transferred_at", documentId);
        assertThat(rows.get(0).get("previous_owner_user_id")).isEqualTo(owner.getUserId());
        assertThat(rows.get(1).get("previous_owner_user_id")).isEqualTo(successor.getUserId());
        assertThat(rows.get(1).get("new_owner_user_id")).isEqualTo(third.getUserId());
    }

    @Test
    void targetValidation() throws Exception {
        UUID documentId = fx.document(tenantId, type, owner, "Target checks");
        // missing
        transfer(admin, true, documentId, body(), 400);
        // unknown user
        transfer(admin, true, documentId, body("newOwnerUserId", UUID.randomUUID()), 422);
        // disabled
        User disabled = fx.user(tenantId, "Disabled");
        fx.jdbc().update("update users set status = 'disabled' where user_id = ?", disabled.getUserId());
        transfer(admin, true, documentId, body("newOwnerUserId", disabled.getUserId()), 422);
        // deactivated
        User gone = fx.user(tenantId, "Gone");
        fx.jdbc().update("update users set is_deleted = true, status = 'disabled' where user_id = ?", gone.getUserId());
        transfer(admin, true, documentId, body("newOwnerUserId", gone.getUserId()), 422);
        // another tenant
        UUID otherTenant = fx.tenant();
        User foreign = fx.user(otherTenant, "Foreign");
        JsonNode error = transfer(admin, true, documentId, body("newOwnerUserId", foreign.getUserId()), 422);
        assertThat(error.get("code").asString()).isEqualTo("OWNERSHIP_TARGET_INVALID");
        // inactive or foreign department
        Department foreignDept = fx.department(otherTenant, "Elsewhere");
        transfer(admin, true, documentId, body("newOwnerUserId", successor.getUserId(), "newOwnerDepartmentId",
                foreignDept.getDepartmentId()), 422);
        // unchanged
        transfer(admin, true, documentId, body("newOwnerUserId", owner.getUserId()), 409);

        assertThat(ownerOf(documentId)).isEqualTo(owner.getUserId().toString());
        assertThat(historyRows(tenantId, documentId)).isZero();
    }

    @Test
    void restrictedFolderDocumentBecomesOpenableAndNobodyLosesAccess() throws Exception {
        User colleague = fx.user(tenantId, "Colleague");
        fx.role(tenantId, "Viewers2", new String[]{VIEW}, colleague);
        UUID folder = folder(tenantId, "Board", true, "edit", owner, colleague);
        UUID documentId = fx.document(tenantId, type, owner, "Board minutes");
        file(documentId, folder);
        assertThat(canOpen(successor, documentId)).isFalse();
        assertThat(canOpen(owner, documentId)).isTrue();

        JsonNode result = transfer(admin, true, documentId, body("newOwnerUserId", successor.getUserId()), 200);

        assertThat(result.get("accessGranted").asBoolean()).isTrue();
        assertThat(result.get("accessConvertedToRestricted").asBoolean()).isTrue();
        assertThat(accessMode(documentId)).isEqualTo("restricted");
        assertThat(canOpen(successor, documentId)).isTrue();
        assertThat(canOpen(owner, documentId)).as("previous owner keeps access").isTrue();
        assertThat(canOpen(colleague, documentId)).as("folder members keep access").isTrue();
        // the folder's two entries were copied, plus the new owner
        assertThat(fx.count("select count(*) from document_access where document_id = ?", documentId)).isEqualTo(3);
        assertThat(fx.count("select count(*) from document_access where document_id = ? and principal_id = ?"
                + " and access_level = 'manage'", documentId, successor.getUserId())).isEqualTo(1);
        assertThat(fx.count("select count(*) from ownership_transfers where document_id = ? and access_granted", documentId))
                .isEqualTo(1);
    }

    @Test
    void withoutTheAccessOptionTheNewOwnerStaysOutButThePreviousOwnerKeepsAccess() throws Exception {
        UUID folder = folder(tenantId, "Closed", true, "edit", owner);
        UUID documentId = fx.document(tenantId, type, owner, "Closed doc");
        file(documentId, folder);

        JsonNode result = transfer(admin, true, documentId,
                body("newOwnerUserId", successor.getUserId(), "grantAccess", false), 200);

        assertThat(result.get("accessGranted").asBoolean()).isFalse();
        assertThat(accessMode(documentId)).isEqualTo("inherit");
        assertThat(canOpen(successor, documentId)).isFalse();
        assertThat(canOpen(owner, documentId)).isTrue();
        assertThat(fx.count("select count(*) from ownership_transfers where document_id = ? and not access_granted", documentId))
                .isEqualTo(1);
    }

    @Test
    void documentWithItsOwnListGetsAManageEntryAndStaysRestricted() throws Exception {
        UUID documentId = fx.document(tenantId, type, owner, "Private");
        restrictTo(tenantId, documentId, "edit", owner);

        JsonNode result = transfer(admin, true, documentId, body("newOwnerUserId", successor.getUserId()), 200);

        assertThat(result.get("accessGranted").asBoolean()).isTrue();
        assertThat(result.get("accessConvertedToRestricted").asBoolean()).isFalse();
        assertThat(accessMode(documentId)).isEqualTo("restricted");
        assertThat(canOpen(successor, documentId)).isTrue();
        assertThat(canOpen(owner, documentId)).isTrue();
        assertThat(fx.count("select count(*) from document_access where document_id = ?", documentId)).isEqualTo(2);
    }

    @Test
    void anOpenDocumentNeedsNoGrant() throws Exception {
        UUID documentId = fx.document(tenantId, type, owner, "Open");
        JsonNode result = transfer(admin, true, documentId, body("newOwnerUserId", successor.getUserId()), 200);
        assertThat(result.get("accessGranted").asBoolean()).isFalse();
        assertThat(accessMode(documentId)).isEqualTo("inherit");
        assertThat(fx.count("select count(*) from document_access where document_id = ?", documentId)).isZero();
    }

    @Test
    void previewSaysWhatWouldHappenAndWritesNothing() throws Exception {
        UUID folder = folder(tenantId, "Closed", true, "edit", owner);
        UUID closed = fx.document(tenantId, type, owner, "Closed");
        file(closed, folder);
        UUID open = fx.document(tenantId, type, owner, "Open");

        JsonNode withConversion = call(post("/api/v1/documents/" + closed + "/ownership/transfer/preview"),
                fx.tenantAdminJwtFor(admin), body("newOwnerUserId", successor.getUserId()), 200);
        JsonNode plain = call(post("/api/v1/documents/" + open + "/ownership/transfer/preview"),
                fx.tenantAdminJwtFor(admin), body("newOwnerUserId", successor.getUserId()), 200);
        JsonNode noGrant = call(post("/api/v1/documents/" + closed + "/ownership/transfer/preview"),
                fx.tenantAdminJwtFor(admin), body("newOwnerUserId", successor.getUserId(), "grantAccess", false), 200);

        assertThat(withConversion.get("willConvertToRestricted").asBoolean()).isTrue();
        assertThat(withConversion.get("newOwnerCanOpenAlready").asBoolean()).isFalse();
        assertThat(plain.get("willConvertToRestricted").asBoolean()).isFalse();
        assertThat(plain.get("newOwnerCanOpenAlready").asBoolean()).isTrue();
        assertThat(noGrant.get("willConvertToRestricted").asBoolean()).isFalse();
        assertThat(ownerOf(closed)).isEqualTo(owner.getUserId().toString());
        assertThat(accessMode(closed)).isEqualTo("inherit");
        assertThat(fx.count("select count(*) from ownership_transfers where tenant_id = ?", tenantId)).isZero();
    }

    @Test
    void recycleBinDocumentIsTransferredAndPurgedOneIsNotFound() throws Exception {
        UUID binned = fx.document(tenantId, type, owner, "In the bin");
        fx.jdbc().update("update documents set is_deleted = true, deleted_at = now() where document_id = ?", binned);
        UUID purged = fx.document(tenantId, type, owner, "Purged");
        fx.jdbc().update("update documents set is_deleted = true, deleted_at = now(), purged_at = now() where document_id = ?", purged);

        transfer(admin, true, binned, body("newOwnerUserId", successor.getUserId()), 200);
        transfer(admin, true, purged, body("newOwnerUserId", successor.getUserId()), 404);

        assertThat(ownerOf(binned)).isEqualTo(successor.getUserId().toString());
        assertThat(ownerOf(purged)).isEqualTo(owner.getUserId().toString());
        assertThat(historyRows(tenantId, purged)).isZero();
    }

    @Test
    void managerOfTheOwnersDepartmentCanTransferButOthersCannot() throws Exception {
        User manager = fx.user(tenantId, "Manager");
        fx.department(tenantId, "Elsewhere");
        makeHead(finance.getDepartmentId(), manager);
        User outsiderHead = fx.user(tenantId, "OutsiderHead");
        makeHead(legal.getDepartmentId(), outsiderHead);
        User plain = fx.user(tenantId, "Plain");
        for (User u : new User[]{manager, outsiderHead, plain}) {
            fx.role(tenantId, "Dot " + u.getFirstName(), new String[]{INITIATE, VIEW}, u);
        }
        UUID documentId = fx.document(tenantId, type, owner, "Finance doc");

        transfer(plain, false, documentId, body("newOwnerUserId", successor.getUserId()), 403);
        transfer(outsiderHead, false, documentId, body("newOwnerUserId", successor.getUserId()), 403);
        assertThat(ownerOf(documentId)).isEqualTo(owner.getUserId().toString());
        transfer(manager, false, documentId, body("newOwnerUserId", successor.getUserId()), 200);
        assertThat(ownerOf(documentId)).isEqualTo(successor.getUserId().toString());
    }

    @Test
    void managerCannotTransferAnOwnedDocumentTheyCannotOpen() throws Exception {
        User manager = fx.user(tenantId, "Manager");
        makeHead(finance.getDepartmentId(), manager);
        fx.role(tenantId, "Dot manager", new String[]{INITIATE, VIEW}, manager);
        UUID documentId = fx.document(tenantId, type, owner, "Hidden from manager");
        restrictTo(tenantId, documentId, "edit", owner);

        JsonNode error = transfer(manager, false, documentId, body("newOwnerUserId", successor.getUserId()), 404);

        assertThat(error.toString()).doesNotContain("Hidden from manager");
        assertThat(ownerOf(documentId)).isEqualTo(owner.getUserId().toString());
    }

    @Test
    void withoutThePermissionOrFromAnotherTenantTheAnswerIsRefusedOrNotFound() throws Exception {
        UUID documentId = fx.document(tenantId, type, owner, "Guarded");
        User nobody = fx.user(tenantId, "Nobody");
        transfer(nobody, false, documentId, body("newOwnerUserId", successor.getUserId()), 403);
        // approve-transfer alone grants nothing (D2)
        User approver = fx.user(tenantId, "Approver");
        fx.role(tenantId, "Approver", new String[]{APPROVE}, approver);
        transfer(approver, false, documentId, body("newOwnerUserId", successor.getUserId()), 403);

        UUID otherTenant = fx.tenant();
        User otherAdmin = fx.user(otherTenant, "OtherAdmin");
        User otherTarget = fx.user(otherTenant, "OtherTarget");
        transfer(otherAdmin, true, documentId, body("newOwnerUserId", otherTarget.getUserId()), 404);
        assertThat(ownerOf(documentId)).isEqualTo(owner.getUserId().toString());
    }

    @Test
    void aLockedDocumentTimesOutWith409AndChangesNothing() throws Exception {
        UUID documentId = fx.document(tenantId, type, owner, "Busy");
        try (Connection holder = fx.jdbc().getDataSource().getConnection()) {
            holder.setAutoCommit(false);
            try (var st = holder.createStatement()) {
                st.execute("select 1 from documents where document_id = '" + documentId + "' for update");
            }
            JsonNode error = transfer(admin, true, documentId, body("newOwnerUserId", successor.getUserId()), 409);
            assertThat(error.get("code").asString()).isEqualTo("CONCURRENT_UPDATE");
            holder.rollback();
        }
        assertThat(ownerOf(documentId)).isEqualTo(owner.getUserId().toString());
        assertThat(historyRows(tenantId, documentId)).isZero();
        assertThat(auditCount(tenantId, "DOCUMENT_OWNERSHIP_TRANSFERRED")).isZero();
    }
}
