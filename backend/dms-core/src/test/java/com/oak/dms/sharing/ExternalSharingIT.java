package com.oak.dms.sharing;

import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.user.User;
import com.oak.dms.workflow.support.WorkflowApiSupport;
import com.oak.dms.workflow.support.WorkflowFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * 1.5 Sharing and External Access, end to end over HTTP: the signed-in side (policy, create, revoke, confidential)
 * and the recipient's side with no login at all (links, passwords, limits, the portal for guests).
 */
@SpringBootTest
@Import(WorkflowFixtures.class)
class ExternalSharingIT extends WorkflowApiSupport {

    static final String[] SHARER = {"docm.documents.view",
            "sea.create-external-share-links.create-link", "sea.create-external-share-links.set-expiry-password",
            "sea.create-external-share-links.set-permission-level", "sea.revoke-share-links.revoke-single",
            "sea.manage-guest-access.invite-guest", "sea.manage-guest-access.update-guest-access",
            "sea.manage-guest-access.revoke-guest-access", "sea.manage-guest-access.view-guest-list",
            "sea.view-sharing-activity-log.view"};
    static final byte[] PDF = "%PDF-1.4 fake but served as is".getBytes(StandardCharsets.UTF_8);

    @Autowired
    private SharingTokens tokens;
    @Autowired
    private GuestService guestService;

    private UUID tenantId;
    private UUID typeId;
    private User alice;
    private User admin;
    private UUID doc;

    @BeforeEach
    void world() {
        tenantId = fx.tenant();
        typeId = fx.documentType(tenantId, "Contract");
        alice = fx.user(tenantId, "Alice");
        admin = fx.user(tenantId, "Admin");
        fx.role(tenantId, "Sharer " + UUID.randomUUID(), SHARER, alice);
        doc = storedDocument("Master services agreement");
    }

    // ------------------------------------------------------------------ helpers

    private UUID storedDocument(String title) {
        UUID id = fx.document(tenantId, typeId, alice, title);
        UUID versionId = fx.jdbc().queryForObject("select current_version_id from documents where document_id = ?",
                UUID.class, id);
        fx.jdbc().update("update document_versions set original_filename = ?, mime_type = 'application/pdf' "
                + "where version_id = ?", title.replace(' ', '-') + ".pdf", versionId);
        fx.putVersionContent(versionId, PDF);
        return id;
    }

    private UUID folder(String name, UUID parent) {
        UUID id = UUID.randomUUID();
        fx.jdbc().update("insert into folders (folder_id, tenant_id, parent_folder_id, folder_name, created_by) "
                + "values (?, ?, ?, ?, ?)", id, tenantId, parent, name, alice.getUserId());
        return id;
    }

    private void file(UUID documentId, UUID folderId) {
        fx.jdbc().update("update documents set folder_id = ? where document_id = ?", folderId, documentId);
    }

    private void enableSharing() throws Exception {
        call(put("/api/v1/sharing/settings"), fx.tenantAdminJwtFor(admin), Map.of("externalSharingEnabled", true,
                "defaultExpiryDays", 7, "maxExpiryDays", 30, "maxPasswordAttempts", 3, "lockoutMinutes", 15,
                "guestInactivityDays", 30, "onReclassify", "flag"), 200);
    }

    private JsonNode createLink(Map<String, Object> body, int expected) throws Exception {
        return call(post("/api/v1/sharing/links"), fx.jwtFor(alice), body, expected);
    }

    private Map<String, Object> docLink(String access) {
        return new java.util.LinkedHashMap<>(Map.of("targetType", "document", "targetId", doc.toString(),
                "access", access));
    }

    private static String tokenOf(JsonNode created) {
        String url = created.get("url").asString();
        return url.substring(url.lastIndexOf('/') + 1);
    }

