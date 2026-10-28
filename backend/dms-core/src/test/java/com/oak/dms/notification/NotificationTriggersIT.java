package com.oak.dms.notification;

import com.oak.dms.notification.service.NotificationRequest;
import com.oak.dms.notification.service.NotificationService;
import com.oak.dms.security.rbac.entity.Role;
import com.oak.dms.user.User;
import com.oak.dms.workflow.support.WorkflowApiSupport;
import com.oak.dms.workflow.support.WorkflowFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * N9 (access-aware content) and N10 (the new triggers), end to end through the
 * real flows: the workflow API, comments, and the access-list endpoints.
 * A recipient who can't open a document never gets its title or a link to it;
 * a workflow participant, who can read it while their task is open, does.
 */
@SpringBootTest
@Import(WorkflowFixtures.class)
class NotificationTriggersIT extends WorkflowApiSupport {

    @Autowired
    private NotificationService notificationService;

    private UUID tenant;
    private UUID typeId;
    private User admin;
    private User owner;
    private User initiator;
    private User approver;
    private User outsider;

    @BeforeEach
    void world() {
        tenant = fx.tenant();
        admin = fx.user(tenant, "Admin");
        owner = fx.user(tenant, "Owner");
        initiator = fx.user(tenant, "Initiator");
        approver = fx.user(tenant, "Approver");
        outsider = fx.user(tenant, "Outsider");
        fx.role(tenant, "Submitters", new String[]{WorkflowFixtures.SUBMIT_PERMISSION, "docm.documents.view",
                "docm.document-comments.create"}, owner, initiator);
        fx.role(tenant, "Approvers", WorkflowFixtures.APPROVER_PERMISSIONS, approver);
        typeId = fx.documentType(tenant, "Minutes");
    }

    /** Gives the document its own access list naming only {@code manager} - nobody else can open it. */
    private UUID restricted(UUID documentId, User manager) {
        fx.jdbc().update("update documents set access_mode = 'restricted' where document_id = ?", documentId);
        fx.jdbc().update("insert into document_access (tenant_id, document_id, principal_type, principal_id,"
                + " access_level) values (?, ?, 'user', ?, 'manage')", tenant, documentId, manager.getUserId());
        return documentId;
    }

    private List<Map<String, Object>> rows(User user, String category) {
        return fx.jdbc().queryForList("select title, body, link_url, object_id from notifications"
                + " where recipient_user_id = ? and category_code = ? order by created_at", user.getUserId(), category);
    }

    private Map<String, Object> only(User user, String category) {
        List<Map<String, Object>> found = rows(user, category);
        assertThat(found).as(user.getDisplayName() + " / " + category).hasSize(1);
        return found.get(0);
    }

    private void singleStageTemplate(String code, String onDocumentChange) throws Exception {
        activeTemplate(admin, code, typeId, onDocumentChange, List.of(
                stageDef("Review", "any", "halt_immediately", null, entry("user", approver.getUserId()))));
    }

    // ------------------------------------------------------------------ N9

    @Test
    void access_titleAndLinkOnlyForThoseWhoCanOpenTheDocument() {
        UUID doc = restricted(fx.document(tenant, typeId, owner, "Board minutes"), owner);

        notificationService.notify(NotificationRequest.builder()
                .tenantId(tenant)
                .recipientUserIds(List.of(owner.getUserId(), outsider.getUserId()))
                .categoryCode("module.info")
                .title("Board minutes were updated").body("Open \"Board minutes\".").linkUrl("/documents/" + doc)
                .documentId(doc)
                .neutralTitle("A document was updated").neutralBody("A document you can't open was updated.")
                .objectType("document").objectId(doc).sourceModule("test")
                .build());

        Map<String, Object> mine = only(owner, "module.info");
        assertThat(mine.get("title")).isEqualTo("Board minutes were updated");
        assertThat(mine.get("link_url")).isEqualTo("/documents/" + doc);
        assertThat(mine.get("object_id")).isEqualTo(doc);

        Map<String, Object> theirs = only(outsider, "module.info");
        assertThat(theirs.get("title")).isEqualTo("A document was updated");
        assertThat(theirs.get("body").toString()).doesNotContain("Board minutes");
        assertThat(theirs.get("link_url")).isNull();
        assertThat(theirs.get("object_id")).as("no reference to the document either").isNull();
    }

