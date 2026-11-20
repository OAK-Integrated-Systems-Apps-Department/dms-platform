package com.oak.dms.checkout;

import com.oak.dms.common.exception.ApiException;
import com.oak.dms.document.DocumentVersionService;
import com.oak.dms.scanning.dto.IngestionTicketResponse;
import com.oak.dms.scanning.enums.ScanResultStatus;
import com.oak.dms.scanning.service.IngestionGate;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.user.User;
import com.oak.dms.workflow.support.WorkflowApiSupport;
import com.oak.dms.workflow.support.WorkflowFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * DOC-05: a plain version upload and a restore are refused - with the existing {@code 409 DOCUMENT_HAS_SIGNATURE} -
 * while the document's current version is signed, exactly as a check-in and the editor already were. "Signed" is
 * whatever {@link SignedContentGuard} says: a signed_copy mark, or a signature inside the file itself (PDF,
 * Word/Excel/PowerPoint, OpenDocument - read from real stored content here). The workflow signature lock keeps its own
 * refusal, access rules keep theirs, and a refusal happens before the ingestion gate: nothing is scanned, stored or
 * versioned, and the signed version and the history are untouched.
 * <p>
 * Real database, real storage and encryption, real access checks and real guard; the ingestion gate is mocked (as in
 * {@code DocumentCheckoutIT}) so "never reaches the gate" can be asserted. A valid signature RECORD (rule 1) is
 * covered with the real guard over stubbed repositories in {@code DocumentVersionServiceSignatureGuardTest}.
 */
@SpringBootTest
@Import(WorkflowFixtures.class)
class DocumentVersionSignatureGuardIT extends WorkflowApiSupport {

    private static final String VIEW = "docm.documents.view";
    private static final String CREATE = "docm.documents.create";
    private static final String RESTORE = "dvc.restore-prior-version";

    @MockitoBean IngestionGate ingestionGate;
    @Autowired DocumentVersionService versionService;

    private UUID tenantId;
    private User alice;       // may upload and restore
    private User nobody;      // same tenant, no permissions
    private UUID documentId;
    private UUID oldVersion;  // the one a restore would put back
    private UUID currentVersion;

    @BeforeEach
    void world() {
        tenantId = fx.tenant();
        UUID type = fx.documentType(tenantId, "Contract");
        alice = fx.user(tenantId, "Alice");
        nobody = fx.user(tenantId, "Nobody");
        fx.role(tenantId, "Editors", new String[]{VIEW, CREATE, RESTORE}, alice);
        documentId = fx.document(tenantId, type, alice, "Master agreement");
        oldVersion = fx.jdbc().queryForObject("select latest_version_id from documents where document_id = ?",
                UUID.class, documentId);
        fx.jdbc().update("update document_versions set original_filename = 'Master agreement.docx' where version_id = ?",
                oldVersion);
        fx.putVersionContent(oldVersion, "OLD BYTES".getBytes(StandardCharsets.UTF_8));
        currentVersion = fx.newVersion(tenantId, documentId, alice);
        fx.jdbc().update("update document_versions set original_filename = 'Master agreement.docx' where version_id = ?",
                currentVersion);
        fx.putVersionContent(currentVersion, "CURRENT BYTES".getBytes(StandardCharsets.UTF_8));
        when(ingestionGate.submit(any())).thenReturn(ticket());
    }

    // ------------------------------------------------------------------ unsigned: as before

    @Test
    void anUnsignedDocument_acceptsAnUploadAndARestore_asBefore() throws Exception {
        assertThat(upload(alice).getStatus()).isEqualTo(202);
        assertThat(restore(alice).getStatus()).isEqualTo(202);
        verify(ingestionGate, times(2)).submit(any());
    }

    // ------------------------------------------------------------------ signed by a mark (standalone signing's own record)

    @Test
    void aSignedCopyCurrentVersion_refusesAnUploadAndARestore_andNothingChanges() throws Exception {
        fx.jdbc().update("update document_versions set created_reason = 'signed_copy' where version_id = ?", currentVersion);
        String before = snapshot();

        assertRefused(upload(alice), "DOCUMENT_HAS_SIGNATURE");
        assertRefused(restore(alice), "DOCUMENT_HAS_SIGNATURE");

        verify(ingestionGate, never()).submit(any());
        assertThat(snapshot()).as("history, current version and scan rows are exactly as they were").isEqualTo(before);
    }

