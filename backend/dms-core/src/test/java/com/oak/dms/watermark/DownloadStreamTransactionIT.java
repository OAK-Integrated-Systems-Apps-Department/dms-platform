package com.oak.dms.watermark;

import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.service.AuditService;
import com.oak.dms.document.DocumentService;
import com.oak.dms.document.DocumentVersion;
import com.oak.dms.document.DocumentVersionService;
import com.oak.dms.document.dto.DownloadedDocument;
import com.oak.dms.storage.dto.DownloadedObject;
import com.oak.dms.storage.entity.StorageLocation;
import com.oak.dms.storage.service.ObjectStorageService;
import com.oak.dms.storage.service.ObjectStorageServiceFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.AdditionalAnswers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

/**
 * DOC-18 against real PostgreSQL and Spring's real transaction handling: the storage stream a download opens is closed
 * when the download does not complete - the audit write fails, or the transaction is rolled back, or its COMMIT fails after
 * the service method has returned - and is left open and readable when the transaction commits, so the controller's
 * streaming body can send it. The failing commit is made by a real database constraint: a version row with no scan result
 * violates the scan gate's deferred trigger (V39), which PostgreSQL checks only at commit.
 */
@SpringBootTest
class DownloadStreamTransactionIT extends WatermarkFixtures {

    @Autowired private DocumentService documentService;
    @Autowired private DocumentVersionService versionService;
    @MockitoSpyBean private ObjectStorageServiceFactory storageFactory;
    @MockitoSpyBean private AuditService auditService;

    /** A storage stream that records being closed. */
    private static final class Tracked extends FilterInputStream {
        final AtomicBoolean closed = new AtomicBoolean();

        Tracked(InputStream in) {
            super(in);
        }

        @Override
        public void close() throws IOException {
            closed.set(true);
            super.close();
        }
    }

    private final List<Tracked> opened = new CopyOnWriteArrayList<>();

    /** Every stream the storage layer hands out is wrapped, so a test can see whether it was closed. */
    @BeforeEach
    void trackStorageStreams() {
        doAnswer(call -> {
            ObjectStorageService real = (ObjectStorageService) call.callRealMethod();
            ObjectStorageService tracking = Mockito.mock(ObjectStorageService.class, AdditionalAnswers.delegatesTo(real));
            doAnswer(download -> {
                DownloadedObject object = real.download(download.getArgument(0), download.getArgument(1));
                Tracked stream = new Tracked(object.content());
                opened.add(stream);
                return new DownloadedObject(stream, object.sizeBytes(), object.contentType());
            }).when(tracking).download(any(), anyString());
            return tracking;
        }).when(storageFactory).resolve(any());
    }

    /** Makes the surrounding transaction's COMMIT fail: a version with no scan result, checked by the deferred gate trigger. */
    private void failTheCommitWithARealConstraint(Fixture f) {
        DocumentVersion first = versionRepository.findById(f.versionId()).orElseThrow();
        versionRepository.save(DocumentVersion.builder().versionId(UUID.randomUUID()).documentId(f.documentId())
                .tenantId(f.tenantId()).versionNumber(99).majorVersion(99).minorVersion(0)
                .storageLocationId(first.getStorageLocationId()).storageObjectKey("no-scan-result/" + UUID.randomUUID())
                .originalFilename("x.pdf").mimeType(PDF_MIME).sizeBytes(1).fileHash(sha256(new byte[]{1}))
                .createdReason("replacement_upload").scanStatus(DocumentVersion.SCAN_STATUS_CLEAN).createdBy(f.userId())
                .build());
    }

    private Tracked theOneStreamOpened() {
        assertThat(opened).as("the download opened exactly one storage stream").hasSize(1);
        return opened.get(0);
    }

    private int downloadEvents(Fixture f) {
        return auditCount(AuditActionCatalog.DOCUMENT_DOWNLOADED, f.documentId());
    }

    // ------------------------------------------------------------------ 1. a committed transaction

    @Test
    void aCommittedTransaction_returnsAnOpenReadableStream_andWritesOneAuditEvent() throws Exception {
        byte[] content = minimalPdf();
        Fixture f = fixtureWithContent("memo.pdf", PDF_MIME, content);

        DownloadedDocument plain = documentService.downloadDocument(f.documentId());
        Tracked stream = theOneStreamOpened();
        assertThat(stream.closed.get()).as("handed to the caller, who owns it now").isFalse();
        assertThat(plain.content().readAllBytes()).isEqualTo(content);
        plain.content().close();
        assertThat(stream.closed.get()).isTrue();
        assertThat(downloadEvents(f)).isEqualTo(1);

        opened.clear();
        DownloadedDocument byVersion = versionService.download(f.documentId(), f.versionId());
        Tracked second = theOneStreamOpened();
        assertThat(second.closed.get()).isFalse();
        assertThat(byVersion.content().readAllBytes()).isEqualTo(content);
        byVersion.content().close();
        assertThat(downloadEvents(f)).as("one event per download, none duplicated").isEqualTo(2);
    }