    @Test
    void theViewPermission_isRequiredOnlyWhenAskedFor() {
        // No access list at all, so the ACL lets everyone in; `outsider` holds no permissions.
        UUID doc = fx.document(tenant, typeId, owner, "Open handbook");

        for (boolean requirePermission : new boolean[]{false, true}) {
            notificationService.notify(NotificationRequest.builder()
                    .tenantId(tenant)
                    .recipientUserIds(List.of(owner.getUserId(), outsider.getUserId()))
                    .categoryCode("module.info")
                    .title("Handbook " + requirePermission).body("Open \"Open handbook\".")
                    .linkUrl("/documents/" + doc)
                    .documentId(doc).requireViewPermission(requirePermission)
                    .neutralTitle("A document " + requirePermission).neutralBody("A document changed.")
                    .objectType("document").objectId(doc).sourceModule("test")
                    .build());
        }

        // Access check alone (workflow notifications): the ACL is open, so everyone gets the title.
        assertThat(titles(outsider)).contains("Handbook false").doesNotContain("A document false");
        // With the permission required (mentions): the outsider has none, so they get the neutral version.
        assertThat(titles(outsider)).contains("A document true").doesNotContain("Handbook true");
        assertThat(titles(owner)).contains("Handbook true");
    }

    private List<String> titles(User user) {
        return rows(user, "module.info").stream().map(r -> r.get("title").toString()).toList();
    }

    @Test
    void workflowParticipant_whoCanReadItWhileTheirTaskIsOpen_getsTheTitleAndLink() throws Exception {
        UUID doc = restricted(fx.document(tenant, typeId, owner, "Board minutes"), owner);
        singleStageTemplate("MIN", "flag_only");

        submit(owner, doc, 201);

        // The approver is not on the document's list, but the approval is waiting on them (workflow-scoped read).
        Map<String, Object> assigned = only(approver, "workflow.task.assigned");
        assertThat(assigned.get("title")).isEqualTo("Approval needed: Board minutes");
        assertThat(assigned.get("link_url")).isEqualTo("/documents/" + doc);
    }

    @Test
    void mention_ofSomeoneWithoutAccess_keepsTheComment_butNotTheTitleOrADocumentLink() throws Exception {
        UUID doc = restricted(fx.document(tenant, typeId, owner, "Board minutes"), owner);

        postAs("/api/v1/documents/" + doc + "/comments", owner,
                Map.of("body", "Please see @[Outsider](user:" + outsider.getUserId() + ")"), 201);

        Map<String, Object> mention = only(outsider, "comment.mention");
        assertThat(mention.get("title")).isEqualTo("Owner mentioned you in a comment");
        assertThat(mention.get("body").toString()).contains("Please see @Outsider").doesNotContain("Board minutes");
        assertThat(mention.get("link_url")).isEqualTo("/mentions");
        assertThat(mention.get("object_id")).isNull();
    }

    // ------------------------------------------------------------------ N10

    @Test
    void statusChange_notifiesTheOwner_inAppOnly_andNotWhoStartedIt() throws Exception {
        UUID doc = fx.document(tenant, typeId, owner, "Budget 2027");
        singleStageTemplate("BUD", "flag_only");
        JsonNode instance = submit(initiator, doc, 201);

        reject(approver, taskId(instance, 1, approver.getDisplayName()), "The numbers are off", 200);

        Map<String, Object> status = only(owner, "document.status-changed");
        assertThat(status.get("title")).isEqualTo("Document rejected: Budget 2027");
        // The person who started it gets the outcome notification, not this one.
        assertThat(rows(initiator, "document.status-changed")).isEmpty();
        assertThat(rows(initiator, "workflow.outcome")).hasSize(1);
        // Optional tier: email is off by default, so only the in-app entry is delivered.
        assertThat(fx.count("select count(*) from notification_deliveries d join notifications n"
                + " on n.notification_id = d.notification_id where n.recipient_user_id = ?"
                + " and n.category_code = 'document.status-changed' and d.channel = 'email'"
                + " and d.status = 'skipped' and d.skip_reason = 'email_disabled'", owner.getUserId())).isEqualTo(1);
    }

