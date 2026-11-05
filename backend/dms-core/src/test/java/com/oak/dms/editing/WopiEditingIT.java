package com.oak.dms.editing;

import com.oak.dms.scanning.dto.IngestionRequest;
import com.oak.dms.scanning.dto.IngestionTicketResponse;
import com.oak.dms.scanning.enums.ScanResultStatus;
import com.oak.dms.scanning.service.IngestionGate;
import com.oak.dms.user.User;
import com.oak.dms.workflow.support.WorkflowApiSupport;
import com.oak.dms.workflow.support.WorkflowFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * 2.10 online editing, end to end over HTTP with the engine itself stubbed: who may start a session, what the
 * engine's calls (CheckFileInfo / GetFile / PutFile) are allowed to do with the token it was given, and the
 * fallbacks (engine down, file type not editable, document frozen).
 *
 * <p>The engine process is not started - discovery is stubbed - so this proves OUR side of WOPI. Whether a real
 * Collabora renders the document is checked separately in the browser.
 */
// No "enabled" property: whether editing is on is each tenant's own setting (V128), turned on per test below.
@SpringBootTest(properties = {
        "dms.editing.collabora-url=http://localhost:9980",
        "dms.editing.token-secret=an-editing-it-secret-that-is-only-for-tests-0123456789"
})
@Import(WorkflowFixtures.class)
class WopiEditingIT extends WorkflowApiSupport {

    private static final String VIEW = "docm.documents.view";
    private static final String CHECK_OUT = "cico.check-out-check-in-documents.check-out";
    private static final String CHECK_IN = "cico.check-out-check-in-documents.check-in";
    private static final String DISCARD = "cico.check-out-check-in-documents.discard-checkout";
    private static final String FORCE = "cico.force-release-lock";
    private static final String SELECT_PROVIDER = "cico.configure-editing-engine.select-provider";
    private static final String CONFIGURE_CONNECTION = "cico.configure-editing-engine.configure-connection";

    @MockitoBean
    EditingEngine engine;
    @MockitoBean
    IngestionGate ingestionGate;
    @Autowired
    WopiTokenCodec tokens;

    private UUID tenantId;
    private User alice;
    private User bob;
    private User admin;
    private UUID documentId;
    private UUID versionId;

    @BeforeEach
    void world() throws Exception {
        tenantId = fx.tenant();
        UUID type = fx.documentType(tenantId, "Contract");
        alice = fx.user(tenantId, "Alice");
        bob = fx.user(tenantId, "Bob");
        admin = fx.user(tenantId, "Admin");
        fx.role(tenantId, "Editors", new String[]{VIEW, CHECK_OUT, CHECK_IN, DISCARD}, alice, bob);
        fx.role(tenantId, "Lock admins", new String[]{VIEW, FORCE, SELECT_PROVIDER, CONFIGURE_CONNECTION}, admin);
        documentId = fx.document(tenantId, type, alice, "Master agreement");
        versionId = fx.jdbc().queryForObject("select latest_version_id from documents where document_id = ?",
                UUID.class, documentId);
        fx.jdbc().update("update document_versions set original_filename = 'Master agreement.docx' where version_id = ?",
                versionId);
        fx.putVersionContent(versionId, "ORIGINAL BYTES".getBytes(StandardCharsets.UTF_8));

        // This tenant's administrator has turned online editing on (exclusive); the SERVER has an engine.
        setEditing(tenantId, true, "exclusive");
        when(engine.isConfigured()).thenReturn(true);
        // What the real Collabora reports as editable (checked against its discovery document): Word, Excel,
        // PowerPoint, text and OpenDocument - and NOT pdf, which it only views.
        Set<String> editable = Set.of("docx", "xlsx", "pptx", "ppt", "odt", "ods", "odp", "txt", "rtf", "csv");
        when(engine.supportedExtensions()).thenReturn(editable);
        when(engine.editorUrlFor(any())).thenAnswer(inv -> {
            String name = inv.getArgument(0, String.class).toLowerCase();
            int dot = name.lastIndexOf('.');
            return dot >= 0 && editable.contains(name.substring(dot + 1))
                    ? Optional.of("http://localhost:9980/browser/abc/cool.html?") : Optional.empty();
        });
        doNothing().when(engine).requireAvailable();
    }

    // ------------------------------------------------------------------ starting a session

    @Test
    void startingASession_checksTheDocumentOut_andHandsBackAnUrlAndAToken() throws Exception {
        JsonNode session = postAs(path("edit-session"), alice, null, 200);

        assertThat(session.get("mode").asString()).isEqualTo("exclusive");
        String url = session.get("editorUrl").asString();
        assertThat(url).startsWith("http://localhost:9980/browser/abc/cool.html?WOPISrc=");
        assertThat(java.net.URLDecoder.decode(url, StandardCharsets.UTF_8))
                .endsWith("/internal/v1/wopi/files/" + documentId);
        // The token is bound to this user and this document - and the document is now Alice's to edit.
        WopiTokenCodec.Grant grant = tokens.verify(session.get("accessToken").asString()).orElseThrow();
        assertThat(grant.userId()).isEqualTo(alice.getUserId());
        assertThat(grant.documentId()).isEqualTo(documentId);
        assertThat(grant.tenantId()).isEqualTo(tenantId);
        assertThat(holder()).isEqualTo(alice.getUserId());
        assertThat(audit("DOCUMENT_EDIT_SESSION_STARTED")).isEqualTo(1);
    }

