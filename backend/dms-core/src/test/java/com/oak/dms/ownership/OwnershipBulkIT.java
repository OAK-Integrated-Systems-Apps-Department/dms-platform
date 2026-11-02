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
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** O4: the review list and bulk/selective transfer - totals, skips, Recycle Bin, the 5,000 rule, chunks, managers, preview. */
@SpringBootTest
@Import(WorkflowFixtures.class)
class OwnershipBulkIT extends OwnershipApiSupport {

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

    private JsonNode transfer(User as, boolean tenantAdmin, Object body, int status) throws Exception {
        return call(post("/api/v1/ownership/transfers"), tenantAdmin ? fx.tenantAdminJwtFor(as) : fx.jwtFor(as), body, status);
    }

    private JsonNode list(User as, boolean tenantAdmin, String query, int status) throws Exception {
        return call(get("/api/v1/ownership/documents?" + query), tenantAdmin ? fx.tenantAdminJwtFor(as) : fx.jwtFor(as), null, status);
    }

    private JsonNode skipped(JsonNode summary, String reason) {
        for (JsonNode group : summary.get("skipped")) {
            if (reason.equals(group.get("reason").asString())) {
                return group;
            }
        }
        return null;
    }

    private List<UUID> docs(int n, String prefix) {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            ids.add(fx.document(tenantId, type, owner, prefix + i));
        }
        return ids;
    }

    @Test
    void transferAllMovesEverythingIncludingTheRecycleBinAndSkipsPurged() throws Exception {
        List<UUID> live = docs(3, "Live ");
        UUID binned = fx.document(tenantId, type, owner, "Binned");
        fx.jdbc().update("update documents set is_deleted = true, deleted_at = now() where document_id = ?", binned);
        UUID purged = fx.document(tenantId, type, owner, "Purged");
        fx.jdbc().update("update documents set is_deleted = true, deleted_at = now(), purged_at = now() where document_id = ?", purged);

        JsonNode summary = transfer(admin, true, allOf(owner, successor), 200);

        assertThat(summary.get("totalCount").asInt()).isEqualTo(4);
        assertThat(summary.get("transferredCount").asInt()).isEqualTo(4);
        assertThat(summary.get("skippedCount").asInt()).isZero();
        assertThat(summary.get("kind").asString()).isEqualTo("bulk");
        for (UUID id : live) {
            assertThat(ownerOf(id)).isEqualTo(successor.getUserId().toString());
        }
        assertThat(ownerOf(binned)).isEqualTo(successor.getUserId().toString());
        assertThat(ownerOf(purged)).as("a purged tombstone is left alone").isEqualTo(owner.getUserId().toString());
        // history: one row per document with the previous owner; one audit event for the whole batch
        assertThat(fx.count("select count(*) from ownership_transfers where tenant_id = ? and batch_id = ?::uuid"
                + " and previous_owner_user_id = ?", tenantId, summary.get("batchId").asString(), owner.getUserId())).isEqualTo(4);
        assertThat(auditCount(tenantId, "OWNERSHIP_BATCH_TRANSFERRED")).isEqualTo(1);
        assertThat(auditCount(tenantId, "DOCUMENT_OWNERSHIP_TRANSFERRED")).isZero();

        // the batch endpoint tells the same story
        JsonNode again = call(get("/api/v1/ownership/batches/" + summary.get("batchId").asString()),
                fx.tenantAdminJwtFor(admin), null, 200);
        assertThat(again.get("transferredCount").asInt()).isEqualTo(4);
        assertThat(again.get("newOwner").get("name").asString()).isEqualTo("Successor");
    }

    @Test
    void transferAllWithPurgedOnlyDocumentsIsAnEmptyRunNotAnError() throws Exception {
        UUID purged = fx.document(tenantId, type, owner, "Purged");
        fx.jdbc().update("update documents set is_deleted = true, purged_at = now() where document_id = ?", purged);
        JsonNode summary = transfer(admin, true, allOf(owner, successor), 200);
        assertThat(summary.get("totalCount").asInt()).isZero();
        assertThat(auditCount(tenantId, "OWNERSHIP_BATCH_TRANSFERRED")).isZero();
    }

    @Test
    void selectiveTransferMovesOnlyTheChosenOnesAndReportsSkips() throws Exception {
        List<UUID> docs = docs(5, "Doc ");
        UUID alreadyThere = fx.document(tenantId, type, successor, "Already successor's");
        UUID purged = fx.document(tenantId, type, owner, "Purged");
        fx.jdbc().update("update documents set is_deleted = true, purged_at = now() where document_id = ?", purged);
        UUID foreignTenant = fx.tenant();
        UUID foreignDoc = fx.document(foreignTenant, fx.documentType(foreignTenant, "X"), fx.user(foreignTenant, "F"), "Foreign");
        UUID unknown = UUID.randomUUID();
        List<UUID> chosen = new ArrayList<>(docs.subList(0, 2));
        chosen.addAll(List.of(alreadyThere, purged, foreignDoc, unknown));

        JsonNode summary = transfer(admin, true, selected(chosen, successor), 200);

        assertThat(summary.get("totalCount").asInt()).isEqualTo(6);
        assertThat(summary.get("transferredCount").asInt()).isEqualTo(2);
        assertThat(skipped(summary, "already_owner").get("count").asInt()).isEqualTo(1);
        assertThat(skipped(summary, "purged").get("count").asInt()).isEqualTo(1);
        JsonNode notFound = skipped(summary, "not_found");
        assertThat(notFound.get("count").asInt()).as("foreign tenant and unknown ids look the same").isEqualTo(2);
        assertThat(notFound.get("documentIds")).isEmpty();
        assertThat(ownerOf(docs.get(0))).isEqualTo(successor.getUserId().toString());
        assertThat(ownerOf(docs.get(1))).isEqualTo(successor.getUserId().toString());
        for (int i = 2; i < 5; i++) {
            assertThat(ownerOf(docs.get(i))).isEqualTo(owner.getUserId().toString());
        }
        assertThat(fx.count("select count(*) from documents where document_id = ? and owner_user_id <> ?",
                foreignDoc, successor.getUserId())).as("the other tenant's document is untouched").isEqualTo(1);
    }

    @Test
    void excludeListLeavesDocumentsBehind() throws Exception {
        List<UUID> docs = docs(4, "Doc ");
        JsonNode summary = transfer(admin, true, allOf(owner, successor, "excludeDocumentIds", List.of(docs.get(0), docs.get(1))), 200);
        assertThat(summary.get("transferredCount").asInt()).isEqualTo(2);
        assertThat(ownerOf(docs.get(0))).isEqualTo(owner.getUserId().toString());
        assertThat(ownerOf(docs.get(3))).isEqualTo(successor.getUserId().toString());
    }

    @Test
    void reorganisationTakesTheDepartmentsDocuments() throws Exception {
        List<UUID> docs = docs(3, "Fin ");
        User colleague = fx.user(tenantId, "Colleague");
        fx.department(tenantId, "Ops", colleague);
        UUID opsDoc = fx.document(tenantId, type, colleague, "Ops doc");
        Set<UUID> expected = Set.copyOf(docs);

        JsonNode summary = transfer(admin, true, body("sourceDepartmentId", finance.getDepartmentId(), "mode", "all",
                "reason", "reorganization", "target", body("type", "user", "newOwnerUserId", successor.getUserId(),
                        "newOwnerDepartmentId", legal.getDepartmentId())), 200);

        assertThat(summary.get("kind").asString()).isEqualTo("reorganization");
        assertThat(summary.get("transferredCount").asInt()).isEqualTo(3);
        for (UUID id : expected) {
            assertThat(ownerOf(id)).isEqualTo(successor.getUserId().toString());
        }
        assertThat(ownerOf(opsDoc)).isEqualTo(colleague.getUserId().toString());
        assertThat(fx.count("select count(*) from ownership_transfers where tenant_id = ? and reason = 'reorganization'", tenantId))
                .isEqualTo(3);
    }

    @Test
    void reviewListShowsFlagsAndFilters() throws Exception {
        docs(2, "Alpha ");
        UUID binned = fx.document(tenantId, type, owner, "Beta binned");
        fx.jdbc().update("update documents set is_deleted = true, deleted_at = now() where document_id = ?", binned);
        UUID purged = fx.document(tenantId, type, owner, "Gamma purged");
        fx.jdbc().update("update documents set is_deleted = true, purged_at = now() where document_id = ?", purged);

        JsonNode all = list(admin, true, "userId=" + owner.getUserId(), 200);
        assertThat(all.get("total").asInt()).isEqualTo(3);
        assertThat(all.get("hiddenCount").asInt()).isZero();
        boolean sawBin = false;
        for (JsonNode item : all.get("items")) {
            assertThat(item.get("owner").get("name").asString()).isEqualTo("Owner");
            assertThat(item.get("ownerDepartment").get("name").asString()).isEqualTo("Finance");
            sawBin |= item.get("inRecycleBin").asBoolean();
        }
        assertThat(sawBin).isTrue();
        assertThat(list(admin, true, "userId=" + owner.getUserId() + "&q=alpha", 200).get("total").asInt()).isEqualTo(2);
        assertThat(list(admin, true, "userId=" + owner.getUserId() + "&recycleBin=true", 200).get("total").asInt()).isEqualTo(1);
        assertThat(list(admin, true, "departmentId=" + finance.getDepartmentId(), 200).get("total").asInt()).isEqualTo(3);
        list(admin, true, "", 422);
    }

    @Test
    void moreThanFiveThousandIsRefusedAndTheLimitSplitsIt() throws Exception {
        UUID ref = versionOf(fx.document(tenantId, type, owner, "Seed"));
        bulkDocuments(tenantId, type, owner, ref, 5000, "Bulk");   // 5,001 with the seed
        assertThat(ownedBy(owner)).isEqualTo(5001);

        JsonNode refused = transfer(admin, true, allOf(owner, successor), 422);
        assertThat(refused.get("code").asString()).isEqualTo("OWNERSHIP_BATCH_TOO_LARGE");
        assertThat(refused.get("message").asString()).contains("5000").contains("several steps");
        assertThat(ownedBy(owner)).isEqualTo(5001);

        JsonNode first = transfer(admin, true, allOf(owner, successor, "limit", 5000), 200);
        assertThat(first.get("transferredCount").asInt()).isEqualTo(5000);
        JsonNode second = transfer(admin, true, allOf(owner, successor, "limit", 5000), 200);
        assertThat(second.get("transferredCount").asInt()).isEqualTo(1);
        assertThat(ownedBy(owner)).isZero();
        assertThat(fx.count("select count(*) from ownership_transfers where tenant_id = ?", tenantId)).isEqualTo(5001);

        // a selection above the limit is refused up front too
        List<UUID> many = new ArrayList<>();
        for (int i = 0; i < 5001; i++) {
            many.add(UUID.randomUUID());
        }
        transfer(admin, true, selected(many, owner), 422);
    }

    @Test
    void chunkBoundariesLoseNothing() throws Exception {
        for (int n : new int[]{499, 500, 501, 1000}) {
            UUID t = fx.tenant();
            UUID ty = fx.documentType(t, "C");
            User o = fx.user(t, "Owner" + n);
            User s = fx.user(t, "Succ" + n);
            User a = fx.user(t, "Admin" + n);
            UUID ref = versionOf(fx.document(t, ty, o, "Seed"));
            bulkDocuments(t, ty, o, ref, n - 1, "Chunk");
            JsonNode summary = call(post("/api/v1/ownership/transfers"), fx.tenantAdminJwtFor(a), allOf(o, s), 200);
            assertThat(summary.get("transferredCount").asInt()).as("n=" + n).isEqualTo(n);
            assertThat(fx.count("select count(*) from ownership_transfers where tenant_id = ?", t)).isEqualTo(n);
            assertThat(ownedBy(o)).isZero();
            assertThat(ownedBy(s)).isEqualTo(n);
        }
    }

    @Test
    void managerSeesAndMovesOnlyWhatTheyCanOpenInTheirDepartment() throws Exception {
        User manager = fx.user(tenantId, "Manager");
        makeHead(finance.getDepartmentId(), manager);
        fx.role(tenantId, "Dot manager", new String[]{SELECT, EXECUTE, INITIATE, VIEW}, manager);
        UUID open1 = fx.document(tenantId, type, owner, "Open one");
        UUID open2 = fx.document(tenantId, type, owner, "Open two");
        UUID secret = fx.document(tenantId, type, owner, "Secret salary review");
        restrictTo(tenantId, secret, "edit", owner);

        JsonNode listed = list(manager, false, "userId=" + owner.getUserId(), 200);
        assertThat(listed.get("total").asInt()).isEqualTo(2);
        assertThat(listed.get("hiddenCount").asInt()).isEqualTo(1);
        assertThat(listed.get("totalOwned").asInt()).isEqualTo(3);
        assertThat(listed.toString()).doesNotContain("Secret salary review").doesNotContain(secret.toString());

        // someone from another department is out of scope; a plain DOT holder heads nothing
        list(manager, false, "userId=" + successor.getUserId(), 403);
        User plain = fx.user(tenantId, "Plain");
        fx.role(tenantId, "Dot plain", new String[]{SELECT, EXECUTE, VIEW}, plain);
        list(plain, false, "userId=" + owner.getUserId(), 403);
        transfer(plain, false, allOf(owner, successor), 403);

        JsonNode summary = transfer(manager, false, allOf(owner, successor), 200);
        assertThat(summary.get("transferredCount").asInt()).isEqualTo(2);
        assertThat(ownerOf(open1)).isEqualTo(successor.getUserId().toString());
        assertThat(ownerOf(open2)).isEqualTo(successor.getUserId().toString());
        assertThat(ownerOf(secret)).as("the manager can't open it, so it stays").isEqualTo(owner.getUserId().toString());

        // naming the hidden document in a selection is the same as naming one that doesn't exist
        JsonNode hidden = transfer(manager, false, selected(List.of(secret), successor), 200);
        assertThat(hidden.get("transferredCount").asInt()).isZero();
        assertThat(skipped(hidden, "not_found").get("count").asInt()).isEqualTo(1);
        assertThat(skipped(hidden, "not_found").get("documentIds")).isEmpty();
        assertThat(hidden.toString()).doesNotContain("Secret").doesNotContain(secret.toString());
    }

    @Test
    void batchSummaryIsForTheCreatorOrAnAdminAndNotForAnotherTenant() throws Exception {
        docs(1, "One ");
        JsonNode summary = transfer(admin, true, allOf(owner, successor), 200);
        String path = "/api/v1/ownership/batches/" + summary.get("batchId").asString();
        User other = fx.user(tenantId, "Other");
        fx.role(tenantId, "Dot other", new String[]{SELECT, VIEW}, other);
        call(get(path), fx.jwtFor(other), null, 404);
        UUID otherTenant = fx.tenant();
        call(get(path), fx.tenantAdminJwtFor(fx.user(otherTenant, "OtherAdmin")), null, 404);
        call(get(path), fx.tenantAdminJwtFor(admin), null, 200);
        call(get("/api/v1/ownership/batches/" + UUID.randomUUID()), fx.tenantAdminJwtFor(admin), null, 404);
    }

    @Test
    void previewCountsWhatWouldHappenAndWritesNothing() throws Exception {
        User colleague = fx.user(tenantId, "Colleague");
        UUID folder = folder(tenantId, "Closed", true, "edit", owner, colleague);
        UUID closed1 = fx.document(tenantId, type, owner, "Closed one");
        UUID closed2 = fx.document(tenantId, type, owner, "Closed two");
        file(closed1, folder);
        file(closed2, folder);
        UUID own = fx.document(tenantId, type, owner, "Own list");
        restrictTo(tenantId, own, "edit", owner);
        UUID open = fx.document(tenantId, type, owner, "Open");
        UUID alreadyThere = fx.document(tenantId, type, successor, "Successor's");

        JsonNode preview = call(post("/api/v1/ownership/transfers/preview"), fx.tenantAdminJwtFor(admin),
                selected(List.of(closed1, closed2, own, open, alreadyThere), successor), 200);

        assertThat(preview.get("totalCount").asInt()).isEqualTo(5);
        assertThat(preview.get("transferableCount").asInt()).isEqualTo(4);
        assertThat(preview.get("willConvertToRestrictedCount").asInt()).isEqualTo(2);
        assertThat(preview.get("willAddAccessEntryCount").asInt()).isEqualTo(1);
        assertThat(skipped(preview, "already_owner").get("count").asInt()).isEqualTo(1);
        JsonNode noGrant = call(post("/api/v1/ownership/transfers/preview"), fx.tenantAdminJwtFor(admin),
                selected(List.of(closed1, closed2, own, open), successor, "grantAccess", false), 200);
        assertThat(noGrant.get("willConvertToRestrictedCount").asInt()).isZero();
        assertThat(fx.count("select count(*) from ownership_transfers where tenant_id = ?", tenantId)).isZero();
        assertThat(accessMode(closed1)).isEqualTo("inherit");
        assertThat(ownerOf(open)).isEqualTo(owner.getUserId().toString());

        // and the real run reports the same number in its results
        JsonNode summary = transfer(admin, true, selected(List.of(closed1, closed2, own, open), successor), 200);
        assertThat(summary.get("convertedToRestrictedCount").asInt()).isEqualTo(2);
        assertThat(summary.get("accessGrantedCount").asInt()).isEqualTo(3);
        assertThat(accessMode(closed1)).isEqualTo("restricted");
        assertThat(canOpen(successor, closed1)).isTrue();
        assertThat(fx.count("select count(*) from document_access where document_id = ? and principal_id = ?",
                closed1, colleague.getUserId())).as("the folder's entries were copied").isEqualTo(1);
    }

    @Test
    void invalidRequestsAreRefused() throws Exception {
        transfer(admin, true, body("mode", "all", "target", body("type", "user", "newOwnerUserId", successor.getUserId())), 422);
        transfer(admin, true, body("mode", "sideways", "sourceUserId", owner.getUserId(),
                "target", body("type", "user", "newOwnerUserId", successor.getUserId())), 422);
        transfer(admin, true, body("mode", "selected", "documentIds", List.of(),
                "target", body("type", "user", "newOwnerUserId", successor.getUserId())), 422);
        transfer(admin, true, allOf(owner, successor, "reason", "offboarding"), 422);
        transfer(admin, true, allOf(owner, successor, "limit", 0), 422);
        User nobody = fx.user(tenantId, "Nobody");
        transfer(nobody, false, allOf(owner, successor), 403);
        list(nobody, false, "userId=" + owner.getUserId(), 403);
    }
}