    @Test
    void newVersionMidApproval_notifiesThePeopleItIsWaitingOn_notTheUploader() throws Exception {
        UUID doc = fx.document(tenant, typeId, owner, "Policy");
        singleStageTemplate("POL", "flag_only");
        submit(initiator, doc, 201);

        fx.newVersion(tenant, doc, owner);

        Map<String, Object> version = only(approver, "workflow.new-version");
        assertThat(version.get("title")).isEqualTo("New version uploaded during approval: Policy");
        assertThat(version.get("body").toString()).contains("Owner uploaded a new version of \"Policy\"");
        assertThat(version.get("link_url")).isEqualTo("/documents/" + doc);
        assertThat(rows(owner, "workflow.new-version")).as("the uploader is not told").isEmpty();
    }

    @Test
    void sharingADocument_tellsOnlyTheNewlyAddedPeople_andRolesExpandToTheirMembers() throws Exception {
        UUID doc = fx.document(tenant, typeId, owner, "Contract");
        User grantee = fx.user(tenant, "Grantee");
        User later = fx.user(tenant, "Later");
        User memberA = fx.user(tenant, "MemberA");
        User memberB = fx.user(tenant, "MemberB");
        Role reviewers = fx.role(tenant, "Reviewers", new String[0], memberA, memberB);

        adminPut("/api/v1/documents/" + doc + "/access", admin, restrictedTo(
                entry("user", admin.getUserId(), "manage"), entry("user", grantee.getUserId(), "view")), 200);
        Map<String, Object> shared = only(grantee, "document.shared");
        assertThat(shared.get("title")).isEqualTo("Admin shared a document with you: Contract");
        assertThat(shared.get("link_url")).isEqualTo("/documents/" + doc);
        assertThat(rows(admin, "document.shared")).as("the sharer is not told").isEmpty();

        // Saving again with one more person tells only that person - and expands a role to its members.
        adminPut("/api/v1/documents/" + doc + "/access", admin, restrictedTo(
                entry("user", admin.getUserId(), "manage"), entry("user", grantee.getUserId(), "edit"),
                entry("user", later.getUserId(), "view"), entry("role", reviewers.getRoleId(), "view")), 200);
        assertThat(rows(grantee, "document.shared")).as("a level change is not a new share").hasSize(1);
        assertThat(rows(later, "document.shared")).hasSize(1);
        assertThat(rows(memberA, "document.shared")).hasSize(1);
        assertThat(rows(memberB, "document.shared")).hasSize(1);
    }

    @Test
    void sharingAFolder_tellsTheNewPeople_andLinksToSharedWithMe() throws Exception {
        User grantee = fx.user(tenant, "Grantee");
        UUID folder = id(adminPost("/api/v1/folders", admin, Map.of("name", "Finance"), 201), "folderId");

        adminPut("/api/v1/folders/" + folder + "/access", admin, restrictedTo(
                entry("user", admin.getUserId(), "manage"), entry("user", grantee.getUserId(), "view")), 200);

        Map<String, Object> shared = only(grantee, "document.shared");
        assertThat(shared.get("title")).isEqualTo("Admin shared a folder with you: Finance");
        assertThat(shared.get("link_url")).isEqualTo("/shared");
    }

    private static Map<String, Object> entry(String type, UUID principalId, String level) {
        return Map.of("principalType", type, "principalId", principalId, "level", level);
    }

    @SafeVarargs
    private static Map<String, Object> restrictedTo(Map<String, Object>... entries) {
        return Map.of("mode", "restricted", "entries", List.of(entries));
    }
}
