package com.oak.dms.checkout;

import com.oak.dms.scanning.dto.IngestionTicketResponse;
import com.oak.dms.scanning.enums.ScanResultStatus;
import com.oak.dms.scanning.service.IngestionGate;
import com.oak.dms.user.User;
import com.oak.dms.workflow.support.WorkflowApiSupport;
import com.oak.dms.workflow.support.WorkflowFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 2.10 exclusive check-out / check-in, end to end over HTTP: who wins, what everyone else is told,
 * what a lock does NOT let through (version upload, delete, move), the frozen states (signature
 * lock, legal hold), force-release and the idle expiry.
 *
 * <p>The ingestion gate is mocked: a real check-in is a scanned upload, and the scan/promote path
 * is already covered where it lives. What matters here is when the lock is released around it.
 */
@SpringBootTest
@Import(WorkflowFixtures.class)
class DocumentCheckoutIT extends WorkflowApiSupport {

    private static final String VIEW = "docm.documents.view";
    private static final String CREATE = "docm.documents.create";
    private static final String CHECK_OUT = "cico.check-out-check-in-documents.check-out";
    private static final String CHECK_IN = "cico.check-out-check-in-documents.check-in";
    private static final String DISCARD = "cico.check-out-check-in-documents.discard-checkout";
    private static final String FORCE = "cico.force-release-lock";
    private static final String PLACE_HOLD = "crm.place-legal-hold";
    private static final String LIFT_HOLD = "crm.lift-legal-hold";

    @MockitoBean
    IngestionGate ingestionGate;

    @org.springframework.beans.factory.annotation.Autowired
    com.oak.dms.document.RecycleBinService recycleBin;
    @org.springframework.beans.factory.annotation.Autowired
    com.oak.dms.signature.service.StandaloneSignaturePlacementService placement;

    private UUID tenantId;
    private User alice;
    private User bob;
    private User admin;
    private UUID documentId;