    // ------------------------------------------------------------------ signed inside the file (read from real content)

    @Test
    void aFileThatCarriesItsOwnSignature_refusesAnUploadAndARestore_whateverItsType() throws Exception {
        byte[] signedPdf = "%PDF-1.7 1 0 obj<</Type/Sig/ByteRange[0 1 2 3]/Contents<00>>>endobj"
                .getBytes(StandardCharsets.ISO_8859_1);
        byte[] signedOffice = zip("[Content_Types].xml", "_xmlsignatures/sig1.xml");
        byte[] signedOdf = zip("mimetype", "META-INF/documentsignatures.xml");

        for (Object[] signed : new Object[][]{{"contract.pdf", signedPdf}, {"letter.docx", signedOffice},
                {"budget.xlsx", signedOffice}, {"deck.pptx", signedOffice}, {"memo.odt", signedOdf}}) {
            setCurrentFile((String) signed[0], (byte[]) signed[1]);
            String before = snapshot();
            assertRefused(upload(alice), "DOCUMENT_HAS_SIGNATURE");
            assertRefused(restore(alice), "DOCUMENT_HAS_SIGNATURE");
            assertThat(snapshot()).as((String) signed[0]).isEqualTo(before);
        }
        verify(ingestionGate, never()).submit(any());
    }

    @Test
    void theSameTypesWithNoSignatureInside_areNotAffected() throws Exception {
        setCurrentFile("plain.pdf", "%PDF-1.7 1 0 obj<</Type/Page>>endobj".getBytes(StandardCharsets.ISO_8859_1));
        assertThat(upload(alice).getStatus()).isEqualTo(202);
        setCurrentFile("notes.txt", "mentions /ByteRange but is only text".getBytes(StandardCharsets.UTF_8));
        assertThat(restore(alice).getStatus()).isEqualTo(202);
        verify(ingestionGate, times(2)).submit(any());
    }

    // ------------------------------------------------------------------ the existing protections keep their own answers

    @Test
    void aWorkflowSignatureLock_keepsItsOwnRefusal_onBothRoutes() throws Exception {
        fx.jdbc().update("update documents set signature_locked_at = now(), signature_locked_version_id = "
                + "latest_version_id where document_id = ?", documentId);
        // ...even when the current version is also signed some other way
        fx.jdbc().update("update document_versions set created_reason = 'signed_copy' where version_id = ?", currentVersion);

        assertRefused(upload(alice), "DOCUMENT_SIGNATURE_LOCKED");
        assertRefused(restore(alice), "DOCUMENT_SIGNATURE_LOCKED");
        verify(ingestionGate, never()).submit(any());
    }

    @Test
    void aCheckOutHeldByAnotherUser_keepsItsOwnRefusal() throws Exception {
        User bob = fx.user(tenantId, "Bob");
        fx.role(tenantId, "More editors", new String[]{VIEW, CREATE, RESTORE}, bob);
        fx.jdbc().update("update documents set checked_out_by = ?, checked_out_at = now(), checkout_expires_at = now() + interval '1 hour' "
                        + "where document_id = ?",
                alice.getUserId(), documentId);
        fx.jdbc().update("update document_versions set created_reason = 'signed_copy' where version_id = ?", currentVersion);

        // the check-out refusal comes first, as it always did
        assertRefused(upload(bob), "DOCUMENT_CHECKED_OUT");
        assertRefused(restore(bob), "DOCUMENT_CHECKED_OUT");
        verify(ingestionGate, never()).submit(any());
    }

