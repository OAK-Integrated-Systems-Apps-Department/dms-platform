package com.oak.dms.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.document.dto.RubberBandRequest;
import com.oak.dms.document.dto.SaveReviewRequest;
import com.oak.dms.metadata.service.DocumentTypeFieldService;
import com.oak.dms.metadata.repository.DocumentTypeRepository;
import com.oak.dms.metadata.service.MetadataValidationService;
import com.oak.dms.ocrworker.OcrProperties;
import com.oak.dms.ocrworker.OcrWorkerClient;
import com.oak.dms.ocrworker.ProcessingJobRepository;
import com.oak.dms.outbox.OutboxEventPublisher;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.document.DocumentMapper;
import com.oak.dms.folder.AccessPrincipals;
import com.oak.dms.folder.DocumentAccessService;
import com.oak.dms.folder.FolderAccessService;
import com.oak.dms.folder.FolderService;
import com.oak.dms.audit.service.AuditService;
import com.oak.dms.ocrworker.OcrEnqueueService;
import com.oak.dms.scanning.service.IngestionGate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Every review endpoint, asked for tenant A's document by a tenant B user.
 *
 * This matters more here than on the older routes, because two of these
 * leave dms-core entirely: {@code getPageImage} and {@code readRegion}
 * forward a version id to ocr-worker, which has no notion of who is asking
 * -- it answers for ANY version id presented with the internal token. The
 * check in {@link DocumentServiceImpl} is the only thing standing between
 * one tenant and another tenant's page images, so these tests also assert
 * that the worker was never called at all, not merely that the caller got
 * an error afterwards.
 *
 * 404 and not 403, deliberately and identically to "no such document": a
 * 403 would confirm that the id exists in some other tenant.
 * {@link ResourceNotFoundException} carries NOT_FOUND, which
 * {@code GlobalExceptionHandler} renders directly.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReviewEndpointTenantIsolationTest {

    @Mock private DocumentTypeRepository documentTypeRepository;
    @Mock private DocumentTypeFieldService documentTypeFieldService;
    @Mock private com.oak.dms.user.UserRepository userRepository;
    @Mock private com.oak.dms.storage.repository.StorageLocationRepository storageLocationRepository;
    @Mock private com.oak.dms.storage.service.ObjectStorageServiceFactory objectStorageServiceFactory;
    @Mock private DocumentRepository documentRepository;
    @Mock private DocumentVersionRepository documentVersionRepository;
    @Mock private DocumentOcrFieldResultRepository documentOcrFieldResultRepository;
    @Mock private DocumentMapper documentMapper;
    @Mock private IngestionGate ingestionGate;
    @Mock private AuditService auditService;
    @Mock private FolderService folderService;
    @Mock private FolderAccessService folderAccessService;
    @Mock private DocumentAccessService documentAccessService;
    @Mock private AccessPrincipals accessPrincipals;
    @Mock private VersionContent versionContent;
    @Mock private ProcessingJobRepository processingJobRepository;
    @Mock private DocumentOcrLineItemRepository documentOcrLineItemRepository;
    @Mock private DocumentOcrCorrectionRepository documentOcrCorrectionRepository;
    @Mock private OcrWorkerClient ocrWorkerClient;
    @Mock private OcrEnqueueService ocrEnqueueService;
    @Mock private DocumentMetadataService documentMetadataService;
    @Mock private MetadataValidationService metadataValidationService;
    @Mock private OcrProperties ocrProperties;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private DocumentServiceImpl service;

    private final UUID tenantA = UUID.randomUUID();
    private final UUID tenantB = UUID.randomUUID();
    private final UUID documentId = UUID.randomUUID();
    private final UUID versionId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        // Constructor order follows the field order in DocumentServiceImpl:
        // the document/folder/access collaborators first, then the OCR ones.
        service = new DocumentServiceImpl(
                documentRepository, documentVersionRepository, documentMapper, documentTypeRepository,
                userRepository, storageLocationRepository, objectStorageServiceFactory, ingestionGate,
                auditService, folderService, folderAccessService, documentAccessService,
                accessPrincipals, versionContent,
                documentTypeFieldService, documentOcrFieldResultRepository,
                documentOcrLineItemRepository, documentOcrCorrectionRepository,
                processingJobRepository, ocrWorkerClient, ocrEnqueueService,
                documentMetadataService, metadataValidationService, objectMapper, ocrProperties,
                new PageImageCache(ocrProperties));

        // The document belongs to tenant A. Every test below is tenant B.
        Document document = Document.builder()
                .documentId(documentId)
                .tenantId(tenantA)
                .latestVersionId(versionId)
                .title("Tenant A's invoice")
                .build();
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
        // The review queue is access-filtered in the query now, so it asks
        // for the caller's access view first. "Sees everything" here on
        // purpose: this test is about the TENANT boundary, and granting the
        // widest possible document access makes a leak across tenants show up
        // as a leak rather than being masked by a folder check.
        // A real view, not a mock: DocumentAccessView is a record whose
        // seesEverything() is derived from its components, and a stubbed
        // answer would let the two disagree.
        when(documentAccessService.forCaller()).thenReturn(new DocumentAccessService.DocumentAccessView(
                null, java.util.Map.of(), true, false));

        TenantContext.set(tenantB, UUID.randomUUID());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void assertIsA404(ThrowingCall call) {
        assertThatThrownBy(call::run)
                .isInstanceOf(ResourceNotFoundException.class)
                .extracting(e -> ((ResourceNotFoundException) e).getStatus())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @FunctionalInterface
    interface ThrowingCall {
        void run();
    }

    // ------------------------------------------------------------------
    // GET /api/v1/documents/{documentId}/review
    // ------------------------------------------------------------------
    @Test
    void getReviewIsA404ForAnotherTenantsDocument() {
        assertIsA404(() -> service.getReview(documentId));

        // It never got as far as reading anyone's field results.
        verifyNoInteractions(documentOcrFieldResultRepository);
        verifyNoInteractions(documentOcrLineItemRepository);
    }

    // ------------------------------------------------------------------
    // GET /api/v1/documents/{documentId}/pages/{page}/image
    // ------------------------------------------------------------------
    @Test
    void getPageImageIsA404ForAnotherTenantsDocument() {
        assertIsA404(() -> service.getPageImage(documentId, 0));
    }

    @Test
    void getPageImageNeverReachesTheWorkerForAnotherTenantsDocument() {
        assertThatThrownBy(() -> service.getPageImage(documentId, 0))
                .isInstanceOf(ResourceNotFoundException.class);

        // The crux: ocr-worker would have served the page to anyone
        // holding the internal token.
        verify(ocrWorkerClient, never()).fetchPageImage(any(), any(), anyInt(), any());
        verifyNoInteractions(ocrWorkerClient);
    }

    // ------------------------------------------------------------------
    // POST /api/v1/documents/{documentId}/read-region
    // ------------------------------------------------------------------
    @Test
    void readRegionIsA404ForAnotherTenantsDocument() {
        assertIsA404(() -> service.readRegion(documentId, new RubberBandRequest(0, 0.1, 0.1, 0.5, 0.5)));
    }

    @Test
    void readRegionNeverReachesTheWorkerForAnotherTenantsDocument() {
        assertThatThrownBy(() -> service.readRegion(documentId, new RubberBandRequest(0, 0.1, 0.1, 0.5, 0.5)))
                .isInstanceOf(ResourceNotFoundException.class);

        verifyNoInteractions(ocrWorkerClient);
    }

    // ------------------------------------------------------------------
    // POST /api/v1/documents/{documentId}/review
    // ------------------------------------------------------------------
    @Test
    void saveReviewIsA404ForAnotherTenantsDocument() {
        SaveReviewRequest request = new SaveReviewRequest(
                List.of(new SaveReviewRequest.Decision("invoice_number", "corrected", "INV-9999", "typed")));

        assertIsA404(() -> service.saveReview(documentId, request));
    }

    @Test
    void saveReviewWritesNothingForAnotherTenantsDocument() {
        SaveReviewRequest request = new SaveReviewRequest(
                List.of(new SaveReviewRequest.Decision("invoice_number", "corrected", "INV-9999", "typed")));

        assertThatThrownBy(() -> service.saveReview(documentId, request))
                .isInstanceOf(ResourceNotFoundException.class);

        // No correction row, and no write-back onto another tenant's
        // document metadata.
        verifyNoInteractions(documentOcrCorrectionRepository);
        verify(documentMetadataService, never()).saveValues(any(), any(), org.mockito.ArgumentMatchers.anyBoolean());
        verify(documentVersionRepository, never()).save(any());
    }

    // ------------------------------------------------------------------
    // GET /api/v1/documents/review-queue
    // ------------------------------------------------------------------
    @Test
    void theReviewQueueIsScopedToTheCallersTenant() {
        // This one cannot 404 -- it is a list, and the right answer for a
        // tenant with nothing to review is an empty page. What matters is
        // that the tenant it queries is the CALLER's, never one supplied
        // from outside.
        when(documentVersionRepository.findReviewQueue(any(), any()))
                .thenReturn(org.springframework.data.domain.Page.empty());

        service.getReviewQueue(PageRequest.of(0, 25));

        verify(documentVersionRepository).findReviewQueue(eq(tenantB), any());
        verify(documentVersionRepository, never()).findReviewQueue(eq(tenantA), any());
    }

    @Test
    void theReviewQueueReturnsNothingRatherThanAnotherTenantsRows() {
        when(documentVersionRepository.findReviewQueue(eq(tenantB), any()))
                .thenReturn(org.springframework.data.domain.Page.empty());

        assertThat(service.getReviewQueue(PageRequest.of(0, 25))).isEmpty();
    }

    // ------------------------------------------------------------------
    // The guard itself
    // ------------------------------------------------------------------
    @Test
    void aDocumentThatDoesNotExistAtAllFailsIdentically() {
        UUID unknown = UUID.randomUUID();
        when(documentRepository.findById(unknown)).thenReturn(Optional.empty());

        // Same exception, same status, and the message names only the id
        // the caller already had -- so "exists in another tenant" and
        // "does not exist" are indistinguishable from outside.
        assertIsA404(() -> service.getReview(unknown));
    }

    @Test
    void theRefusalSaysNothingAboutTheOwningTenant() {
        String message = org.assertj.core.api.Assertions
                .catchThrowableOfType(() -> service.getReview(documentId), ResourceNotFoundException.class)
                .getMessage();

        assertThat(message).doesNotContain(tenantA.toString());
        assertThat(message).doesNotContain("Tenant A's invoice");
    }
}