    @BeforeEach
    void world() {
        tenantId = fx.tenant();
        UUID type = fx.documentType(tenantId, "Contract");
        alice = fx.user(tenantId, "Alice");
        bob = fx.user(tenantId, "Bob");
        admin = fx.user(tenantId, "Admin");
        String[] editor = {VIEW, CREATE, CHECK_OUT, CHECK_IN, DISCARD};
        fx.role(tenantId, "Editors", editor, alice, bob);
        fx.role(tenantId, "Lock admins", new String[]{VIEW, FORCE, PLACE_HOLD, LIFT_HOLD}, admin);
        documentId = fx.document(tenantId, type, alice, "Master agreement");
        // A real document has a stored file, and check-out now reads it to make sure it carries no signature.
        UUID version = fx.jdbc().queryForObject("select latest_version_id from documents where document_id = ?",
                UUID.class, documentId);
        fx.jdbc().update("update document_versions set original_filename = 'Master agreement.docx' where version_id = ?", version);
        fx.putVersionContent(version, "ORIGINAL BYTES".getBytes(StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ the lock

    @Test
    void checkOut_locksTheDocument_andEveryoneSeesWhoHoldsIt() throws Exception {
        JsonNode mine = postAs(path("checkout"), alice, null, 200);
        assertThat(mine.get("state").asString()).isEqualTo("checked_out");
        assertThat(mine.get("checkedOutByMe").asBoolean()).isTrue();

        // Anyone who can see the document sees who has it and since when (CICO-FR-002).
        JsonNode seenByBob = get200(path("checkout"), bob);
        assertThat(seenByBob.get("state").asString()).isEqualTo("checked_out");
        assertThat(seenByBob.get("checkedOutByMe").asBoolean()).isFalse();
        assertThat(seenByBob.get("checkedOutByName").asString()).contains("Alice");
        assertThat(seenByBob.get("checkedOutAt").isNull()).isFalse();
        assertThat(audit("DOCUMENT_CHECKED_OUT")).isEqualTo(1);
    }

    @Test
    void aSecondUser_isRefused_andToldWhoHasIt() throws Exception {
        postAs(path("checkout"), alice, null, 200);
        JsonNode refused = postAs(path("checkout"), bob, null, 409);
        assertThat(refused.get("code").asString()).isEqualTo("DOCUMENT_CHECKED_OUT");
        assertThat(refused.get("message").asString()).contains("Alice")
                .containsPattern("since [0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2} UTC") // readable, not 2026-11-03T03:47:19.671824Z
                .doesNotContain("T0").doesNotContain("Z.");
        assertThat(holder()).isEqualTo(alice.getUserId());
    }

    @Test
    void checkingOutWhatYouAlreadyHold_isNotAnError_andChangesNothing() throws Exception {
        JsonNode first = postAs(path("checkout"), alice, null, 200);
        JsonNode again = postAs(path("checkout"), alice, null, 200);
        assertThat(again.get("checkedOutAt").asString()).isEqualTo(first.get("checkedOutAt").asString());
        assertThat(audit("DOCUMENT_CHECKED_OUT")).isEqualTo(1);
    }

    @Test
    void twoPeopleAtOnce_exactlyOneWins() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> results = new java.util.ArrayList<>();
        for (User user : List.of(alice, bob)) {
            Callable<Integer> action = () -> {
                ready.countDown();
                go.await();
                return mvc.perform(post(path("checkout")).with(fx.jwtFor(user))).andReturn().getResponse().getStatus();
            };
            results.add(pool.submit(action));
        }
        ready.await();
        go.countDown();
        List<Integer> statuses = List.of(results.get(0).get(), results.get(1).get());
        pool.shutdown();

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        assertThat(holder()).isIn(alice.getUserId(), bob.getUserId());
    }

    // ------------------------------------------------------------------ what a lock blocks

    @Test
    void whileCheckedOut_anotherUsersNewVersionIsRefused_andNeverReachesTheGate() throws Exception {
        postAs(path("checkout"), alice, null, 200);
        var result = mvc.perform(multipart("/api/v1/documents/" + documentId + "/versions")
                        .file(new MockMultipartFile("file", "x.docx", "application/octet-stream", new byte[]{1, 2, 3}))
                        .with(fx.jwtFor(bob)))
                .andReturn().getResponse();
        assertThat(result.getStatus()).isEqualTo(409);
        assertThat(json.readTree(result.getContentAsString()).get("code").asString()).isEqualTo("DOCUMENT_CHECKED_OUT");
        verify(ingestionGate, never()).submit(any());
    }

    // ------------------------------------------------------------------ check-in / discard

    @Test
    void checkIn_uploadsTheFileAsANewVersion_andReleasesTheLock() throws Exception {
        postAs(path("checkout"), alice, null, 200);
        when(ingestionGate.submit(any())).thenReturn(ticket(ScanResultStatus.pending));

        var response = mvc.perform(multipart(path("checkin"))
                        .file(new MockMultipartFile("file", "edited.docx", "application/octet-stream", new byte[]{9, 9}))
                        .param("comment", "fixed clause 4")
                        .with(fx.jwtFor(alice)))
                .andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(202); // same ticket as any version upload
        verify(ingestionGate).submit(any());
        assertThat(holder()).isNull();
        assertThat(audit("DOCUMENT_CHECKED_IN")).isEqualTo(1);
        // ...and now Bob can take it.
        postAs(path("checkout"), bob, null, 200);
    }

    @Test
    void checkIn_refusedByTheScanner_keepsTheLock_soTheHolderCanFixTheFile() throws Exception {
        postAs(path("checkout"), alice, null, 200);
        when(ingestionGate.submit(any())).thenReturn(ticket(ScanResultStatus.infected));

        var response = mvc.perform(multipart(path("checkin"))
                        .file(new MockMultipartFile("file", "bad.docx", "application/octet-stream", new byte[]{1}))
                        .with(fx.jwtFor(alice)))
                .andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(422);
        assertThat(holder()).isEqualTo(alice.getUserId());
        assertThat(audit("DOCUMENT_CHECKED_IN")).isZero();
    }

    @Test
    void onlyTheHolder_canCheckInOrDiscard() throws Exception {
        postAs(path("checkout"), alice, null, 200);

        JsonNode discard = postAs(path("checkout/discard"), bob, null, 409);
        assertThat(discard.get("code").asString()).isEqualTo("DOCUMENT_CHECKED_OUT");
        var checkIn = mvc.perform(multipart(path("checkin"))
                        .file(new MockMultipartFile("file", "x.docx", "application/octet-stream", new byte[]{1}))
                        .with(fx.jwtFor(bob)))
                .andReturn().getResponse();
        assertThat(checkIn.getStatus()).isEqualTo(409);
        verify(ingestionGate, never()).submit(any());
        assertThat(holder()).isEqualTo(alice.getUserId());
    }

    @Test
    void checkInWithoutCheckingOut_isRefused() throws Exception {
        var response = mvc.perform(multipart(path("checkin"))
                        .file(new MockMultipartFile("file", "x.docx", "application/octet-stream", new byte[]{1}))
                        .with(fx.jwtFor(alice)))
                .andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(409);
        assertThat(json.readTree(response.getContentAsString()).get("code").asString())
                .isEqualTo("DOCUMENT_NOT_CHECKED_OUT");
    }

    @Test
    void discard_releasesTheLock_withoutANewVersion() throws Exception {
        postAs(path("checkout"), alice, null, 200);
        JsonNode after = postAs(path("checkout/discard"), alice, null, 200);
        assertThat(after.get("state").asString()).isEqualTo("available");
        assertThat(holder()).isNull();
        verify(ingestionGate, never()).submit(any());
        assertThat(audit("DOCUMENT_CHECKOUT_DISCARDED")).isEqualTo(1);
    }

    // ------------------------------------------------------------------ force-release (CICO-FR-004)

    @Test
    void forceRelease_needsItsPermission_aReason_andIsAudited() throws Exception {
        postAs(path("checkout"), alice, null, 200);

        postAs(path("checkout/force-release"), bob, Map.of("reason", "away"), 403); // no such permission
        postAs(path("checkout/force-release"), admin, Map.of("reason", " "), 400); // reason is mandatory
        assertThat(holder()).isEqualTo(alice.getUserId());

        JsonNode released = postAs(path("checkout/force-release"), admin, Map.of("reason", "Alice is on leave"), 200);
        assertThat(released.get("state").asString()).isEqualTo("available");
        assertThat(holder()).isNull();
        assertThat(audit("DOCUMENT_CHECKOUT_FORCE_RELEASED")).isEqualTo(1);
        assertThat(fx.count("select count(*) from audit_events where tenant_id = ? and action = ? and reason = ?",
                tenantId, "DOCUMENT_CHECKOUT_FORCE_RELEASED", "Alice is on leave")).isEqualTo(1);

        postAs(path("checkout/force-release"), admin, Map.of("reason", "again"), 409); // nothing left to release
    }

    // ------------------------------------------------------------------ frozen documents (CICO-FR-009)

    @Test
    void aSignatureLockedDocument_cannotBeCheckedOut() throws Exception {
        fx.jdbc().update("update documents set signature_locked_at = now(), signature_locked_version_id = "
                + "latest_version_id where document_id = ?", documentId);
        JsonNode refused = postAs(path("checkout"), alice, null, 409);
        assertThat(refused.get("code").asString()).isEqualTo("DOCUMENT_SIGNATURE_LOCKED");
        assertThat(holder()).isNull();
        assertThat(get200(path("checkout"), alice).get("state").asString()).isEqualTo("signature_locked");
    }

    @Test
    void legalHold_blocksCheckOut_andEveryEdit_untilItIsLifted() throws Exception {
        postAs(path("legal-hold"), admin, Map.of("reason", "Litigation 2026-114"), 200);
        assertThat(get200(path("checkout"), alice).get("state").asString()).isEqualTo("legal_hold");

        JsonNode refused = postAs(path("checkout"), alice, null, 409);
        assertThat(refused.get("code").asString()).isEqualTo("DOCUMENT_LEGAL_HOLD");
        var upload = mvc.perform(multipart("/api/v1/documents/" + documentId + "/versions")
                        .file(new MockMultipartFile("file", "x.docx", "application/octet-stream", new byte[]{1}))
                        .with(fx.jwtFor(alice)))
                .andReturn().getResponse();
        assertThat(upload.getStatus()).isEqualTo(409);
        verify(ingestionGate, never()).submit(any());

        postAs(path("legal-hold"), admin, Map.of("reason", "dup"), 409); // already held
        postAs(path("legal-hold/release"), alice, Map.of("reason", "x"), 403); // not allowed to lift it
        postAs(path("legal-hold/release"), admin, Map.of("reason", "Matter closed"), 200);
        postAs(path("checkout"), alice, null, 200);
        assertThat(audit("DOCUMENT_LEGAL_HOLD_PLACED")).isEqualTo(1);
        assertThat(audit("DOCUMENT_LEGAL_HOLD_LIFTED")).isEqualTo(1);
    }

    // ------------------------------------------------------------------ never ambiguous (CICO-NFR-004)

    @Test
    void anExpiredLock_blocksNobody_andTheNextCheckOutTakesOverAndSaysSo() throws Exception {
        postAs(path("checkout"), alice, null, 200);
        fx.jdbc().update("update documents set checkout_expires_at = now() - interval '1 minute' where document_id = ?",
                documentId);

        // It no longer reads as held, and it no longer blocks Bob...
        assertThat(get200(path("checkout"), bob).get("state").asString()).isEqualTo("available");
        JsonNode taken = postAs(path("checkout"), bob, null, 200);
        assertThat(taken.get("checkedOutByMe").asBoolean()).isTrue();
        assertThat(holder()).isEqualTo(bob.getUserId());
        assertThat(audit("DOCUMENT_CHECKOUT_EXPIRED")).isEqualTo(1);

        // ...and Alice, finding out late, is told to start again rather than silently overwriting Bob.
        var late = mvc.perform(multipart(path("checkin"))
                        .file(new MockMultipartFile("file", "x.docx", "application/octet-stream", new byte[]{1}))
                        .with(fx.jwtFor(alice)))
                .andReturn().getResponse();
        assertThat(late.getStatus()).isEqualTo(409);
        verify(ingestionGate, never()).submit(any());
    }

    // ------------------------------------------------------------------ gaps closed after the first review

    @Test
    void aLegalHold_outlivesTheRecycleBin_itCannotBePurgedByHandOrBySweep() throws Exception {
        postAs(path("legal-hold"), admin, Map.of("reason", "Litigation 2026-114"), 200);
        // It reached the bin before the hold (a hold cannot be placed on nothing, but one can land after deletion).
        fx.jdbc().update("update documents set is_deleted = true, deleted_at = now() - interval '400 days' "
                + "where document_id = ?", documentId);
        fx.role(tenantId, "Purgers", new String[]{VIEW, "rbr.permanently-purge-documents"}, alice);

        var byHand = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/v1/recycle-bin/" + documentId).with(fx.jwtFor(alice)))
                .andReturn().getResponse();
        assertThat(byHand.getStatus()).isEqualTo(409);
        assertThat(json.readTree(byHand.getContentAsString()).get("code").asString()).isEqualTo("DOCUMENT_LEGAL_HOLD");

        // The hourly sweep (retention long past) must skip it as well.
        recycleBin.purgeExpired();
        assertThat(fx.count("select count(*) from documents where document_id = ? and purged_at is not null",
                documentId)).isZero();

        // Lifting the hold makes it purgeable again - the hold, not something else, was what stopped it.
        fx.jdbc().update("update documents set legal_hold = false, legal_hold_at = null, legal_hold_by = null, "
                + "legal_hold_reason = null where document_id = ?", documentId);
        recycleBin.purgeExpired();
        assertThat(fx.count("select count(*) from documents where document_id = ? and purged_at is not null",
                documentId)).isEqualTo(1);
    }

    @Test
    void aManualMetadataEdit_isRefusedWhileSomeoneElseHasItCheckedOut_butTheHolderMayEdit() throws Exception {
        fx.role(tenantId, "Metadata editors", new String[]{VIEW, "os.perform-ocr-extraction.edit-text"}, alice, bob);
        postAs(path("checkout"), alice, null, 200);

        var refused = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put(path("metadata")).contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{}").with(fx.jwtFor(bob)))
                .andReturn().getResponse();
        assertThat(refused.getStatus()).isEqualTo(409);
        assertThat(json.readTree(refused.getContentAsString()).get("code").asString()).isEqualTo("DOCUMENT_CHECKED_OUT");

        // The holder is the one editing: the lock check passes (whatever the schema then says about the values).
        var holder = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put(path("metadata")).contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content("{}").with(fx.jwtFor(alice)))
                .andReturn().getResponse();
        assertThat(holder.getStatus()).isNotEqualTo(409);
    }

    @Test
    void theDocumentList_marksWhatIsCheckedOutOrHeld_andOnlyThat() throws Exception {
        UUID other = fx.document(tenantId, fx.documentType(tenantId, "Memo"), alice, "Untouched memo");

        postAs(path("checkout"), alice, null, 200);
        assertThat(listLockState(alice, documentId)).isEqualTo("checked_out_by_me");
        assertThat(listLockState(bob, documentId)).isEqualTo("checked_out"); // someone else's - no name leaks into the list
        assertThat(listLockState(alice, other)).isNull();

        postAs(path("checkout/discard"), alice, null, 200);
        postAs(path("legal-hold"), admin, Map.of("reason", "Matter 7"), 200);
        assertThat(listLockState(bob, documentId)).isEqualTo("legal_hold");
        assertThat(listLockState(bob, other)).isNull();
    }

    /** The {@code lockState} the document browser shows for one document, as the given user sees it; null when free. */
    private String listLockState(User as, UUID document) throws Exception {
        JsonNode page = call(get("/api/v1/documents").param("all", "true").param("size", "100"), fx.jwtFor(as), null, 200);
        for (JsonNode row : page.get("content")) {
            if (document.toString().equals(row.get("documentId").asString())) {
                JsonNode state = row.get("lockState");
                return state == null || state.isNull() ? null : state.asString();
            }
        }
        throw new AssertionError("Document " + document + " is not in the list for " + as.getFirstName());
    }

    @Test
    void addingASignaturePage_isRefusedWhileSomeoneElseHasTheDocumentCheckedOut() throws Exception {
        postAs(path("checkout"), alice, null, 200);
        com.oak.dms.security.auth.TenantContext.set(tenantId, bob.getUserId());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> placement.addPage(documentId))
                .isInstanceOf(CheckoutException.class)
                .hasMessageContaining("checked out by");
    }