    @Test
    void whileAliceIsEditing_bobCannotStartASession_andIsToldWho() throws Exception {
        postAs(path("edit-session"), alice, null, 200);
        JsonNode refused = postAs(path("edit-session"), bob, null, 409);
        assertThat(refused.get("code").asString()).isEqualTo("DOCUMENT_CHECKED_OUT");
        assertThat(refused.get("message").asString()).contains("Alice");
        assertThat(holder()).isEqualTo(alice.getUserId());
    }

    @Test
    void aFileTheEngineCannotEdit_isRefusedWithTheFallback_andNothingIsLocked() throws Exception {
        fx.jdbc().update("update document_versions set original_filename = 'scan.pdf' where version_id = ?", versionId);
        JsonNode refused = postAs(path("edit-session"), alice, null, 409);
        assertThat(refused.get("code").asString()).isEqualTo("EDITING_UNSUPPORTED_TYPE");
        assertThat(refused.get("message").asString()).contains("Check out");
        assertThat(holder()).isNull();
    }

    @Test
    void anEngineThatIsDown_blocksTheSession_withTheFallback_andNothingIsLocked() throws Exception {
        doThrow(new EditingEngine.EngineUnavailableException("The editing engine cannot be reached."))
                .when(engine).requireAvailable();
        JsonNode refused = postAs(path("edit-session"), alice, null, 503);
        assertThat(refused.get("code").asString()).isEqualTo("EDITING_ENGINE_UNAVAILABLE");
        assertThat(refused.get("message").asString()).contains("Check out");
        assertThat(holder()).isNull();
    }

    @Test
    void aSignedOrHeldDocument_cannotBeOpenedForEditing() throws Exception {
        fx.jdbc().update("update documents set signature_locked_at = now(), signature_locked_version_id = "
                + "latest_version_id where document_id = ?", documentId);
        assertThat(postAs(path("edit-session"), alice, null, 409).get("code").asString())
                .isEqualTo("DOCUMENT_SIGNATURE_LOCKED");
        fx.jdbc().update("update documents set signature_locked_at = null, signature_locked_version_id = null "
                + "where document_id = ?", documentId);

        fx.jdbc().update("update documents set legal_hold = true, legal_hold_at = now(), legal_hold_by = ?, "
                + "legal_hold_reason = 'x' where document_id = ?", admin.getUserId(), documentId);
        assertThat(postAs(path("edit-session"), alice, null, 409).get("code").asString())
                .isEqualTo("DOCUMENT_LEGAL_HOLD");
        assertThat(holder()).isNull();
    }

    @Test
    void someoneWithoutTheCheckOutPermission_cannotStartASession() throws Exception {
        User reader = fx.user(tenantId, "Reader");
        fx.role(tenantId, "Readers", new String[]{VIEW}, reader);
        postAs(path("edit-session"), reader, null, 403);
    }

    // ------------------------------------------------------------------ what the engine may do with its token

    @Test
    void checkFileInfo_describesTheFile_andSaysTheHolderMayWrite() throws Exception {
        String token = sessionToken(alice);
        JsonNode info = json.readTree(mvc.perform(get(wopi("")).param("access_token", token)).andReturn()
                .getResponse().getContentAsString());

        assertThat(info.get("BaseFileName").asString()).isEqualTo("Master agreement.docx");
        assertThat(info.get("UserId").asString()).isEqualTo(alice.getUserId().toString());
        assertThat(info.get("Version").asString()).isEqualTo(versionId.toString());
        assertThat(info.get("UserCanWrite").asBoolean()).isTrue();
        assertThat(info.get("SupportsLocks").asBoolean()).isFalse();
    }

