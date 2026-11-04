package com.oak.dms.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oak.dms.common.exception.MetadataValidationFailedException;
import com.oak.dms.document.dto.SaveReviewRequest;
import com.oak.dms.metadata.service.DocumentTypeFieldService;
import com.oak.dms.metadata.repository.DocumentTypeRepository;
import com.oak.dms.metadata.service.MetadataValidationService;
import com.oak.dms.metadata.dto.DocumentTypeSchemaResponse;
import com.oak.dms.metadata.dto.ValidationError;
import com.oak.dms.ocrworker.OcrProperties;
import com.oak.dms.ocrworker.OcrWorkerClient;
import com.oak.dms.ocrworker.ProcessingJobRepository;
import com.oak.dms.outbox.OutboxEventPublisher;
import com.oak.dms.security.auth.TenantContext;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Two guarantees about a reviewer's correction, both easy to lose quietly.
 *
 * <b>1. It is validated.</b> The review path calls
 * {@code saveValues(..., enforceMandatory=false)}, and that flag suppresses
 * {@link MetadataValidationService} ENTIRELY inside
 * {@code DocumentMetadataServiceImpl#writeValues} -- not just its
 * mandatory arm, which is what the name suggests. Without
 * {@code validateCorrections} running it explicitly first, a reviewer
 * could type anything into a field with configured rules and it would be
 * stored unchecked.
 *
 * <b>2. It is marked {@code manual}, and OCR never overwrites manual.</b>
 * {@code saveValues} writes {@code source = "manual"}, and
 * {@code applyOcrFields} skips any field whose stored source is manual --
 * so a later OCR re-run cannot undo a human's correction. The second half
 * of that is covered by
 * {@link DocumentMetadataServiceApplyOcrFieldsTest}; this class pins the
 * first half, that the review path really does go through the method
 * which marks it.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReviewCorrectionsAreManualTest {

    @Mock private DocumentTypeRepository documentTypeRepository;
    @Mock private DocumentTypeFieldService documentTypeFieldService;
    @Mock private com.oak.dms.storage.repository.StorageLocationRepository storageLocationRepository;
    @Mock private com.oak.dms.storage.service.ObjectStorageServiceFactory objectStorageServiceFactory;
    @Mock private DocumentRepository documentRepository;
    @Mock private DocumentVersionRepository documentVersionRepository;
    @Mock private DocumentOcrFieldResultRepository documentOcrFieldResultRepository;
    @Mock private DocumentApprovals documentApprovals;
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
    @Mock private com.oak.dms.watermark.service.WatermarkDefaultsService watermarkDefaultsService;
    @Mock private com.oak.dms.signature.service.DocumentSignatureLockService signatureLockService;
    @Mock private com.oak.dms.checkout.DocumentEditGuard editGuard;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private DocumentServiceImpl service;

    private final UUID tenantId = UUID.randomUUID();
    private final UUID documentTypeId = UUID.randomUUID();
    private final UUID documentId = UUID.randomUUID();
    private final UUID versionId = UUID.randomUUID();

    private DocumentTypeSchemaResponse.SchemaField field(String key, String dataType, boolean mandatory, int order) {
        return new DocumentTypeSchemaResponse.SchemaField(
                UUID.randomUUID(), key, key, null, dataType, "active", mandatory, false, true,
                order, null, null, List.of(), List.of());
    }

    @BeforeEach
    void setUp() {
        // Constructor order follows the field order in DocumentServiceImpl:
        // the document/folder/access collaborators first, then the OCR ones.
        service = new DocumentServiceImpl(
                documentRepository, documentVersionRepository, documentTypeRepository,
                storageLocationRepository, objectStorageServiceFactory, ingestionGate,
                auditService, folderService, folderAccessService, documentAccessService,
                accessPrincipals, documentApprovals, versionContent,
                documentTypeFieldService, documentOcrFieldResultRepository,
                documentOcrLineItemRepository, documentOcrCorrectionRepository,
                processingJobRepository, ocrWorkerClient, ocrEnqueueService,
                documentMetadataService, metadataValidationService, objectMapper, ocrProperties,
                new PageImageCache(ocrProperties), watermarkDefaultsService, signatureLockService,
                editGuard, new com.oak.dms.compliance.RetentionDueDates(null, null, null));

        TenantContext.set(tenantId, UUID.randomUUID());

        Document document = Document.builder()
                .documentId(documentId).tenantId(tenantId)
                .documentTypeId(documentTypeId).latestVersionId(versionId)
                .title("Invoice 41").build();
        when(documentRepository.findById(documentId)).thenReturn(Optional.of(document));
        // The OCR review endpoints go through findVisible now - document-level
        // folder access on top of the os.* permission - so the tenant-scoped
        // lookup and the access check both have to answer here. What this test
        // is about is what saveReview WRITES, so access is granted and the
        // refusal cases are covered by ReviewEndpointTenantIsolationTest and
        // the access tests.
        when(documentRepository.findByDocumentIdAndTenantIdAndDeletedFalse(documentId, tenantId))
                .thenReturn(Optional.of(document));
        when(documentAccessService.require(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(document), org.mockito.ArgumentMatchers.any()))
                .thenReturn(document);

        DocumentVersion version = DocumentVersion.builder()
                .versionId(versionId).documentId(documentId).tenantId(tenantId)
                .ocrStatus("completed").requiresReview(true)
                .requestedFields("[\"invoice_number\",\"amount\"]")
                .originalFilename("invoice.pdf").mimeType("application/pdf")
                .build();
        when(documentVersionRepository.findById(versionId)).thenReturn(Optional.of(version));

        when(documentTypeFieldService.getSchema(documentTypeId)).thenReturn(
                new DocumentTypeSchemaResponse(documentTypeId, "INVOICE", "Invoice", 1, List.of(
                        field("invoice_number", "text", true, 10),
                        field("amount", "decimal", true, 20))));

        when(documentOcrFieldResultRepository.findByDocumentIdAndTenantId(documentId, tenantId))
                .thenReturn(List.of(
                        DocumentOcrFieldResult.builder()
                                .fieldResultId(UUID.randomUUID()).fieldKey("invoice_number")
                                .value("INV-4471").status("low_confidence")
                                .confidence(BigDecimal.valueOf(0.62)).build(),
                        DocumentOcrFieldResult.builder()
                                .fieldResultId(UUID.randomUUID()).fieldKey("amount")
                                .value("1234.50").status("extracted")
                                .confidence(BigDecimal.valueOf(0.97)).build()));

        when(documentOcrLineItemRepository.findByVersionIdAndTenantIdOrderByLineNumberAsc(versionId, tenantId))
                .thenReturn(List.of());
        when(processingJobRepository
                .findTopByReferenceTypeAndReferenceIdAndJobTypeOrderByCreatedAtDesc(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(metadataValidationService.validate(any(), any(), any())).thenReturn(List.of());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private SaveReviewRequest correcting(String fieldKey, String value) {
        return new SaveReviewRequest(
                List.of(new SaveReviewRequest.Decision(fieldKey, "corrected", value, "typed")));
    }

    // ------------------------------------------------------------------
    // Validation
    // ------------------------------------------------------------------
    @Test
    void aCorrectionIsRunThroughMetadataValidationService() {
        service.saveReview(documentId, correcting("invoice_number", "INV-9999"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(metadataValidationService).validate(eq(documentTypeId), eq(null), captor.capture());

        assertThat(captor.getValue()).containsEntry("invoice_number", "INV-9999");
    }

    @Test
    void aCorrectionThatBreaksAFieldRuleIsRejected() {
        when(metadataValidationService.validate(any(), any(), any())).thenReturn(List.of(
                new ValidationError("invoice_number", "MAX_LENGTH", "Too long.", "error")));

        assertThatThrownBy(() -> service.saveReview(documentId, correcting("invoice_number", "x".repeat(500))))
                .isInstanceOf(MetadataValidationFailedException.class);
    }

    @Test
    void nothingIsWrittenWhenValidationRejectsTheCorrection() {
        when(metadataValidationService.validate(any(), any(), any())).thenReturn(List.of(
                new ValidationError("invoice_number", "MAX_LENGTH", "Too long.", "error")));

        assertThatThrownBy(() -> service.saveReview(documentId, correcting("invoice_number", "x".repeat(500))))
                .isInstanceOf(MetadataValidationFailedException.class);

        // Validation runs BEFORE the write, so the reviewer is not left
        // guessing which of their corrections survived a mid-batch failure.
        verify(documentMetadataService, never()).saveValues(any(), any(), anyBoolean());
    }

    @Test
    void aMissingMandatoryFieldDoesNotBlockAPartialSave() {
        // validate() walks the WHOLE schema, so a partial submission
        // always reports every mandatory field the reviewer did not touch.
        // Treating those as blocking would make this endpoint refuse
        // exactly the situation it exists to work through.
        when(metadataValidationService.validate(any(), any(), any())).thenReturn(List.of(
                new ValidationError("amount", "MANDATORY_FIELD_MISSING", "Field 'amount' is required.", "error")));

        service.saveReview(documentId, correcting("invoice_number", "INV-9999"));

        verify(documentMetadataService).saveValues(eq(documentId), any(), eq(false));
    }

    @Test
    void anErrorAboutAFieldTheReviewerDidNotTouchDoesNotBlockTheSave() {
        when(metadataValidationService.validate(any(), any(), any())).thenReturn(List.of(
                new ValidationError("amount", "MAX_LENGTH", "Too long.", "error")));

        service.saveReview(documentId, correcting("invoice_number", "INV-9999"));

        verify(documentMetadataService).saveValues(eq(documentId), any(), eq(false));
    }

    @Test
    void aWarningIsNotAnError() {
        when(metadataValidationService.validate(any(), any(), any())).thenReturn(List.of(
                new ValidationError("invoice_number", "SOMETHING", "Looks odd.", "warning")));

        service.saveReview(documentId, correcting("invoice_number", "INV-9999"));

        verify(documentMetadataService).saveValues(eq(documentId), any(), eq(false));
    }

    // ------------------------------------------------------------------
    // "manual"
    // ------------------------------------------------------------------
    @Test
    void aCorrectionIsWrittenThroughTheMethodThatMarksItManual() {
        service.saveReview(documentId, correcting("invoice_number", "INV-9999"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        // saveValues is the manual path -- DocumentMetadataServiceImpl
        // calls writeValues(..., source="manual", ...) for it, which is
        // what applyOcrFields later refuses to overwrite.
        verify(documentMetadataService).saveValues(eq(documentId), captor.capture(), eq(false));

        assertThat(captor.getValue()).containsExactly(Map.entry("invoice_number", "INV-9999"));
    }

    @Test
    void anAcceptanceIsNotWrittenBackAtAll() {
        service.saveReview(documentId, new SaveReviewRequest(
                List.of(new SaveReviewRequest.Decision("invoice_number", "accepted", null, null))));

        // Re-saving an accepted value would touch document_metadata_values
        // and its audit trail for every field on every review, and would
        // flip an OCR-sourced value to "manual" that nobody changed.
        verify(documentMetadataService, never()).saveValues(any(), any(), anyBoolean());
    }

    @Test
    void anEditThatChangedNothingIsNotWrittenBackEither() {
        service.saveReview(documentId, correcting("invoice_number", "INV-4471"));

        verify(documentMetadataService, never()).saveValues(any(), any(), anyBoolean());
    }

    // ------------------------------------------------------------------
    // The correction log itself
    // ------------------------------------------------------------------
    @Test
    void bothValuesAreRecordedWithTheConfidenceOcrHad() {
        service.saveReview(documentId, correcting("invoice_number", "INV-9999"));

        ArgumentCaptor<DocumentOcrCorrection> captor = ArgumentCaptor.forClass(DocumentOcrCorrection.class);
        verify(documentOcrCorrectionRepository).save(captor.capture());
        DocumentOcrCorrection row = captor.getValue();

        assertThat(row.getAction()).isEqualTo(DocumentOcrCorrection.ACTION_CORRECTED);
        assertThat(row.getOriginalValue()).isEqualTo("INV-4471");
        assertThat(row.getCorrectedValue()).isEqualTo("INV-9999");
        // "accepted at 0.62" and "corrected from 0.62" are both
        // observations about where the threshold sits.
        assertThat(row.getOriginalConfidence()).isEqualByComparingTo("0.62");
        assertThat(row.getOriginalStatus()).isEqualTo("low_confidence");
        assertThat(row.getSource()).isEqualTo(DocumentOcrCorrection.SOURCE_TYPED);
        assertThat(row.getTenantId()).isEqualTo(tenantId);
    }

    @Test
    void anAcceptanceIsStillRecorded() {
        service.saveReview(documentId, new SaveReviewRequest(
                List.of(new SaveReviewRequest.Decision("invoice_number", "accepted", null, null))));

        ArgumentCaptor<DocumentOcrCorrection> captor = ArgumentCaptor.forClass(DocumentOcrCorrection.class);
        verify(documentOcrCorrectionRepository).save(captor.capture());

        // Without acceptances the correction log has no denominator.
        assertThat(captor.getValue().getAction()).isEqualTo(DocumentOcrCorrection.ACTION_ACCEPTED);
        assertThat(captor.getValue().getSource()).isNull();
    }
}