    @Test
    void theDatabase_refusesAHalfSetLock() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> fx.jdbc().update(
                        "update documents set checked_out_by = ? where document_id = ?", alice.getUserId(), documentId))
                .hasMessageContaining("ck_documents_checkout_all_or_none");
    }

    @Test
    void aUserInAnotherTenant_cannotSeeOrTakeTheLock() throws Exception {
        UUID otherTenant = fx.tenant();
        User outsider = fx.user(otherTenant, "Outsider");
        fx.role(otherTenant, "Editors", new String[]{VIEW, CHECK_OUT, CHECK_IN}, outsider);
        mvc.perform(get(path("checkout")).with(fx.jwtFor(outsider))).andExpect(
                org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
        mvc.perform(post(path("checkout")).with(fx.jwtFor(outsider))).andExpect(
                org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
        assertThat(holder()).isNull();
    }

    // ------------------------------------------------------------------ signatures stay intact, for every file type

    @Test
    void aFileThatCarriesASignature_isNeverCheckedOut_whateverItsType() throws Exception {
        byte[] signedPdf = "%PDF-1.7 1 0 obj<</Type/Sig/ByteRange[0 1 2 3]/Contents<00>>>endobj".getBytes(StandardCharsets.ISO_8859_1);
        byte[] signedOffice = zip("[Content_Types].xml", "_xmlsignatures/sig1.xml");
        byte[] signedOdf = zip("mimetype", "META-INF/documentsignatures.xml");

        assertRefusedAsSigned("contract.pdf", signedPdf);
        assertRefusedAsSigned("letter.docx", signedOffice);
        assertRefusedAsSigned("budget.xlsx", signedOffice);
        assertRefusedAsSigned("deck.pptx", signedOffice);
        assertRefusedAsSigned("memo.odt", signedOdf);
        assertRefusedAsSigned("slides.odp", signedOdf);

        // The same types with no signature in them check out normally - including a PDF and a plain text file.
        for (Object[] clean : new Object[][]{
                {"plain.pdf", "%PDF-1.7 1 0 obj<</Type/Page>>endobj".getBytes(StandardCharsets.ISO_8859_1)},
                {"deck.pptx", zip("[Content_Types].xml", "ppt/presentation.xml")},
                {"notes.txt", "mentions /ByteRange but is only text".getBytes(StandardCharsets.UTF_8)}}) {
            setFile((String) clean[0], (byte[]) clean[1]);
            postAs(path("checkout"), alice, null, 200);
            postAs(path("checkout/discard"), alice, null, 200);
        }
    }

    @Test
    void aVersionWithASignatureMark_cannotBeCheckedOut_andACheckInAfterItWasSignedIsRefused() throws Exception {
        UUID version = fx.jdbc().queryForObject("select latest_version_id from documents where document_id = ?",
                UUID.class, documentId);

        // Signed before check-out: refused outright.
        fx.jdbc().update("update document_versions set created_reason = 'signed_copy' where version_id = ?", version);
        assertThat(postAs(path("checkout"), alice, null, 409).get("code").asString()).isEqualTo("DOCUMENT_HAS_SIGNATURE");

        // Signed AFTER check-out (a standalone signing places a mark): the edited file must not replace it.
        fx.jdbc().update("update document_versions set created_reason = 'replacement_upload' where version_id = ?", version);
        postAs(path("checkout"), alice, null, 200);
        fx.jdbc().update("update document_versions set created_reason = 'signed_copy' where version_id = ?", version);

        var response = mvc.perform(multipart(path("checkin"))
                        .file(new MockMultipartFile("file", "edited.docx", "application/octet-stream", new byte[]{9}))
                        .with(fx.jwtFor(alice)))
                .andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(409);
        assertThat(json.readTree(response.getContentAsString()).get("code").asString()).isEqualTo("DOCUMENT_HAS_SIGNATURE");
        verify(ingestionGate, never()).submit(any());
        assertThat(holder()).isEqualTo(alice.getUserId()); // still hers, so she can discard
    }

    @Test
    void aFileThatCannotBeRead_isNotCheckedOutOnTrust_andTheErrorHidesStoragePaths() throws Exception {
        // A document whose stored file is missing: we cannot show it carries no signature, so refuse - plainly.
        UUID noFile = fx.document(tenantId, fx.documentType(tenantId, "Memo"), alice, "Lost file");
        fx.jdbc().update("update document_versions set original_filename = 'lost.pdf' where version_id = "
                + "(select latest_version_id from documents where document_id = ?)", noFile);

        var response = mvc.perform(post("/api/v1/documents/" + noFile + "/checkout").with(fx.jwtFor(alice)))
                .andReturn().getResponse();
        assertThat(response.getStatus()).isEqualTo(503);
        JsonNode body = json.readTree(response.getContentAsString());
        assertThat(body.get("code").asString()).isEqualTo("DOCUMENT_FILE_UNAVAILABLE");
        assertThat(body.get("message").asString()).doesNotContain("documents/").doesNotContain("Object not found");
        assertThat(fx.jdbc().queryForObject("select checked_out_by from documents where document_id = ?", UUID.class, noFile))
                .isNull();
    }

    @Test
    void theStatusSaysWhoLastEditedAndWhen_andWhichVersion() throws Exception {
        // Version 1: it was only ever uploaded - by Alice, who owns it.
        JsonNode first = get200(path("checkout"), bob);
        assertThat(first.get("lastEditedByName").asString()).contains("Alice");
        assertThat(first.get("lastEditedVersion").asInt()).isEqualTo(1);
        assertThat(first.get("lastEditedAt").isNull()).isFalse();

        // Bob saves a new version (an upload, a check-in or a save from the online editor all make one).
        fx.newVersion(tenantId, documentId, bob);
        JsonNode second = get200(path("checkout"), alice);
        assertThat(second.get("lastEditedByName").asString()).contains("Bob");
        assertThat(second.get("lastEditedVersion").asInt()).isEqualTo(2);
        assertThat(second.get("lastEditedById").asString()).isEqualTo(bob.getUserId().toString());
    }

    @Test
    void aDocumentWithASignatureMark_showsAsSigned_inTheStatus_evenWithNoLock() throws Exception {
        assertThat(get200(path("checkout"), alice).get("state").asString()).isEqualTo("available");

        fx.jdbc().update("update document_versions set created_reason = 'signed_copy' where version_id = "
                + "(select latest_version_id from documents where document_id = ?)", documentId);

        JsonNode status = get200(path("checkout"), alice);
        assertThat(status.get("state").asString()).isEqualTo("signature_locked");
        assertThat(status.get("signatureLocked").asBoolean()).isTrue();
        assertThat(fx.jdbc().queryForObject("select signature_locked_at from documents where document_id = ?",
                Object.class, documentId)).isNull(); // proving it is the signature, not the DSI lock, that is shown
    }

    private void setFile(String filename, byte[] content) {
        UUID version = fx.jdbc().queryForObject("select latest_version_id from documents where document_id = ?",
                UUID.class, documentId);
        fx.jdbc().update("update document_versions set original_filename = ?, created_reason = 'replacement_upload' "
                + "where version_id = ?", filename, version);
        fx.putVersionContent(version, content);
    }

    private void assertRefusedAsSigned(String filename, byte[] content) throws Exception {
        setFile(filename, content);
        JsonNode refused = postAs(path("checkout"), alice, null, 409);
        assertThat(refused.get("code").asString()).as(filename).isEqualTo("DOCUMENT_HAS_SIGNATURE");
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

    // ------------------------------------------------------------------ helpers

    private String path(String suffix) {
        return "/api/v1/documents/" + documentId + "/" + suffix;
    }

    private UUID holder() {
        return fx.jdbc().queryForObject("select checked_out_by from documents where document_id = ?", UUID.class, documentId);
    }

    private long audit(String action) {
        return fx.count("select count(*) from audit_events where tenant_id = ? and action = ? and object_id = ?",
                tenantId, action, documentId);
    }

    private IngestionTicketResponse ticket(ScanResultStatus status) {
        return new IngestionTicketResponse(UUID.randomUUID(), status, documentId, null, "edited.docx", 2L, null,
                status == ScanResultStatus.infected ? "Eicar" : null, null, false, OffsetDateTime.now(), null);
    }
}