    /**
     * Found against a real Collabora: it sends the access token in the URL AND as an {@code Authorization: Bearer}
     * header. The app's resource-server filter honours that header and tries to validate our WOPI token as a Keycloak
     * JWT - it fails and answers 401 before the controller runs, so the editor stayed blank. The stubbed engine in
     * every other test never sent the header, which is why nothing caught it.
     */
    @Test
    void theEngineSendingItsTokenAsABearerHeaderToo_isStillServed() throws Exception {
        String token = sessionToken(alice);

        JsonNode info = json.readTree(mvc.perform(get(wopi("")).param("access_token", token)
                        .header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString());
        assertThat(info.get("BaseFileName").asString()).isEqualTo("Master agreement.docx");

        MvcResult contents = mvc.perform(get(wopi("/contents")).param("access_token", token)
                .header("Authorization", "Bearer " + token)).andReturn();
        if (contents.getRequest().isAsyncStarted()) {
            contents = mvc.perform(asyncDispatch(contents)).andReturn();
        }
        assertThat(contents.getResponse().getStatus()).isEqualTo(200);

        assertThat(mvc.perform(post(wopi("/contents")).param("access_token", token)
                        .header("Authorization", "Bearer " + token)
                        .header("X-COOL-WOPI-IsAutosave", "true").content("x".getBytes(StandardCharsets.UTF_8)))
                .andReturn().getResponse().getStatus()).isEqualTo(200);

        // The header must not be a way in on its own: no valid access_token, still refused.
        assertThat(mvc.perform(get(wopi("")).header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getStatus()).isEqualTo(401);
    }

    @Test
    void getFile_returnsTheCurrentVersionsBytes() throws Exception {
        String token = sessionToken(alice);
        MvcResult result = mvc.perform(get(wopi("/contents")).param("access_token", token)).andReturn();
        if (result.getRequest().isAsyncStarted()) {
            result = mvc.perform(asyncDispatch(result)).andReturn();
        }
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentAsString()).isEqualTo("ORIGINAL BYTES");
    }

    @Test
    void noToken_aGarbageToken_anExpiredToken_andAnotherDocumentsToken_areAllRefused() throws Exception {
        UUID other = fx.document(tenantId, fx.documentType(tenantId, "Memo"), alice, "Other");
        String forOther = tokens.mint(new WopiTokenCodec.Grant(tenantId, alice.getUserId(), other, "Alice",
                Instant.now().plusSeconds(600)));
        String expired = tokens.mint(new WopiTokenCodec.Grant(tenantId, alice.getUserId(), documentId, "Alice",
                Instant.now().minusSeconds(5)));

        for (String token : new String[]{null, "garbage", expired, forOther}) {
            var request = get(wopi(""));
            if (token != null) {
                request.param("access_token", token);
            }
            assertThat(mvc.perform(request).andReturn().getResponse().getStatus())
                    .as("token %s", token).isEqualTo(401);
            var contents = get(wopi("/contents"));
            if (token != null) {
                contents.param("access_token", token);
            }
            assertThat(mvc.perform(contents).andReturn().getResponse().getStatus()).as("contents, token %s", token).isEqualTo(401);
        }
    }

    @Test
    void aTokenForATenantsDocument_cannotReachItFromAnotherTenant() throws Exception {
        UUID otherTenant = fx.tenant();
        User outsider = fx.user(otherTenant, "Outsider");
        // That organisation is fully switched on, so what is tested here is tenant isolation - not the on/off switches.
        setEditing(otherTenant, true, "exclusive");
        // A forged-in-spirit grant: right document id, wrong tenant. The document is simply not there.
        String token = tokens.mint(new WopiTokenCodec.Grant(otherTenant, outsider.getUserId(), documentId, "O",
                Instant.now().plusSeconds(600)));
        assertThat(mvc.perform(get(wopi("")).param("access_token", token)).andReturn().getResponse().getStatus())
                .isEqualTo(404);
        assertThat(mvc.perform(get(wopi("/contents")).param("access_token", token)).andReturn().getResponse().getStatus())
                .isEqualTo(404);
    }

    // ------------------------------------------------------------------ saving

    @Test
    void anAutosave_isAcknowledged_butNotStored() throws Exception {
        String token = sessionToken(alice);
        MockHttpServletResponse response = mvc.perform(post(wopi("/contents")).param("access_token", token)
                        .header("X-COOL-WOPI-IsAutosave", "true").content("half-typed".getBytes(StandardCharsets.UTF_8)))
                .andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        verify(ingestionGate, never()).submit(any());
    }

    @Test
    void aDeliberateSave_goesThroughTheScanGate_asANewVersion() throws Exception {
        String token = sessionToken(alice);
        when(ingestionGate.submit(any())).thenReturn(ticket(ScanResultStatus.pending));

        MockHttpServletResponse response = mvc.perform(post(wopi("/contents")).param("access_token", token)
                        .content("EDITED BYTES".getBytes(StandardCharsets.UTF_8)))
                .andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(200);
        ArgumentCaptor<IngestionRequest> captured = ArgumentCaptor.forClass(IngestionRequest.class);
        verify(ingestionGate).submit(captured.capture());
        assertThat(captured.getValue().targetDocumentId()).isEqualTo(documentId);
        assertThat(captured.getValue().originalFilename()).isEqualTo("Master agreement.docx");
        assertThat(captured.getValue().contentLength()).isEqualTo("EDITED BYTES".length());
        assertThat(audit("DOCUMENT_EDIT_SESSION_SAVED")).isEqualTo(1);
        // Saving is not checking in: Alice still holds it until she is done.
        assertThat(holder()).isEqualTo(alice.getUserId());
    }

    @Test
    void aSaveTheScannerRefuses_isNotReportedAsSaved() throws Exception {
        String token = sessionToken(alice);
        when(ingestionGate.submit(any())).thenReturn(ticket(ScanResultStatus.infected));
        assertThat(mvc.perform(post(wopi("/contents")).param("access_token", token)
                        .content("bad".getBytes(StandardCharsets.UTF_8))).andReturn().getResponse().getStatus())
                .isEqualTo(422);
        assertThat(audit("DOCUMENT_EDIT_SESSION_SAVED")).isZero();
    }

    @Test
    void aLockForceReleasedMidSession_stopsFurtherSaves_eventThoughTheTokenIsStillValid() throws Exception {
        String token = sessionToken(alice);
        postAs(path("checkout/force-release"), admin, java.util.Map.of("reason", "Alice is away"), 200);

        assertThat(mvc.perform(get(wopi("")).param("access_token", token)).andReturn().getResponse().getContentAsString())
                .contains("\"UserCanWrite\":false");
        assertThat(mvc.perform(post(wopi("/contents")).param("access_token", token)
                        .content("late".getBytes(StandardCharsets.UTF_8))).andReturn().getResponse().getStatus())
                .isEqualTo(409);
        verify(ingestionGate, never()).submit(any());
    }

    @Test
    void aDocumentSignedMidSession_cannotBeOverwritten() throws Exception {
        String token = sessionToken(alice);
        fx.jdbc().update("update documents set signature_locked_at = now(), signature_locked_version_id = "
                + "latest_version_id where document_id = ?", documentId);
        assertThat(mvc.perform(post(wopi("/contents")).param("access_token", token)
                        .content("overwrite".getBytes(StandardCharsets.UTF_8))).andReturn().getResponse().getStatus())
                .isEqualTo(409);
        verify(ingestionGate, never()).submit(any());
    }

    // ------------------------------------------------------------------ every file type, and signatures stay intact

    @Test
    void powerPointTextAndOtherOfficeFiles_canBeOpenedForEditing_butAPdfCannot() throws Exception {
        for (String name : new String[]{"slides.pptx", "legacy.ppt", "notes.txt", "letter.rtf", "data.csv",
                "sheet.xlsx", "memo.odt", "deck.odp"}) {
            rename(name);
            JsonNode session = postAs(path("edit-session"), alice, null, 200);
            assertThat(session.get("editorUrl").asString()).as(name).contains("WOPISrc=");
            postAs(path("checkout/discard"), alice, null, 200); // free the lock for the next file
        }

        // Collabora only views PDFs: refused with the way forward, never a blank editor.
        rename("scan.pdf");
        JsonNode refused = postAs(path("edit-session"), alice, null, 409);
        assertThat(refused.get("code").asString()).isEqualTo("EDITING_UNSUPPORTED_TYPE");
        assertThat(refused.get("message").asString()).contains("Check out");
        assertThat(holder()).isNull();
    }

    @Test
    void aFileAlreadyCarryingASignature_isNeverOpenedForEditing_inEitherMode_forEveryType() throws Exception {
        byte[] signedOffice = zip("[Content_Types].xml", "_xmlsignatures/sig1.xml");
        byte[] signedOdf = zip("mimetype", "META-INF/documentsignatures.xml");
        for (String mode : new String[]{"exclusive", "simultaneous"}) {
            setEditing(tenantId, true, mode);
            assertSigned("letter.docx", signedOffice);
            assertSigned("budget.xlsx", signedOffice);
            assertSigned("deck.pptx", signedOffice);
            assertSigned("memo.odt", signedOdf);
            assertSigned("slides.odp", signedOdf);
        }
        // The same file types WITHOUT a signature open normally.
        rename("deck.pptx");
        fx.putVersionContent(versionId, zip("[Content_Types].xml", "ppt/presentation.xml"));
        postAs(path("edit-session"), alice, null, 200);
    }

    @Test
    void aVersionWithASignatureMarkBurnedIn_isNeverOpenedForEditing_whateverTheType() throws Exception {
        for (String name : new String[]{"signed.docx", "signed.pptx", "signed.odt", "signed.txt"}) {
            rename(name);
            fx.jdbc().update("update document_versions set created_reason = 'signed_copy' where version_id = ?", versionId);
            JsonNode refused = postAs(path("edit-session"), alice, null, 409);
            assertThat(refused.get("code").asString()).as(name).isEqualTo("DOCUMENT_HAS_SIGNATURE");
            assertThat(holder()).as(name).isNull();
        }
    }

    @Test
    void aDocumentSignedDuringASession_cannotBeSavedOver() throws Exception {
        String token = sessionToken(alice);
        // The visual signature is placed (a new signed_copy version becomes current) while the editor is still open.
        fx.jdbc().update("update document_versions set created_reason = 'signed_copy' where version_id = ?", versionId);

        assertThat(mvc.perform(get(wopi("")).param("access_token", token)).andReturn().getResponse().getContentAsString())
                .contains("\"UserCanWrite\":false");
        assertThat(mvc.perform(post(wopi("/contents")).param("access_token", token)
                        .content("overwrite the signed copy".getBytes(StandardCharsets.UTF_8)))
                .andReturn().getResponse().getStatus()).isEqualTo(409);
        verify(ingestionGate, never()).submit(any());
    }

    @Test
    void theEditorOffersNoDownloadOrRepair_becauseTheDmsAlreadyHasDownloadAndItsOwnSigning() throws Exception {
        String token = sessionToken(alice);
        JsonNode info = json.readTree(mvc.perform(get(wopi("")).param("access_token", token)).andReturn()
                .getResponse().getContentAsString());

        assertThat(info.get("DisableExport").asBoolean()).isTrue();   // no taking a second copy out of the editor
        assertThat(info.get("HideExportOption").asBoolean()).isTrue(); // no Download / Download as in the menus
        assertThat(info.get("HideRepairOption").asBoolean()).isTrue(); // no rolling back outside version control
        assertThat(info.get("DisablePrint").asBoolean()).isFalse();    // printing is still an editing convenience
    }

    @Test
    void theFileInfoNamesTheWebAppAsTheOnlyOriginThatMayTalkToTheEditor() throws Exception {
        // So the page can tell the editor to hide controls the DMS has its own versions of (security label, signing).
        // An origin, not a URL: scheme://host[:port] - what a browser reports as the page's origin.
        String token = sessionToken(alice);
        JsonNode info = json.readTree(mvc.perform(get(wopi("")).param("access_token", token)).andReturn()
                .getResponse().getContentAsString());
        assertThat(info.get("PostMessageOrigin").asString()).isEqualTo("http://localhost:3000");
    }

    @Test
    void aSaveThatCarriesASignature_isRefused_soSigningCannotHappenInsideTheEditor() throws Exception {
        String token = sessionToken(alice);
        when(ingestionGate.submit(any())).thenReturn(ticket(ScanResultStatus.pending));

        // The saved file now has a digital signature part in it: the editor signed it. Refused, nothing stored.
        var refused = mvc.perform(post(wopi("/contents")).param("access_token", token)
                        .content(zip("[Content_Types].xml", "word/document.xml", "_xmlsignatures/sig1.xml")))
                .andReturn().getResponse();
        assertThat(refused.getStatus()).isEqualTo(409);
        verify(ingestionGate, never()).submit(any());

        // The same save without the signature goes through.
        var ok = mvc.perform(post(wopi("/contents")).param("access_token", token)
                        .content(zip("[Content_Types].xml", "word/document.xml")))
                .andReturn().getResponse();
        assertThat(ok.getStatus()).isEqualTo(200);
        verify(ingestionGate).submit(any());
    }

    private void rename(String filename) {
        fx.jdbc().update("update document_versions set original_filename = ?, created_reason = 'replacement_upload' "
                + "where version_id = ?", filename, versionId);
    }

    private void assertSigned(String filename, byte[] signedContent) throws Exception {
        rename(filename);
        fx.putVersionContent(versionId, signedContent);
        JsonNode refused = postAs(path("edit-session"), alice, null, 409);
        assertThat(refused.get("code").asString()).as(filename).isEqualTo("DOCUMENT_HAS_SIGNATURE");
        assertThat(refused.get("message").asString()).as(filename).contains("digital signature");
        assertThat(holder()).as(filename).isNull();
    }

    private static byte[] zip(String... entries) throws java.io.IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream z = new java.util.zip.ZipOutputStream(out)) {
            for (String entry : entries) {
                z.putNextEntry(new java.util.zip.ZipEntry(entry));
                z.write("<x/>".getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
            }
        }
        return out.toByteArray();
    }