    // ------------------------------------------------------------------ 2. a failed (rolled back) transaction

    @Test
    void aTransactionThatRollsBackAfterTheServiceReturned_closesTheStream_andLeavesNoAuditEvent() {
        Fixture f = fixtureWithContent("memo.pdf", PDF_MIME, minimalPdf());

        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
            documentService.downloadDocument(f.documentId());           // joins this transaction and returns the open stream
            assertThat(theOneStreamOpened().closed.get()).as("still open while the transaction runs").isFalse();
            throw new IllegalStateException("the surrounding work failed");
        })).isInstanceOf(IllegalStateException.class).hasMessage("the surrounding work failed");

        assertThat(theOneStreamOpened().closed.get()).as("rolled back: the caller never used the stream").isTrue();
        assertThat(downloadEvents(f)).as("the audit event was rolled back with it").isZero();
    }

    @Test
    void aTransactionThatCommitsAfterTheServiceReturned_leavesTheStreamOpen() throws Exception {
        Fixture f = fixtureWithContent("memo.pdf", PDF_MIME, minimalPdf());

        DownloadedDocument[] returned = new DownloadedDocument[1];
        transactions.executeWithoutResult(status -> returned[0] = documentService.downloadDocument(f.documentId()));

        assertThat(theOneStreamOpened().closed.get()).as("committed: the caller still owns an open stream").isFalse();
        assertThat(returned[0].content().readAllBytes()).isEqualTo(minimalPdf());
        returned[0].content().close();
        assertThat(downloadEvents(f)).isEqualTo(1);
    }

    // ------------------------------------------------------------------ 3. the audit write fails

    @Test
    void anAuditWriteThatFailsAfterTheStreamWasOpened_closesIt_forBothDownloads() {
        Fixture f = fixtureWithContent("memo.pdf", PDF_MIME, minimalPdf());
        doThrow(new IllegalStateException("audit store unavailable")).when(auditService)
                .persist(argThat(b -> b != null && AuditActionCatalog.DOCUMENT_DOWNLOADED.equals(b.action())));

        assertThatThrownBy(() -> documentService.downloadDocument(f.documentId()))
                .isInstanceOf(IllegalStateException.class).hasMessage("audit store unavailable");
        assertThat(theOneStreamOpened().closed.get()).as("normal download").isTrue();

        opened.clear();
        assertThatThrownBy(() -> versionService.download(f.documentId(), f.versionId()))
                .isInstanceOf(IllegalStateException.class).hasMessage("audit store unavailable");
        assertThat(theOneStreamOpened().closed.get()).as("version download").isTrue();
        assertThat(downloadEvents(f)).isZero();
    }

    // ------------------------------------------------------------------ 4. the COMMIT itself fails, after the method returned

    @Test
    void aCommitThatFailsInTheDatabaseAfterTheMethodReturned_closesTheStream_forBothDownloads() {
        Fixture f = fixtureWithContent("memo.pdf", PDF_MIME, minimalPdf());
        doAnswer(call -> {
            Object saved = call.callRealMethod();            // the audit row is written normally...
            failTheCommitWithARealConstraint(f);              // ...but the commit will violate the scan gate's deferred trigger
            return saved;
        }).when(auditService).persist(argThat(b -> b != null && AuditActionCatalog.DOCUMENT_DOWNLOADED.equals(b.action())));
        int versionsBefore = versionRepository.findAll().stream().filter(v -> v.getDocumentId().equals(f.documentId())).toList().size();

        // The service method body finishes normally (it has returned the stream); the exception comes from the commit and
        // its root cause is PostgreSQL's own refusal (the deferred scan-gate trigger).
        assertThatThrownBy(() -> documentService.downloadDocument(f.documentId()))
                .as("the commit, not the method body, failed")
                .hasRootCauseInstanceOf(java.sql.SQLException.class);
        assertThat(theOneStreamOpened().closed.get()).as("normal download").isTrue();

        opened.clear();
        assertThatThrownBy(() -> versionService.download(f.documentId(), f.versionId()))
                .hasRootCauseInstanceOf(java.sql.SQLException.class);
        assertThat(theOneStreamOpened().closed.get()).as("version download").isTrue();

        assertThat(downloadEvents(f)).as("the failed commits left no audit event").isZero();
        assertThat(versionRepository.findAll().stream().filter(v -> v.getDocumentId().equals(f.documentId())).count())
                .as("and no stray version").isEqualTo(versionsBefore);
    }
}
