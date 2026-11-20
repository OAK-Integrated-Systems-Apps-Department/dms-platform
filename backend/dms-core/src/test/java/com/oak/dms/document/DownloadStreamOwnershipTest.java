package com.oak.dms.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oak.dms.audit.helper.AuditEventBuilder;
import com.oak.dms.audit.service.AuditService;
import com.oak.dms.checkout.DocumentEditGuard;
import com.oak.dms.checkout.SignedContentGuard;
import com.oak.dms.document.dto.DownloadedDocument;
import com.oak.dms.folder.AccessPrincipals;
import com.oak.dms.folder.DocumentAccessService;
import com.oak.dms.folder.FolderAccessService;
import com.oak.dms.folder.FolderService;
import com.oak.dms.metadata.repository.DocumentTypeRepository;
import com.oak.dms.metadata.service.DocumentTypeFieldService;
import com.oak.dms.metadata.service.MetadataValidationService;
import com.oak.dms.ocrworker.OcrEnqueueService;
import com.oak.dms.ocrworker.OcrProperties;
import com.oak.dms.ocrworker.OcrWorkerClient;
import com.oak.dms.ocrworker.ProcessingJobRepository;
import com.oak.dms.scanning.service.IngestionGate;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.signature.service.DocumentSignatureLockService;
import com.oak.dms.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.io.ByteArrayInputStream;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DOC-18: who owns the storage stream while a download is being set up. DB-free: the real {@link DocumentServiceImpl}
 * and {@link DocumentVersionService} over mocked collaborators and a tracked stream, with Spring's real
 * {@code @Transactional} interceptor over a stub transaction manager that - like the real one - runs its after-completion
 * callbacks. The stream is closed when the audit write fails after it was opened, and when the transaction does not commit
 * after the method has returned (which no {@code catch} in the method could see); on a normal return, and on a commit,
 * it is left open for the caller (the controller's streaming body).
 */
class DownloadStreamOwnershipTest {

    

    private final UUID tenantId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID documentId = UUID.randomUUID();
    private final UUID versionId = UUID.randomUUID();

    /** The storage stream: records being closed. */
    static final class Tracked extends ByteArrayInputStream {
        final AtomicBoolean closed = new AtomicBoolean();

        Tracked() {
            super(new byte[]{1, 2, 3});
        }

        @Override
        public void close() {
            closed.set(true);
        }
    }

    private Tracked tracked;
    private AuditService auditService;
    private Document document;
    private DocumentVersion version;
    private DocumentRepository documentRepository;
    private DocumentVersionRepository versionRepository;
    private DocumentAccessService access;
    private VersionContent versionContent;

    @BeforeEach
    void setUp() {
        TenantContext.set(tenantId, userId);
        tracked = new Tracked();
        document = Document.builder().documentId(documentId).tenantId(tenantId).documentTypeId(UUID.randomUUID())
                .title("contract.pdf").latestVersionId(versionId).currentVersionId(versionId).ownerUserId(userId).build();
        version = DocumentVersion.builder().versionId(versionId).documentId(documentId).tenantId(tenantId)
                .versionNumber(1).majorVersion(1).minorVersion(0).originalFilename("contract.pdf").sizeBytes(3)
                .mimeType("application/pdf").build();
        documentRepository = mock(DocumentRepository.class);
        when(documentRepository.findByDocumentIdAndTenantIdAndDeletedFalse(documentId, tenantId))
                .thenReturn(Optional.of(document));
        versionRepository = mock(DocumentVersionRepository.class);
        when(versionRepository.findByVersionIdAndDocumentIdAndTenantIdAndDeletedFalse(versionId, documentId, tenantId))
                .thenReturn(Optional.of(version));
        access = mock(DocumentAccessService.class);
        when(access.require(any(), any(), any())).thenAnswer(i -> i.getArgument(1));
        versionContent = mock(VersionContent.class);
        when(versionContent.open(any(DocumentVersion.class)))
                .thenAnswer(i -> new DownloadedDocument(tracked, 3, "application/pdf", "contract.pdf"));
        auditService = mock(AuditService.class);
        when(auditService.event(anyString())).thenAnswer(i -> new AuditEventBuilder(auditService, i.getArgument(0)));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void auditPersistFails() {
        when(auditService.persist(any())).thenThrow(new IllegalStateException("audit chain lock timeout"));
    }

    private DocumentServiceImpl documentService() {
        DefaultDownloadWatermark watermark = mock(DefaultDownloadWatermark.class);
        when(watermark.apply(any(), any())).thenAnswer(i -> new DefaultDownloadWatermark.Outcome(i.getArgument(0), null));
        OcrProperties ocr = mock(OcrProperties.class);
        return new DocumentServiceImpl(
                documentRepository, versionRepository, mock(DocumentTypeRepository.class),
                mock(com.oak.dms.storage.repository.StorageLocationRepository.class),
                mock(com.oak.dms.storage.service.ObjectStorageServiceFactory.class), mock(IngestionGate.class),
                auditService, mock(FolderService.class), mock(FolderAccessService.class), access,
                mock(AccessPrincipals.class), mock(DocumentApprovals.class), versionContent,
                mock(DocumentTypeFieldService.class), mock(DocumentOcrFieldResultRepository.class),
                mock(DocumentOcrLineItemRepository.class), mock(DocumentOcrCorrectionRepository.class),
                mock(ProcessingJobRepository.class), mock(OcrWorkerClient.class), mock(OcrEnqueueService.class),
                mock(com.oak.dms.document.DocumentMetadataService.class), mock(MetadataValidationService.class),
                new ObjectMapper(), ocr, new PageImageCache(ocr), watermark,
                mock(DocumentSignatureLockService.class), mock(DocumentEditGuard.class),
                new com.oak.dms.compliance.RetentionDueDates(null, null, null),
                new com.oak.dms.compliance.RetentionDeletionGuard(null, null), null);
    }

    private DocumentVersionService versionService() {
        DefaultDownloadWatermark watermark = mock(DefaultDownloadWatermark.class);
        when(watermark.apply(any(), any())).thenAnswer(i -> new DefaultDownloadWatermark.Outcome(i.getArgument(0), null));
        return new DocumentVersionService(documentRepository, versionRepository, access, mock(IngestionGate.class),
                versionContent, mock(UserRepository.class), auditService, mock(DocumentSignatureLockService.class),
                mock(DocumentEditGuard.class), mock(SignedContentGuard.class), watermark);
    }

    /**
     * A transaction manager that behaves like the real one around a failing commit: it starts a transaction (so the
     * synchronization machinery is active), lets the method run, and its commit fails - and, like
     * AbstractPlatformTransactionManager always does, it then runs the registered afterCompletion callbacks.
     */
    static final class FailingCommitManager extends org.springframework.transaction.support.AbstractPlatformTransactionManager {
        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, org.springframework.transaction.TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus status) {
            throw new TransactionSystemException("commit failed: audit_events insert rejected");
        }

        @Override
        protected void doRollback(org.springframework.transaction.support.DefaultTransactionStatus status) {
        }
    }

    /** A transaction manager whose commit succeeds. */
    static final class CommittingManager extends org.springframework.transaction.support.AbstractPlatformTransactionManager {
        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, org.springframework.transaction.TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(org.springframework.transaction.support.DefaultTransactionStatus status) {
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T withCommittingTransaction(T target) {
        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(new CommittingManager(), new AnnotationTransactionAttributeSource()));
        return (T) factory.getProxy();
    }

    /** What a real @Transactional method does: the method returns, THEN the transaction manager commits - and the commit fails. */
    @SuppressWarnings("unchecked")
    private static <T> T withFailingCommit(T target) {
        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(new FailingCommitManager(), new AnnotationTransactionAttributeSource()));
        return (T) factory.getProxy();
    }

    private void assertLeakOrClosed(String what) {
        assertThat(tracked.closed.get()).as(what + ": the storage stream must be closed").isTrue();
    }

    // ---------------------------------------------------------------- control

    @Test
    void whenAuditSucceeds_andThereIsNoTransaction_theOpenStreamIsHandedToTheCaller_whoOwnsIt() {
        DownloadedDocument returned = documentService().downloadDocument(documentId);

        assertThat(returned.content()).isSameAs(tracked);
        assertThat(tracked.closed.get()).as("ownership passes to the controller's response body").isFalse();
    }

    @Test
    void whenTheTransactionCommits_theStreamIsLeftOpenForTheCaller_ofEveryDownloadAndPreview() throws Exception {
        DocumentServiceImpl documents = withCommittingTransaction(documentService());
        DocumentVersionService versions = withCommittingTransaction(versionService());

        for (DownloadedDocument returned : new DownloadedDocument[]{
                documents.downloadDocument(documentId), documents.previewDocument(documentId),
                versions.download(documentId, versionId), versions.preview(documentId, versionId)}) {
            assertThat(returned.content()).isSameAs(tracked);
            assertThat(tracked.closed.get()).as("committed: still open for the response body").isFalse();
            assertThat(tracked.available()).isEqualTo(3);
        }
    }

    // ---------------------------------------------------------------- B. normal download

    @Test
    void normalDownload_auditPersistThrowsAfterOpen_exceptionPropagates_andTheStreamIsClosed() {
        auditPersistFails();
        DocumentServiceImpl service = documentService();

        assertThatThrownBy(() -> service.downloadDocument(documentId)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("audit chain lock timeout");
        assertLeakOrClosed("normal-download/audit-persist-throws");
    }

    @Test
    void inlinePreview_auditPersistThrowsAfterOpen_theStreamIsClosed() {
        auditPersistFails();
        DocumentServiceImpl service = documentService();

        assertThatThrownBy(() -> service.previewDocument(documentId)).isInstanceOf(IllegalStateException.class);
        assertLeakOrClosed("inline-preview/audit-persist-throws");
    }

    @Test
    void normalDownload_commitFailsAfterTheMethodReturned_theStreamIsClosed() {
        DocumentServiceImpl proxy = withFailingCommit(documentService());

        assertThatThrownBy(() -> proxy.downloadDocument(documentId)).isInstanceOf(TransactionSystemException.class);
        // The caller (controller) received an exception, never the stream - nobody holds a reference to close it.
        assertLeakOrClosed("normal-download/commit-fails-after-return");
    }

    // ---------------------------------------------------------------- C. version download

    @Test
    void versionDownload_auditPersistThrowsAfterOpen_theStreamIsClosed() {
        auditPersistFails();
        DocumentVersionService service = versionService();

        assertThatThrownBy(() -> service.download(documentId, versionId)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("audit chain lock timeout");
        assertLeakOrClosed("version-download/audit-persist-throws");
    }

    @Test
    void versionPreview_auditPersistThrowsAfterOpen_theStreamIsClosed() {
        auditPersistFails();
        DocumentVersionService service = versionService();

        assertThatThrownBy(() -> service.preview(documentId, versionId)).isInstanceOf(IllegalStateException.class);
        assertLeakOrClosed("version-preview/audit-persist-throws");
    }

    @Test
    void versionDownload_commitFailsAfterTheMethodReturned_theStreamIsClosed() {
        DocumentVersionService proxy = withFailingCommit(versionService());

        assertThatThrownBy(() -> proxy.download(documentId, versionId)).isInstanceOf(TransactionSystemException.class);
        assertLeakOrClosed("version-download/commit-fails-after-return");
    }

    // ---------------------------------------------------------------- D. controller response construction

    /** The only code between receiving the stream and returning the body: the sandbox/header builders. */
    @Test
    void controllerResponseConstruction_doesNotThrowForUnusualContentTypesNamesOrSizes() {
        String[] types = {null, "", " ", "application/pdf", "text/html", "x/y;;;", "weird", "image/svg+xml",
                MediaType.APPLICATION_OCTET_STREAM_VALUE, "a".repeat(5000)};
        String[] names = {null, "", "..", "ගොනුව.pdf", "a\r\nb.pdf", "x".repeat(5000), "%s;\"\\"};
        for (String type : types) {
            for (String name : names) {
                String contentType = type != null && !type.isBlank() ? type : MediaType.APPLICATION_OCTET_STREAM_VALUE;
                for (boolean inline : new boolean[]{false, true}) {
                    ResponseEntity.BodyBuilder builder = ContentServingPolicy.withSandbox(ResponseEntity.ok(), contentType);
                    builder.header("Content-Disposition",
                            ContentDispositionHeader.of(ContentServingPolicy.dispositionType(inline, contentType), name));
                    builder.header("Content-Length", String.valueOf(Long.MAX_VALUE));
                }
            }
        }
    }
}