    // ------------------------------------------------------------------ the organisation's own switch (V128)

    @Test
    void anOrganisationThatHasNotTurnedItOn_getsNoSession_andNothingIsLocked() throws Exception {
        fx.jdbc().update("delete from tenant_editing_settings where tenant_id = ?", tenantId); // back to the default
        JsonNode refused = postAs(path("edit-session"), alice, null, 503);
        assertThat(refused.get("code").asString()).isEqualTo("EDITING_DISABLED");
        assertThat(refused.get("message").asString()).contains("Check out");
        assertThat(holder()).isNull();
        assertThat(get200("/api/v1/editing/config", alice).get("enabled").asBoolean()).isFalse();
    }

    @Test
    void oneOrganisationTurningItOn_doesNotTurnItOnForAnother() throws Exception {
        UUID otherTenant = fx.tenant();
        User outsider = fx.user(otherTenant, "Outsider");
        fx.role(otherTenant, "Editors", new String[]{VIEW, CHECK_OUT}, outsider);
        UUID otherDoc = fx.document(otherTenant, fx.documentType(otherTenant, "Contract"), outsider, "Their file");
        fx.jdbc().update("update document_versions set original_filename = 'Their file.docx' "
                + "where version_id = (select latest_version_id from documents where document_id = ?)", otherDoc);

        // Ours is on and works; theirs never turned it on, so it is refused - same server, same engine.
        postAs(path("edit-session"), alice, null, 200);
        JsonNode refused = postAs("/api/v1/documents/" + otherDoc + "/edit-session", outsider, null, 503);
        assertThat(refused.get("code").asString()).isEqualTo("EDITING_DISABLED");
    }

