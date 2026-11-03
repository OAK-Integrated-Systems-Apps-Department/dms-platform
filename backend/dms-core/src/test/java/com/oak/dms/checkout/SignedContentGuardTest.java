package com.oak.dms.checkout;

import com.oak.dms.document.Document;
import com.oak.dms.document.DocumentContentReader;
import com.oak.dms.document.DocumentNotScannedException;
import com.oak.dms.document.DocumentVersion;
import com.oak.dms.document.DocumentVersionRepository;
import com.oak.dms.document.dto.DownloadedDocument;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.signature.entity.DocumentSignature;
import com.oak.dms.signature.repository.DocumentSignatureRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Signatures stay intact, for every file type: each way a version can count as signed, and the ways it must not.
 */
class SignedContentGuardTest {

    private final UUID tenantId = UUID.randomUUID();
    private final UUID documentId = UUID.randomUUID();
    private final UUID currentVersion = UUID.randomUUID();

    private DocumentSignatureRepository signatures;
    private DocumentVersionRepository versions;
    private DocumentContentReader reader;
    private SignedContentGuard guard;
    private Document document;

    @BeforeEach
    void setUp() {
        signatures = mock(DocumentSignatureRepository.class);
        versions = mock(DocumentVersionRepository.class);
        reader = mock(DocumentContentReader.class);
        guard = new SignedContentGuard(signatures, versions, reader);
        TenantContext.set(tenantId, UUID.randomUUID());

        document = mock(Document.class);
        when(document.getTenantId()).thenReturn(tenantId);
        when(document.getDocumentId()).thenReturn(documentId);
        when(document.getCurrentVersionId()).thenReturn(currentVersion);
        when(document.getTitle()).thenReturn("Master agreement");
        when(signatures.findByTenantIdAndDocumentIdOrderBySignedAtDesc(tenantId, documentId)).thenReturn(List.of());
        stubVersion("replacement_upload");
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void stubVersion(String createdReason) {
        DocumentVersion version = mock(DocumentVersion.class);
        when(version.getCreatedReason()).thenReturn(createdReason);
        when(versions.findByVersionIdAndDocumentIdAndTenantIdAndDeletedFalse(currentVersion, documentId, tenantId))
                .thenReturn(Optional.of(version));
    }

    private DocumentSignature signature(boolean valid, UUID versionId) {
        DocumentSignature s = mock(DocumentSignature.class);
        when(s.isValid()).thenReturn(valid);
        when(s.getVersionId()).thenReturn(versionId);
        return s;
    }

    private void fileIs(String filename, String content) {
        when(reader.currentForEditing(tenantId, documentId)).thenReturn(new DocumentContentReader.EditableVersion(
                currentVersion, 1, filename, content.length(), OffsetDateTime.now()));
        when(reader.openCurrentForEditing(tenantId, documentId)).thenAnswer(inv -> new DownloadedDocument(
                new ByteArrayInputStream(content.getBytes(StandardCharsets.ISO_8859_1)), content.length(),
                "application/octet-stream", filename));
    }

    // ------------------------------------------------------------------ (1) the DMS's own signature records

    @Test
    void aValidSignatureOnTheCurrentVersion_blocksEditing() {
        DocumentSignature valid = signature(true, currentVersion);
        when(signatures.findByTenantIdAndDocumentIdOrderBySignedAtDesc(tenantId, documentId))
                .thenReturn(List.of(valid));

        assertThat(guard.protectedByRecord(document)).isTrue();
        assertThatThrownBy(() -> guard.guardSaving(document))
                .isInstanceOf(CheckoutException.class)
                .hasMessageContaining("Master agreement")
                .hasMessageContaining("digital signature");
        assertThatThrownBy(() -> guard.guardOpening(document)).isInstanceOf(CheckoutException.class);
    }

    @Test
    void anInvalidatedSignature_orOneOnAnOlderVersion_doesNotBlock() throws Exception {
        DocumentSignature invalidated = signature(false, currentVersion);       // no longer valid
        DocumentSignature forOlderVersion = signature(true, UUID.randomUUID()); // valid, but for a different version
        when(signatures.findByTenantIdAndDocumentIdOrderBySignedAtDesc(tenantId, documentId))
                .thenReturn(List.of(invalidated, forOlderVersion));
        fileIs("a.txt", "plain");

        assertThat(guard.protectedByRecord(document)).isFalse();
        guard.guardOpening(document); // does not throw
    }

    // ------------------------------------------------------------------ (2) a burned-in signature mark

    @Test
    void aVersionMadeByPlacingASignatureMark_blocksEditing() {
        stubVersion("signed_copy");
        assertThat(guard.protectedByRecord(document)).isTrue();
        assertThatThrownBy(() -> guard.guardSaving(document)).isInstanceOf(CheckoutException.class);
    }

    // ------------------------------------------------------------------ (3) a signature inside the file

    @Test
    void aPdfThatAlreadyContainsASignature_isRefusedOnOpening() {
        fileIs("signed-elsewhere.pdf", "%PDF-1.7 <</Type/Sig/ByteRange[0 1 2 3]>>");

        assertThat(guard.protectedByRecord(document)).isFalse(); // nothing in the DMS's records...
        assertThatThrownBy(() -> guard.guardOpening(document))   // ...but the file itself is signed
                .isInstanceOf(CheckoutException.class)
                .hasMessageContaining("inside the file");
        guard.guardSaving(document); // saving asks only the DMS's records, so this is not what blocks it
    }

    @Test
    void anUnsignedPdf_isAllowed() throws Exception {
        fileIs("plain.pdf", "%PDF-1.7 <</Type/Page>>");
        guard.guardOpening(document);
    }

    @Test
    void aTextFile_isNeverEvenRead() throws Exception {
        fileIs("notes.txt", "mentions /ByteRange but is only text");
        guard.guardOpening(document);
        verify(reader, never()).openCurrentForEditing(any(), any());
    }

    @Test
    void aVersionNotScannedCleanYet_hasNothingToProtect_andIsNotAnError() throws Exception {
        fileIs("report.pdf", "%PDF");
        when(reader.openCurrentForEditing(tenantId, documentId)).thenThrow(new DocumentNotScannedException(documentId));
        guard.guardOpening(document); // does not throw
    }

    @Test
    void aFileThatCannotBeRead_failsClosed_withAPlainMessage() throws Exception {
        fileIs("report.pdf", "%PDF");
        when(reader.openCurrentForEditing(tenantId, documentId)).thenThrow(
                new com.oak.dms.storage.exception.StorageObjectNotFoundException("documents/t/d/v1/secret-key"));

        assertThatThrownBy(() -> guard.guardOpening(document))
                .isInstanceOf(CheckoutException.class)
                .hasMessageContaining("can't be read right now")
                .hasMessageNotContaining("secret-key")      // the storage path never reaches the user
                .hasMessageNotContaining("Object not found");
    }

    @Test
    void aDocumentWithNoVersionYet_isNotBlocked() {
        when(document.getCurrentVersionId()).thenReturn(null);
        when(document.getLatestVersionId()).thenReturn(null);
        assertThat(guard.protectedByRecord(document)).isFalse();
    }
}