    @Test
    void accessRules_arePreserved_andNeverRevealWhetherTheDocumentIsSigned() throws Exception {
        fx.jdbc().update("update document_versions set created_reason = 'signed_copy' where version_id = ?", currentVersion);

        // same tenant, no permission: 403 before anything about the document is looked at
        assertThat(upload(nobody).getStatus()).isEqualTo(403);
        assertThat(restore(nobody).getStatus()).isEqualTo(403);

        // another tenant (with the permissions in its own tenant): the document is simply not found
        UUID otherTenant = fx.tenant();
        User outsider = fx.user(otherTenant, "Outsider");
        fx.role(otherTenant, "Outside editors", new String[]{VIEW, CREATE, RESTORE}, outsider);
        assertThat(upload(outsider).getStatus()).isEqualTo(404);
        assertThat(restore(outsider).getStatus()).isEqualTo(404);

        verify(ingestionGate, never()).submit(any());
    }

    // ------------------------------------------------------------------ bulk import goes through the same method

    @Test
    void theCallBulkImportMakesForANewVersion_isRefusedForASignedDocument_asAClientError() {
        TenantContext.set(tenantId, alice.getUserId());

        // exactly the arguments ImportRecordCreator passes: a new version of an existing document, low priority
        assertThat(versionService.upload(documentId, "row.docx", 3, new ByteArrayInputStream(new byte[]{1, 2, 3}),
                "Uploaded again by bulk import", List.of(), true)).isNotNull();
        verify(ingestionGate, times(1)).submit(any());

        fx.jdbc().update("update document_versions set created_reason = 'signed_copy' where version_id = ?", currentVersion);
        assertThatThrownBy(() -> versionService.upload(documentId, "row.docx", 3,
                new ByteArrayInputStream(new byte[]{1, 2, 3}), "Uploaded again by bulk import", List.of(), true))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    // ImportRecordCreator records any 4xx ApiException as that row's rejection (isRejection)
                    assertThat(e.getStatus().is4xxClientError()).isTrue();
                    assertThat(e.getCode()).isEqualTo("DOCUMENT_HAS_SIGNATURE");
                });
        verify(ingestionGate, times(1)).submit(any()); // still only the first, unsigned call
    }

    // ------------------------------------------------------------------ helpers

    private MockHttpServletResponse upload(User as) throws Exception {
        return mvc.perform(multipart("/api/v1/documents/" + documentId + "/versions")
                        .file(new MockMultipartFile("file", "x.docx", "application/octet-stream", new byte[]{1, 2, 3}))
                        .with(fx.jwtFor(as)))
                .andReturn().getResponse();
    }

    private MockHttpServletResponse restore(User as) throws Exception {
        return mvc.perform(post("/api/v1/documents/" + documentId + "/versions/" + oldVersion + "/restore")
                        .with(fx.jwtFor(as)))
                .andReturn().getResponse();
    }

    private void assertRefused(MockHttpServletResponse response, String code) throws Exception {
        assertThat(response.getStatus()).isEqualTo(409);
        assertThat(json.readTree(response.getContentAsString()).get("code").asString()).isEqualTo(code);
    }

    /** Makes the current version a different file (name and stored bytes), unsigned by record. */
    private void setCurrentFile(String filename, byte[] content) {
        fx.jdbc().update("update document_versions set original_filename = ?, created_reason = 'replacement_upload' "
                + "where version_id = ?", filename, currentVersion);
        fx.putVersionContent(currentVersion, content);
    }

    /** Everything a rejected operation must leave alone, as one comparable string. */
    private String snapshot() {
        return fx.jdbc().queryForObject("""
                select (select count(*) from document_versions where document_id = ?) || '|'
                    || (select count(*) from scan_results where tenant_id = ?) || '|'
                    || (select count(*) from document_signatures where document_id = ?) || '|'
                    || (select current_version_id from documents where document_id = ?) || '|'
                    || (select latest_version_id from documents where document_id = ?) || '|'
                    || (select string_agg(version_id || ':' || created_reason || ':' || scan_status, ',' order by version_id)
                          from document_versions where document_id = ?)
                """, String.class, documentId, tenantId, documentId, documentId, documentId, documentId);
    }

    private IngestionTicketResponse ticket() {
        return new IngestionTicketResponse(UUID.randomUUID(), ScanResultStatus.pending, documentId, null, "x.docx", 3L,
                null, null, null, false, OffsetDateTime.now(), null);
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
}