    @Test
    void theConfigEndpoint_followsTheOrganisationsSetting() throws Exception {
        JsonNode on = get200("/api/v1/editing/config", alice);
        assertThat(on.get("enabled").asBoolean()).isTrue();
        assertThat(on.get("mode").asString()).isEqualTo("exclusive");
        assertThat(on.get("extensions").toString()).contains("docx");

        setEditing(tenantId, false, "simultaneous");
        JsonNode off = get200("/api/v1/editing/config", alice);
        assertThat(off.get("enabled").asBoolean()).isFalse();
        assertThat(off.get("mode").asString()).isEqualTo("simultaneous");
    }

    @Test
    void anAdministrator_canTurnItOnAndOff_andChooseTheMode_andItIsAudited() throws Exception {
        fx.jdbc().update("delete from tenant_editing_settings where tenant_id = ?", tenantId);
        JsonNode view = get200("/api/v1/editing/settings", admin);
        assertThat(view.get("enabled").asBoolean()).isFalse(); // the default
        assertThat(view.get("mode").asString()).isEqualTo("exclusive");
        assertThat(view.get("engineConfigured").asBoolean()).isTrue();

        JsonNode on = call(put("/api/v1/editing/settings"), fx.jwtFor(admin),
                Map.of("enabled", true, "mode", "simultaneous"), 200);
        assertThat(on.get("enabled").asBoolean()).isTrue();
        assertThat(on.get("mode").asString()).isEqualTo("simultaneous");
        setTypeCoediting(tenantId, true); // the organisation chose Together; the type must allow it too
        assertThat(postAs(path("edit-session"), alice, null, 200).get("mode").asString()).isEqualTo("simultaneous");

        call(put("/api/v1/editing/settings"), fx.jwtFor(admin), Map.of("enabled", false, "mode", "exclusive"), 200);
        postAs(path("edit-session"), alice, null, 503);
        assertThat(audit("EDITING_SETTINGS_CHANGED", tenantId)).isEqualTo(2);
    }