    private JsonNode publicJson(MockHttpServletRequestBuilder request, Object body) throws Exception {
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        }
        MvcResult result = mvc.perform(request).andReturn();
        assertThat(result.getResponse().getStatus()).as(request + " -> " + result.getResponse().getContentAsString())
                .isEqualTo(200);
        return json.readTree(result.getResponse().getContentAsString());
    }

    private MockHttpServletResponse publicRaw(String path) throws Exception {
        MvcResult result = mvc.perform(get(path)).andReturn();
        if (result.getRequest().isAsyncStarted()) {
            result = mvc.perform(asyncDispatch(result)).andReturn();
        }
        return result.getResponse();
    }

    private JsonNode info(String token) throws Exception {
        return publicJson(get("/api/v1/public/share/" + token), null);
    }

    private JsonNode open(String token, String password) throws Exception {
        return publicJson(post("/api/v1/public/share/" + token + "/open"),
                password == null ? Map.of() : Map.of("password", password));
    }

    private long events(String type) {
        return fx.count("select count(*) from share_events where tenant_id = ? and event_type = ?", tenantId, type);
    }

    // ------------------------------------------------------------------ policy

    @Test
    void sharingIsOffUntilTheOrganisationTurnsItOn() throws Exception {
        JsonNode denied = createLink(docLink("view"), 403);
        assertThat(denied.get("code").asString()).isEqualTo("SHARING_DISABLED");

        enableSharing();
        createLink(docLink("view"), 201);
    }

    @Test
    void onlyATenantAdministratorChangesThePolicy() throws Exception {
        call(put("/api/v1/sharing/settings"), fx.jwtFor(alice), Map.of("externalSharingEnabled", true,
                "defaultExpiryDays", 7, "maxExpiryDays", 30, "maxPasswordAttempts", 3, "lockoutMinutes", 15,
                "guestInactivityDays", 30, "onReclassify", "flag"), 403);
        call(put("/api/v1/sharing/settings"), fx.tenantAdminJwtFor(admin), Map.of("externalSharingEnabled", true,
                "defaultExpiryDays", 40, "maxExpiryDays", 30, "maxPasswordAttempts", 3, "lockoutMinutes", 15,
                "guestInactivityDays", 30, "onReclassify", "flag"), 400);
    }

    // ------------------------------------------------------------------ links

    @Test
    void aViewOnlyLink_opensAndShowsTheFile_butCannotDownload() throws Exception {
        enableSharing();
        JsonNode created = createLink(docLink("view"), 201);
        String token = tokenOf(created);
        assertThat(created.get("link").get("status").asString()).isEqualTo("active");

        JsonNode info = info(token);
        assertThat(info.get("status").asString()).isEqualTo("active");
        assertThat(info.get("passwordRequired").asBoolean()).isFalse();
        assertThat(info.get("name").asString()).isEqualTo("Master services agreement");

        JsonNode opened = open(token, null);
        assertThat(opened.get("ok").asBoolean()).isTrue();
        String access = opened.get("accessToken").asString();

        MockHttpServletResponse shown = publicRaw("/api/v1/public/share/" + token + "/content?access=" + access);
        assertThat(shown.getStatus()).isEqualTo(200);
        assertThat(shown.getContentAsByteArray()).isEqualTo(PDF);
        assertThat(shown.getHeader("Content-Disposition")).startsWith("inline");
        assertThat(shown.getHeader("Cache-Control")).isEqualTo("no-store");

        assertThat(publicRaw("/api/v1/public/share/" + token + "/content?access=" + access + "&download=true")
                .getStatus()).isEqualTo(403);
        assertThat(events("link_created")).isEqualTo(1);
        assertThat(events("link_opened")).isEqualTo(1);
        assertThat(events("link_viewed")).isEqualTo(1);
    }

    @Test
    void aDownloadLink_servesTheOriginalAsAnAttachment_andIsLogged() throws Exception {
        enableSharing();
        String token = tokenOf(createLink(docLink("download"), 201));
        String access = open(token, null).get("accessToken").asString();

        MockHttpServletResponse file = publicRaw("/api/v1/public/share/" + token + "/content?access=" + access
                + "&download=true");
        assertThat(file.getStatus()).isEqualTo(200);
        assertThat(file.getContentAsByteArray()).isEqualTo(PDF);
        assertThat(file.getHeader("Content-Disposition")).startsWith("attachment");
        assertThat(events("link_downloaded")).isEqualTo(1);
    }

    @Test
    void theTokenIsNeverStored_onlyItsHash() throws Exception {
        enableSharing();
        String token = tokenOf(createLink(docLink("view"), 201));
        assertThat(fx.count("select count(*) from share_links where token_hash = ?", token)).isZero();
        assertThat(fx.count("select count(*) from share_links where token_hash = ?", tokens.hash(token))).isEqualTo(1);
    }

    @Test
    void aWrongPasswordIsCounted_andTooManyLockTheLink() throws Exception {
        enableSharing();
        Map<String, Object> body = docLink("view");
        body.put("password", "correct-horse");
        JsonNode created = createLink(body, 201);
        String token = tokenOf(created);
        assertThat(created.get("link").get("passwordProtected").asBoolean()).isTrue();
        assertThat(info(token).get("passwordRequired").asBoolean()).isTrue();
        assertThat(fx.jdbc().queryForObject("select password_hash from share_links where token_hash = ?", String.class,
                tokens.hash(token))).doesNotContain("correct-horse").startsWith("$2");

        JsonNode first = open(token, "nope");
        assertThat(first.get("status").asString()).isEqualTo("wrong_password");
        assertThat(first.get("attemptsRemaining").asInt()).isEqualTo(2);
        open(token, "nope");
        JsonNode locked = open(token, "nope");
        assertThat(locked.get("status").asString()).isEqualTo("locked");
        assertThat(locked.get("lockedUntil").isNull()).isFalse();

        // Even the right password is refused while the link is locked.
        assertThat(open(token, "correct-horse").get("status").asString()).isEqualTo("locked");
        assertThat(info(token).get("status").asString()).isEqualTo("locked");
        assertThat(events("password_failed")).isEqualTo(3);
        assertThat(events("link_locked")).isEqualTo(1);

        // After the lock-out the right password works again.
        fx.jdbc().update("update share_links set locked_until = now() - interval '1 minute' where token_hash = ?",
                tokens.hash(token));
        assertThat(open(token, "correct-horse").get("ok").asBoolean()).isTrue();
    }

    @Test
    void aViewLimitInvalidatesTheLinkAutomatically() throws Exception {
        enableSharing();
        Map<String, Object> body = docLink("view");
        body.put("maxViews", 1);
        String token = tokenOf(createLink(body, 201));

        assertThat(open(token, null).get("ok").asBoolean()).isTrue();
        JsonNode second = open(token, null);
        assertThat(second.get("ok").asBoolean()).isFalse();
        assertThat(second.get("status").asString()).isEqualTo("exhausted");
        assertThat(info(token).get("status").asString()).isEqualTo("exhausted");
        assertThat(events("link_expired")).isEqualTo(1);
    }

    @Test
    void anExpiredLinkShowsAnExpiredPage_notAnError() throws Exception {
        enableSharing();
        String token = tokenOf(createLink(docLink("view"), 201));
        fx.jdbc().update("update share_links set expires_at = now() - interval '1 minute' where token_hash = ?",
                tokens.hash(token));

        assertThat(info(token).get("status").asString()).isEqualTo("expired");
        assertThat(open(token, null).get("status").asString()).isEqualTo("expired");
    }

    @Test
    void anUnknownLinkIsUnavailable_notAnError() throws Exception {
        assertThat(info("not-a-real-token").get("status").asString()).isEqualTo("unavailable");
        assertThat(open("not-a-real-token", null).get("status").asString()).isEqualTo("unavailable");
    }

    @Test
    void revokingStopsAccessImmediately_evenWithAnAccessTokenAlreadyIssued() throws Exception {
        enableSharing();
        JsonNode created = createLink(docLink("view"), 201);
        String token = tokenOf(created);
        String access = open(token, null).get("accessToken").asString();
        assertThat(publicRaw("/api/v1/public/share/" + token + "/content?access=" + access).getStatus()).isEqualTo(200);

        call(delete("/api/v1/sharing/links/" + created.get("link").get("linkId").asString()), fx.jwtFor(alice), null, 204);

        assertThat(info(token).get("status").asString()).isEqualTo("revoked");
        assertThat(publicRaw("/api/v1/public/share/" + token + "/content?access=" + access).getStatus()).isEqualTo(403);
        assertThat(events("link_revoked")).isEqualTo(1);
    }

    @Test
    void expiryIsCappedByPolicy_andDefaultsWhenOmitted() throws Exception {
        enableSharing();
        Map<String, Object> tooLong = docLink("view");
        tooLong.put("expiresAt", OffsetDateTime.now().plusDays(60).toString());
        assertThat(createLink(tooLong, 400).get("code").asString()).isEqualTo("SHARING_EXPIRY_TOO_LONG");

        JsonNode link = createLink(docLink("view"), 201).get("link");
        OffsetDateTime expires = OffsetDateTime.parse(link.get("expiresAt").asString());
        assertThat(expires).isBetween(OffsetDateTime.now().plusDays(6), OffsetDateTime.now().plusDays(8));
    }

    @Test
    void someoneWithoutTheSharePermission_cannotCreateALink() throws Exception {
        enableSharing();
        User bob = fx.user(tenantId, "Bob");
        fx.role(tenantId, "Viewer " + UUID.randomUUID(), new String[]{"docm.documents.view"}, bob);
        call(post("/api/v1/sharing/links"), fx.jwtFor(bob), docLink("view"), 403);
    }

    @Test
    void noPermissionToDownload_meansViewOnly() throws Exception {
        enableSharing();
        User carol = fx.user(tenantId, "Carol");
        fx.role(tenantId, "Creator " + UUID.randomUUID(), new String[]{"docm.documents.view",
                "sea.create-external-share-links.create-link"}, carol);
        call(post("/api/v1/sharing/links"), fx.jwtFor(carol), docLink("download"), 403);
        call(post("/api/v1/sharing/links"), fx.jwtFor(carol), docLink("view"), 201);
    }

    // ------------------------------------------------------------------ confidential / retention

    @Test
    void aConfidentialDocumentCannotBeShared_unlessAnAdministratorOverridesWithAReason() throws Exception {
        enableSharing();
        call(put("/api/v1/sharing/confidential"), fx.jwtFor(alice),
                Map.of("targetType", "document", "targetId", doc.toString(), "confidential", true), 200);

        JsonNode eligibility = call(get("/api/v1/sharing/eligibility?targetType=document&targetId=" + doc),
                fx.jwtFor(alice), null, 200);
        assertThat(eligibility.get("canShare").asBoolean()).isFalse();
        assertThat(eligibility.get("confidential").asBoolean()).isTrue();
        assertThat(eligibility.get("canOverride").asBoolean()).isFalse();
        assertThat(createLink(docLink("view"), 403).get("code").asString()).isEqualTo("SHARING_NOT_ALLOWED");

        Map<String, Object> override = docLink("view");
        override.put("overrideRestriction", true);
        assertThat(createLink(override, 403).get("code").asString()).isEqualTo("SHARING_OVERRIDE_REQUIRES_ADMIN");

        override.put("overrideReason", "Auditor needs the signed copy");
        JsonNode created = call(post("/api/v1/sharing/links"), fx.tenantAdminJwtFor(admin), override, 201);
        assertThat(created.get("link").get("overridden").asBoolean()).isTrue();
        assertThat(info(tokenOf(created)).get("status").asString()).isEqualTo("active");
    }

    @Test
    void anAdministratorCannotOverrideLegalHold() throws Exception {
        enableSharing();
        fx.jdbc().update("update documents set legal_hold = true, legal_hold_at = now() where document_id = ?", doc);
        Map<String, Object> override = docLink("view");
        override.put("overrideRestriction", true);
        override.put("overrideReason", "needed");
        call(post("/api/v1/sharing/links"), fx.tenantAdminJwtFor(admin), override, 403);
    }

    @Test
    void reclassifyingAfterSharing_flagsTheLink_untilAnAdministratorKeepsIt() throws Exception {
        enableSharing();
        JsonNode created = createLink(docLink("view"), 201);
        String token = tokenOf(created);
        assertThat(info(token).get("status").asString()).isEqualTo("active");

        JsonNode result = call(put("/api/v1/sharing/confidential"), fx.jwtFor(alice),
                Map.of("targetType", "document", "targetId", doc.toString(), "confidential", true), 200);
        assertThat(result.get("flaggedLinks").asInt()).isEqualTo(1);
        assertThat(info(token).get("status").asString()).isEqualTo("unavailable");
        assertThat(open(token, null).get("ok").asBoolean()).isFalse();

        JsonNode review = call(get("/api/v1/sharing/links/all?status=review"), fx.tenantAdminJwtFor(admin), null, 200);
        assertThat(review.get("total").asInt()).isEqualTo(1);
        call(post("/api/v1/sharing/links/" + created.get("link").get("linkId").asString() + "/clear-review"),
                fx.tenantAdminJwtFor(admin), null, 204);
        assertThat(info(token).get("status").asString()).isEqualTo("active");
    }

    @Test
    void thePolicyCanRevokeInsteadOfFlag() throws Exception {
        enableSharing();
        call(put("/api/v1/sharing/settings"), fx.tenantAdminJwtFor(admin), Map.of("externalSharingEnabled", true,
                "defaultExpiryDays", 7, "maxExpiryDays", 30, "maxPasswordAttempts", 3, "lockoutMinutes", 15,
                "guestInactivityDays", 30, "onReclassify", "revoke"), 200);
        String token = tokenOf(createLink(docLink("view"), 201));

        JsonNode result = call(put("/api/v1/sharing/confidential"), fx.jwtFor(alice),
                Map.of("targetType", "document", "targetId", doc.toString(), "confidential", true), 200);
        assertThat(result.get("revokedLinks").asInt()).isEqualTo(1);
        assertThat(info(token).get("status").asString()).isEqualTo("revoked");
    }

    @Test
    void onlyAnAdministratorCanClearTheConfidentialMark() throws Exception {
        call(put("/api/v1/sharing/confidential"), fx.jwtFor(alice),
                Map.of("targetType", "document", "targetId", doc.toString(), "confidential", true), 200);
        call(put("/api/v1/sharing/confidential"), fx.jwtFor(alice),
                Map.of("targetType", "document", "targetId", doc.toString(), "confidential", false), 403);
        call(put("/api/v1/sharing/confidential"), fx.tenantAdminJwtFor(admin),
                Map.of("targetType", "document", "targetId", doc.toString(), "confidential", false), 200);
    }

    // ------------------------------------------------------------------ folders

    @Test
    void aFolderLink_listsWhatIsInsideAndNothingConfidential_andRefusesFilesOutsideIt() throws Exception {
        enableSharing();
        UUID root = folder("Client A", null);
        UUID sub = folder("Contracts", root);
        UUID secret = folder("Board papers", root);
        UUID inRoot = storedDocument("Welcome pack");
        UUID inSub = storedDocument("Signed contract");
        UUID inSecret = storedDocument("Board minutes");
        UUID outside = storedDocument("Unrelated");
        file(inRoot, root);
        file(inSub, sub);
        file(inSecret, secret);
        call(put("/api/v1/sharing/confidential"), fx.jwtFor(alice),
                Map.of("targetType", "folder", "targetId", secret.toString(), "confidential", true), 200);

        JsonNode created = createLink(Map.of("targetType", "folder", "targetId", root.toString(), "access", "view"), 201);
        String token = tokenOf(created);
        String access = open(token, null).get("accessToken").asString();

        JsonNode top = publicJson(get("/api/v1/public/share/" + token + "/items?access=" + access), null);
        assertThat(top.get("items")).extracting(n -> n.get("name").asString())
                .containsExactly("Contracts", "Welcome pack");
        assertThat(top.get("breadcrumb")).hasSize(1);

        JsonNode inner = publicJson(get("/api/v1/public/share/" + token + "/items?access=" + access + "&folderId=" + sub), null);
        assertThat(inner.get("items")).extracting(n -> n.get("name").asString()).containsExactly("Signed contract");
        assertThat(inner.get("breadcrumb")).hasSize(2);

        String base = "/api/v1/public/share/" + token + "/content?access=" + access + "&documentId=";
        assertThat(publicRaw(base + inSub).getStatus()).isEqualTo(200);
        assertThat(publicRaw(base + outside).getStatus()).isEqualTo(404);
        assertThat(publicRaw(base + inSecret).getStatus()).isEqualTo(404);
        assertThat(mvc.perform(get("/api/v1/public/share/" + token + "/items?access=" + access + "&folderId=" + secret))
                .andReturn().getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void aFolderYouCannotEditCannotBeShared() throws Exception {
        enableSharing();
        UUID root = folder("Locked", null);
        fx.jdbc().update("update folders set access_mode = 'restricted' where folder_id = ?", root);
        fx.jdbc().update("insert into folder_access (tenant_id, folder_id, principal_type, principal_id, access_level) "
                + "values (?, ?, 'user', ?, 'view')", tenantId, root, alice.getUserId());
        call(post("/api/v1/sharing/links"), fx.jwtFor(alice),
                Map.of("targetType", "folder", "targetId", root.toString(), "access", "view"), 403);
    }

    // ------------------------------------------------------------------ guests and the portal

    private String inviteTokenFor(String email) {
        String token = tokens.newToken();
        fx.jdbc().update("update guest_accounts set invite_token_hash = ?, invite_expires_at = now() + interval '1 day' "
                + "where tenant_id = ? and email = ?", tokens.hash(token), tenantId, email);
        return token;
    }

    private JsonNode grant(String email, UUID document, String access) throws Exception {
        return call(post("/api/v1/sharing/guest-grants"), fx.jwtFor(alice), Map.of("email", email, "displayName", "Gina Guest",
                "targetType", "document", "targetId", document.toString(), "access", access), 201);
    }

    private String signIn(String email, String password) throws Exception {
        JsonNode login = publicJson(post("/api/v1/public/portal/login"), Map.of("email", email, "password", password,
                "tenantId", tenantId.toString()));
        assertThat(login.get("status").asString()).isEqualTo("ok");
        return login.get("token").asString();
    }

    @Test
    void aGuest_isInvited_setsAPassword_signsIn_andSeesOnlyWhatWasGranted() throws Exception {
        enableSharing();
        UUID other = storedDocument("Not for guests");
        JsonNode granted = grant("Gina@Example.com", doc, "view");
        assertThat(granted.get("guestStatus").asString()).isEqualTo("invited");
        assertThat(granted.get("email").asString()).isEqualTo("gina@example.com");

        // Not usable until they set their own password.
        assertThat(publicJson(post("/api/v1/public/portal/login"), Map.of("email", "gina@example.com",
                "password", "anything-at-all", "tenantId", tenantId.toString())).get("status").asString())
                .isEqualTo("invalid");

        String invite = inviteTokenFor("gina@example.com");
        assertThat(publicJson(get("/api/v1/public/portal/invite/" + invite), null).get("valid").asBoolean()).isTrue();
        publicJson(post("/api/v1/public/portal/accept-invite"), Map.of("token", invite, "password", "a-long-passphrase"));
        assertThat(publicJson(get("/api/v1/public/portal/invite/" + invite), null).get("valid").asBoolean()).isFalse();

        String guest = signIn("gina@example.com", "a-long-passphrase");
        MvcResult me = mvc.perform(get("/api/v1/public/portal/me").header("X-Guest-Token", guest)).andReturn();
        assertThat(json.readTree(me.getResponse().getContentAsString()).get("email").asString()).isEqualTo("gina@example.com");

        MvcResult list = mvc.perform(get("/api/v1/public/portal/items").header("X-Guest-Token", guest)).andReturn();
        JsonNode items = json.readTree(list.getResponse().getContentAsString()).get("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).get("id").asString()).isEqualTo(doc.toString());
        assertThat(items.get(0).get("access").asString()).isEqualTo("view");

        assertThat(publicRaw("/api/v1/public/portal/content?access=" + guest + "&documentId=" + doc).getStatus())
                .isEqualTo(200);
        assertThat(publicRaw("/api/v1/public/portal/content?access=" + guest + "&documentId=" + other).getStatus())
                .isEqualTo(404);
        assertThat(publicRaw("/api/v1/public/portal/content?access=" + guest + "&documentId=" + doc + "&download=true")
                .getStatus()).isEqualTo(403);
        assertThat(events("guest_signed_in")).isEqualTo(1);
        assertThat(events("guest_viewed")).isEqualTo(1);
    }

    @Test
    void revokingAGrant_takesTheFileAwayOnTheGuestsNextRequest() throws Exception {
        enableSharing();
        JsonNode granted = grant("gina@example.com", doc, "download");
        publicJson(post("/api/v1/public/portal/accept-invite"), Map.of("token", inviteTokenFor("gina@example.com"),
                "password", "a-long-passphrase"));
        String guest = signIn("gina@example.com", "a-long-passphrase");
        assertThat(publicRaw("/api/v1/public/portal/content?access=" + guest + "&documentId=" + doc + "&download=true")
                .getStatus()).isEqualTo(200);

        call(delete("/api/v1/sharing/guest-grants/" + granted.get("grantId").asString()), fx.jwtFor(alice), null, 204);
        assertThat(publicRaw("/api/v1/public/portal/content?access=" + guest + "&documentId=" + doc).getStatus())
                .isEqualTo(404);
        MvcResult list = mvc.perform(get("/api/v1/public/portal/items").header("X-Guest-Token", guest)).andReturn();
        assertThat(json.readTree(list.getResponse().getContentAsString()).get("items")).isEmpty();
    }

    @Test
    void switchingAGuestOff_endsTheirSessionAndSignIn() throws Exception {
        enableSharing();
        JsonNode granted = grant("gina@example.com", doc, "view");
        publicJson(post("/api/v1/public/portal/accept-invite"), Map.of("token", inviteTokenFor("gina@example.com"),
                "password", "a-long-passphrase"));
        String guest = signIn("gina@example.com", "a-long-passphrase");

        call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/api/v1/sharing/guests/"
                + granted.get("guestId").asString()), fx.jwtFor(alice), Map.of("status", "disabled"), 200);

        assertThat(mvc.perform(get("/api/v1/public/portal/items").header("X-Guest-Token", guest)).andReturn()
                .getResponse().getStatus()).isEqualTo(403);
        assertThat(publicJson(post("/api/v1/public/portal/login"), Map.of("email", "gina@example.com",
                "password", "a-long-passphrase", "tenantId", tenantId.toString())).get("status").asString())
                .isEqualTo("disabled");
    }

    @Test
    void aGuestAccountLocksAfterTooManyWrongPasswords() throws Exception {
        enableSharing();
        grant("gina@example.com", doc, "view");
        publicJson(post("/api/v1/public/portal/accept-invite"), Map.of("token", inviteTokenFor("gina@example.com"),
                "password", "a-long-passphrase"));
        for (int i = 0; i < 3; i++) {
            publicJson(post("/api/v1/public/portal/login"), Map.of("email", "gina@example.com", "password", "wrong-one",
                    "tenantId", tenantId.toString()));
        }
        assertThat(publicJson(post("/api/v1/public/portal/login"), Map.of("email", "gina@example.com",
                "password", "a-long-passphrase", "tenantId", tenantId.toString())).get("status").asString())
                .isEqualTo("locked");
    }

    @Test
    void anInactiveGuestIsSwitchedOffAutomatically() throws Exception {
        enableSharing();
        grant("gina@example.com", doc, "view");
        publicJson(post("/api/v1/public/portal/accept-invite"), Map.of("token", inviteTokenFor("gina@example.com"),
                "password", "a-long-passphrase"));
        fx.jdbc().update("update guest_accounts set last_login_at = now() - interval '45 days' where tenant_id = ?", tenantId);

        TenantContext.clear();
        assertThat(guestService.disableInactive(tenantId)).isEqualTo(1);
        assertThat(fx.jdbc().queryForObject("select status || ':' || disabled_reason from guest_accounts "
                + "where tenant_id = ?", String.class, tenantId)).isEqualTo("disabled:inactivity");
    }

    @Test
    void guestsAndLinksOfOneOrganisationAreInvisibleToAnother() throws Exception {
        enableSharing();
        JsonNode created = createLink(docLink("view"), 201);
        grant("gina@example.com", doc, "view");

        UUID otherTenant = fx.tenant();
        User mallory = fx.user(otherTenant, "Mallory");
        User otherAdmin = fx.user(otherTenant, "Boss");
        fx.role(otherTenant, "Sharer " + UUID.randomUUID(), SHARER, mallory);
        call(put("/api/v1/sharing/settings"), fx.tenantAdminJwtFor(otherAdmin), Map.of("externalSharingEnabled", true,
                "defaultExpiryDays", 7, "maxExpiryDays", 30, "maxPasswordAttempts", 3, "lockoutMinutes", 15,
                "guestInactivityDays", 30, "onReclassify", "flag"), 200);

        call(delete("/api/v1/sharing/links/" + created.get("link").get("linkId").asString()), fx.jwtFor(mallory), null, 404);
        assertThat(call(get("/api/v1/sharing/guests"), fx.jwtFor(mallory), null, 200).size()).isZero();
        call(post("/api/v1/sharing/links"), fx.jwtFor(mallory), docLink("view"), 404);
    }
}