    @Test
    void theSettingNeedsItsPermission_andAnUnknownModeIsRefused() throws Exception {
        call(put("/api/v1/editing/settings"), fx.jwtFor(alice), Map.of("enabled", true, "mode", "exclusive"), 403);
        mvc.perform(get("/api/v1/editing/settings").with(fx.jwtFor(alice)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());

        JsonNode bad = call(put("/api/v1/editing/settings"), fx.jwtFor(admin), Map.of("enabled", true, "mode", "chaos"), 400);
        assertThat(bad.get("code").asString()).isEqualTo("EDITING_MODE_INVALID");
    }

    @Test
    void itCannotBeTurnedOn_whenTheServerHasNoEngine_butCanAlwaysBeTurnedOff() throws Exception {
        when(engine.isConfigured()).thenReturn(false);
        JsonNode refused = call(put("/api/v1/editing/settings"), fx.jwtFor(admin),
                Map.of("enabled", true, "mode", "exclusive"), 409);
        assertThat(refused.get("code").asString()).isEqualTo("EDITING_ENGINE_NOT_CONFIGURED");

        call(put("/api/v1/editing/settings"), fx.jwtFor(admin), Map.of("enabled", false, "mode", "exclusive"), 200);
    }

    @Test
    void inSimultaneousMode_noLockIsTaken_andTwoPeopleCanOpenItTogether() throws Exception {
        setEditing(tenantId, true, "simultaneous");

        JsonNode first = postAs(path("edit-session"), alice, null, 200);
        JsonNode second = postAs(path("edit-session"), bob, null, 200);
        assertThat(first.get("mode").asString()).isEqualTo("simultaneous");
        assertThat(second.get("mode").asString()).isEqualTo("simultaneous");
        assertThat(holder()).isNull(); // nobody holds an exclusive lock

        // Either may save; saving is still a scanned new version.
        when(ingestionGate.submit(any())).thenReturn(ticket(ScanResultStatus.pending));
        for (JsonNode session : new JsonNode[]{first, second}) {
            assertThat(mvc.perform(post(wopi("/contents")).param("access_token", session.get("accessToken").asString())
                            .content("typed together".getBytes(StandardCharsets.UTF_8)))
                    .andReturn().getResponse().getStatus()).isEqualTo(200);
        }
        verify(ingestionGate, org.mockito.Mockito.times(2)).submit(any());
    }

    @Test
    void inSimultaneousMode_aTypeThatDoesNotAllowCoEditing_stillGetsTheExclusiveLock() throws Exception {
        setEditing(tenantId, true, "simultaneous");
        setTypeCoediting(tenantId, false); // "Co-editing enabled" is off on the Contract type

        JsonNode first = postAs(path("edit-session"), alice, null, 200);
        assertThat(first.get("mode").asString()).isEqualTo("exclusive");
        assertThat(holder()).isEqualTo(alice.getUserId());
        // Bob is kept out exactly as in exclusive mode.
        postAs(path("edit-session"), bob, null, 409);
        // ...and only the holder may save.
        String bobsToken = tokens.mint(new WopiTokenCodec.Grant(tenantId, bob.getUserId(), documentId, "Bob",
                java.time.Instant.now().plusSeconds(600)));
        assertThat(mvc.perform(post(wopi("/contents")).param("access_token", bobsToken)
                        .content("nope".getBytes(StandardCharsets.UTF_8))).andReturn().getResponse().getStatus())
                .isEqualTo(409);
    }

    @Test
    void inSimultaneousMode_aPlainCheckOutByOneStillKeepsTheOtherFromSaving() throws Exception {
        setEditing(tenantId, true, "simultaneous");
        String bobsToken = postAs(path("edit-session"), bob, null, 200).get("accessToken").asString();

        postAs(path("checkout"), alice, null, 200); // Alice takes the exclusive lock for download-and-edit

        assertThat(mvc.perform(post(wopi("/contents")).param("access_token", bobsToken)
                        .content("late".getBytes(StandardCharsets.UTF_8))).andReturn().getResponse().getStatus())
                .isEqualTo(409);
        verify(ingestionGate, never()).submit(any());
    }


    // ------------------------------------------------------------------ the platform administrator's switch (V136)

    @Test
    void anOrganisationThePlatformHasNotAllowed_getsNoEditor_whateverItsOwnSettingSays() throws Exception {
        platformAllows(tenantId, false); // its own setting stays ON

        JsonNode refused = postAs(path("edit-session"), alice, null, 503);

        assertThat(refused.get("code").asString()).isEqualTo("EDITING_DISABLED");
        assertThat(holder()).isNull();
        JsonNode config = get200("/api/v1/editing/config", alice);
        assertThat(config.get("enabled").asBoolean()).isFalse();
        assertThat(config.get("allowedByPlatform").asBoolean()).isFalse();
        assertThat(config.get("extensions").size()).isZero();
    }

    @Test
    void whileNotAllowed_theOrganisationsAdministratorCantTurnItOn_andIsToldWhy() throws Exception {
        platformAllows(tenantId, false);

        JsonNode view = get200("/api/v1/editing/settings", admin);
        assertThat(view.get("allowedByPlatform").asBoolean()).isFalse();
        JsonNode refused = call(put("/api/v1/editing/settings"), fx.jwtFor(admin),
                Map.of("enabled", true, "mode", "exclusive"), 409);

        assertThat(refused.get("code").asString()).isEqualTo("EDITING_NOT_ALLOWED");
        assertThat(refused.get("message").asString()).contains("platform administrator");
    }

    @Test
    void whatTheOrganisationChose_comesBackWhenThePlatformAllowsItAgain() throws Exception {
        setEditing(tenantId, true, "exclusive");
        platformAllows(tenantId, false);
        assertThat(get200("/api/v1/editing/config", alice).get("enabled").asBoolean()).isFalse();

        platformAllows(tenantId, true);

        JsonNode config = get200("/api/v1/editing/config", alice);
        assertThat(config.get("enabled").asBoolean()).isTrue();
        assertThat(config.get("allowedByPlatform").asBoolean()).isTrue();
        // Its own choice was never touched.
        assertThat(fx.count("select count(*) from tenant_editing_settings where tenant_id = ? and enabled", tenantId))
                .isEqualTo(1);
        postAs(path("edit-session"), alice, null, 200);
    }

    @Test
    void anOrganisationThePlatformHasAllowed_decidesForItself_theAdministratorCanTurnItOffAndOn() throws Exception {
        platformAllows(tenantId, true);
        JsonNode off = call(put("/api/v1/editing/settings"), fx.jwtFor(admin), Map.of("enabled", false, "mode", "exclusive"), 200);
        assertThat(off.get("allowedByPlatform").asBoolean()).isTrue();
        assertThat(get200("/api/v1/editing/config", alice).get("enabled").asBoolean()).isFalse();

        call(put("/api/v1/editing/settings"), fx.jwtFor(admin), Map.of("enabled", true, "mode", "exclusive"), 200);
        assertThat(get200("/api/v1/editing/config", alice).get("enabled").asBoolean()).isTrue();
    }

    @Test
    void anEditorAlreadyOpen_stopsLoadingAndSaving_theMomentThePlatformStopsIt_andResumesWhenAllowedAgain() throws Exception {
        String token = sessionToken(alice);   // an editor is open, with a token that lasts hours
        assertThat(mvc.perform(get(wopi("")).param("access_token", token)).andReturn().getResponse().getStatus()).isEqualTo(200);

        platformAllows(tenantId, false);

        assertThat(mvc.perform(get(wopi("")).param("access_token", token)).andReturn().getResponse().getStatus()).isEqualTo(403);
        assertThat(mvc.perform(get(wopi("/contents")).param("access_token", token)).andReturn().getResponse().getStatus()).isEqualTo(403);
        assertThat(mvc.perform(post(wopi("/contents")).param("access_token", token)
                .content("late edit".getBytes(StandardCharsets.UTF_8))).andReturn().getResponse().getStatus()).isEqualTo(403);
        verify(ingestionGate, never()).submit(any());

        platformAllows(tenantId, true);
        assertThat(mvc.perform(get(wopi("")).param("access_token", token)).andReturn().getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void anEditorAlreadyOpen_alsoStops_whenTheOrganisationsOwnAdministratorTurnsItOff() throws Exception {
        String token = sessionToken(alice);

        setEditing(tenantId, false, "exclusive");

        assertThat(mvc.perform(get(wopi("")).param("access_token", token)).andReturn().getResponse().getStatus()).isEqualTo(403);
        assertThat(mvc.perform(post(wopi("/contents")).param("access_token", token)
                .content("late edit".getBytes(StandardCharsets.UTF_8))).andReturn().getResponse().getStatus()).isEqualTo(403);
        verify(ingestionGate, never()).submit(any());
    }

    @Test
    void oneOrganisationBeingAllowed_doesNotAllowAnother() throws Exception {
        UUID other = fx.tenant();
        User outsider = fx.user(other, "Outsider");
        fx.role(other, "Editors", new String[]{VIEW, CHECK_OUT}, outsider);
        UUID otherDoc = fx.document(other, fx.documentType(other, "Contract"), outsider, "Their file");
        fx.jdbc().update("update document_versions set original_filename = 'Their file.docx' "
                + "where version_id = (select latest_version_id from documents where document_id = ?)", otherDoc);
        // They turned it on for themselves, but the platform never allowed them.
        fx.jdbc().update("insert into tenant_editing_settings (tenant_id, enabled, mode) values (?, true, 'exclusive')", other);

        JsonNode refused = postAs("/api/v1/documents/" + otherDoc + "/edit-session", outsider, null, 503);

        assertThat(refused.get("code").asString()).isEqualTo("EDITING_DISABLED");
        postAs(path("edit-session"), alice, null, 200); // ...while ours, which is allowed, works
    }

    // ------------------------------------------------------------------ helpers

    /** The platform administrator allows (or stops) online editing for one organisation (V136). */
    private void platformAllows(UUID tenant, boolean allowed) {
        fx.jdbc().update("insert into tenant_platform_settings (tenant_id, online_editing_allowed) values (?, ?) "
                + "on conflict (tenant_id) do update set online_editing_allowed = excluded.online_editing_allowed",
                tenant, allowed);
    }

    private void setEditing(UUID tenant, boolean enabled, String mode) {
        platformAllows(tenant, true);
        fx.jdbc().update("insert into tenant_editing_settings (tenant_id, enabled, mode) values (?, ?, ?) "
                + "on conflict (tenant_id) do update set enabled = excluded.enabled, mode = excluded.mode",
                tenant, enabled, mode);
        // Together mode also needs the document type to allow co-editing; tests that want it off flip it back.
        setTypeCoediting(tenant, "simultaneous".equals(mode));
    }

    private void setTypeCoediting(UUID tenant, boolean allowed) {
        fx.jdbc().update("update document_types set coediting_enabled = ? where tenant_id = ?", allowed, tenant);
    }

    private String sessionToken(User as) throws Exception {
        return postAs(path("edit-session"), as, null, 200).get("accessToken").asString();
    }

    private String path(String suffix) {
        return "/api/v1/documents/" + documentId + "/" + suffix;
    }

    private String wopi(String suffix) {
        return "/internal/v1/wopi/files/" + documentId + suffix;
    }

    private UUID holder() {
        return fx.jdbc().queryForObject("select checked_out_by from documents where document_id = ?", UUID.class, documentId);
    }

    private long audit(String action) {
        return audit(action, documentId);
    }

    /** Audit rows of one action about one object (a document, or the tenant itself for a setting). */
    private long audit(String action, UUID objectId) {
        return fx.count("select count(*) from audit_events where tenant_id = ? and action = ? and object_id = ?",
                tenantId, action, objectId);
    }

    private IngestionTicketResponse ticket(ScanResultStatus status) {
        return new IngestionTicketResponse(UUID.randomUUID(), status, documentId, null, "Master agreement.docx", 12L,
                null, status == ScanResultStatus.infected ? "Eicar" : null, null, false, OffsetDateTime.now(), null);
    }
}
