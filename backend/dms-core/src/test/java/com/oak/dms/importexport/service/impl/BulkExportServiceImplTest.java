package com.oak.dms.importexport.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.catalog.AuditObjectTypes;
import com.oak.dms.audit.enums.AuditActorType;
import com.oak.dms.audit.helper.AuditEventBuilder;
import com.oak.dms.audit.service.AuditService;
import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.importexport.AuditTestSupport;
import com.oak.dms.importexport.config.ImportExportProperties;
import com.oak.dms.importexport.dto.BulkExportDownload;
import com.oak.dms.importexport.dto.BulkExportFilters;
import com.oak.dms.importexport.dto.BulkExportJobResponse;
import com.oak.dms.importexport.dto.BulkExportPreviewResponse;
import com.oak.dms.importexport.dto.BulkExportSelection;
import com.oak.dms.importexport.dto.CreateBulkExportRequest;
import com.oak.dms.importexport.dto.ExportableDocumentResponse;
import com.oak.dms.importexport.entity.ExportJob;
import com.oak.dms.importexport.entity.ExportJob.ExportJobStatus;
import com.oak.dms.importexport.entity.ExportJob.MetadataFormat;
import com.oak.dms.importexport.exception.ActiveExportLimitException;
import com.oak.dms.importexport.exception.BulkExportSelectionException;
import com.oak.dms.importexport.exception.ExportFileUnavailableException;
import com.oak.dms.importexport.export.BulkExportRequestParams;
import com.oak.dms.importexport.export.ExportableDocumentQuery;
import com.oak.dms.importexport.export.ExportableDocumentRow;
import com.oak.dms.importexport.repository.ExportJobRepository;
import com.oak.dms.metadata.entity.DocumentType;
import com.oak.dms.metadata.repository.DocumentTypeRepository;
import com.oak.dms.outbox.OutboxEventPublisher;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.storage.dto.DownloadedObject;
import com.oak.dms.storage.entity.StorageLocation;
import com.oak.dms.storage.repository.StorageLocationRepository;
import com.oak.dms.storage.service.ObjectStorageService;
import com.oak.dms.storage.service.ObjectStorageServiceFactory;
import com.oak.dms.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.util.unit.DataSize;

import java.io.ByteArrayInputStream;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class BulkExportServiceImplTest {

    private final ExportableDocumentQuery query = mock(ExportableDocumentQuery.class);
    private final ExportJobRepository exportJobRepository = mock(ExportJobRepository.class);
    private final DocumentTypeRepository documentTypeRepository = mock(DocumentTypeRepository.class);
    private final UserRepository userRepository = mock(UserRepository.class);
    private final StorageLocationRepository storageLocationRepository = mock(StorageLocationRepository.class);
    private final ObjectStorageServiceFactory objectStorageServiceFactory = mock(ObjectStorageServiceFactory.class);
    private final ObjectStorageService storage = mock(ObjectStorageService.class);
    private final OutboxEventPublisher outboxEventPublisher = mock(OutboxEventPublisher.class);
    private final ImportExportProperties properties = new ImportExportProperties();
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final AuditService auditService = AuditTestSupport.mockAuditService();

    private final BulkExportServiceImpl service = new BulkExportServiceImpl(query, exportJobRepository,
            documentTypeRepository, userRepository, storageLocationRepository, objectStorageServiceFactory,
            outboxEventPublisher, properties, objectMapper, auditService);

    private final UUID tenantId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        TenantContext.set(tenantId, userId, "Asha Perera", UUID.randomUUID());
        when(exportJobRepository.save(any())).thenAnswer(invocation -> {
            ExportJob job = invocation.getArgument(0);
            if (job.getExportJobId() == null) {
                job.setExportJobId(UUID.randomUUID());
            }
            return job;
        });
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // ---- list and preview

    @Test
    void listDocuments_mapsRowsOfTheCallersAccessibleDocuments() {
        BulkExportFilters filters = new BulkExportFilters(UUID.randomUUID(), null, null, null, "inv", null);
        ExportableDocumentRow row = row();
        when(query.page(eq(tenantId), eq(userId), eq(filters), any()))
                .thenReturn(new PageImpl<>(List.of(row), PageRequest.of(0, 20), 1));

        Page<ExportableDocumentResponse> page = service.listDocuments(filters, PageRequest.of(0, 20));

        assertThat(page.getContent()).containsExactly(new ExportableDocumentResponse(row.documentId(), row.title(),
                row.documentTypeId(), row.documentTypeName(), row.uploadedById(), row.uploadedByName(),
                row.createdAt(), row.sizeBytes()));
    }

    @Test
    void listDocuments_noFilters_meansEveryAccessibleDocument() {
        when(query.page(any(), any(), any(), any())).thenReturn(Page.empty());

        service.listDocuments(null, PageRequest.of(0, 20));

        verify(query).page(eq(tenantId), eq(userId), eq(BulkExportFilters.none()), any());
    }

    @Test
    void listDocuments_createdFromNotBeforeCreatedTo_isRejected() {
        OffsetDateTime at = OffsetDateTime.now();
        assertThatThrownBy(() -> service.listDocuments(new BulkExportFilters(null, at, at, null, null, null),
                PageRequest.of(0, 20)))
                .isInstanceOf(BulkExportSelectionException.class);
    }

    @Test
    void preview_reportsCountsAndWhetherWithinTheLimits() {
        properties.setMaxDocumentsPerExport(10);
        properties.setMaxExportSize(DataSize.ofBytes(1000));
        BulkExportSelection byFilters = new BulkExportSelection(null, BulkExportFilters.none());

        when(query.totals(tenantId, userId, byFilters)).thenReturn(new ExportableDocumentQuery.Totals(4, 900));
        assertThat(service.preview(byFilters)).isEqualTo(new BulkExportPreviewResponse(4, 900, 10, 1000, true));

        when(query.totals(tenantId, userId, byFilters)).thenReturn(new ExportableDocumentQuery.Totals(11, 900));
        assertThat(service.preview(byFilters).withinLimits()).isFalse();

        when(query.totals(tenantId, userId, byFilters)).thenReturn(new ExportableDocumentQuery.Totals(4, 1001));
        assertThat(service.preview(byFilters).withinLimits()).isFalse();

        when(query.totals(tenantId, userId, byFilters)).thenReturn(new ExportableDocumentQuery.Totals(0, 0));
        assertThat(service.preview(byFilters).withinLimits()).isFalse();
    }

    @Test
    void preview_explicitIds_areDeduplicatedInOrder() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(query.totals(any(), any(), any())).thenReturn(new ExportableDocumentQuery.Totals(2, 10));

        service.preview(new BulkExportSelection(List.of(a, b, a), null));

        verify(query).totals(tenantId, userId, new BulkExportSelection(List.of(a, b), null));
    }

    @Test
    void selection_mustBeExactlyOneOfIdsOrFilters() {
        assertSelectionRejected(null, "EXPORT_SELECTION_REQUIRED");
        assertSelectionRejected(new BulkExportSelection(null, null), "EXPORT_SELECTION_REQUIRED");
        assertSelectionRejected(new BulkExportSelection(List.of(UUID.randomUUID()), BulkExportFilters.none()),
                "EXPORT_SELECTION_AMBIGUOUS");
        assertSelectionRejected(new BulkExportSelection(List.of(), null), "EXPORT_NOTHING_SELECTED");
        properties.setMaxDocumentsPerExport(2);
        assertSelectionRejected(new BulkExportSelection(List.of(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID()), null), "EXPORT_TOO_MANY_DOCUMENTS");
        verifyNoInteractions(query);
    }

    private void assertSelectionRejected(BulkExportSelection selection, String code) {
        assertThatThrownBy(() -> service.preview(selection))
                .isInstanceOfSatisfying(BulkExportSelectionException.class, e -> assertThat(e.getCode())
                        .isEqualTo(code));
    }

    // ---- request

    @Test
    @SuppressWarnings("unchecked")
    void request_freezesResolvedIdsPublishesTheEventAndAudits() {
        UUID typeId = UUID.randomUUID();
        BulkExportFilters filters = new BulkExportFilters(typeId, null, null, null, " inv ", null);
        BulkExportSelection selection = new BulkExportSelection(null, filters);
        List<UUID> ids = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        when(query.totals(tenantId, userId, selection)).thenReturn(new ExportableDocumentQuery.Totals(3, 4096));
        when(query.resolveIds(tenantId, userId, selection, 1000)).thenReturn(ids);
        when(documentTypeRepository.findByDocumentTypeIdAndTenantIdAndDeletedFalse(typeId, tenantId))
                .thenReturn(Optional.of(DocumentType.builder().typeName("Invoice").build()));

        BulkExportJobResponse response = service.request(new CreateBulkExportRequest(selection, null));

        ArgumentCaptor<ExportJob> saved = ArgumentCaptor.forClass(ExportJob.class);
        verify(exportJobRepository).save(saved.capture());
        ExportJob job = saved.getValue();
        assertThat(job.getTenantId()).isEqualTo(tenantId);
        assertThat(job.getCreatedBy()).isEqualTo(userId);
        assertThat(job.getStatus()).isEqualTo(ExportJobStatus.pending);
        assertThat(job.getExportType()).isEqualTo(ExportJob.ExportType.bulk_selection);
        assertThat(job.getMetadataFormat()).isEqualTo(MetadataFormat.xlsx);
        assertThat(job.getDocumentCount()).isEqualTo(3);
        assertThat(job.getCorrelationId()).isNotNull();

        BulkExportRequestParams params = BulkExportRequestParams.fromJson(objectMapper, job.getRequestParams());
        assertThat(params.documentIds()).containsExactlyElementsOf(ids);
        assertThat(params.selectionMode()).isEqualTo(BulkExportRequestParams.MODE_FILTERS);
        assertThat(params.filters()).containsEntry("documentTypeId", typeId.toString())
                .containsEntry("titleContains", "inv")
                .doesNotContainKey("uploadedBy");
        assertThat(params.selection()).contains(new BulkExportRequestParams.SelectionLine("Document type", "Invoice"));
        assertThat(params.totalBytes()).isEqualTo(4096);

        verify(outboxEventPublisher).publish(tenantId, BulkExportServiceImpl.AGGREGATE_TYPE, job.getExportJobId(),
                BulkExportServiceImpl.REQUESTED_EVENT_TYPE,
                Map.of("exportJobId", job.getExportJobId(), "correlationId", job.getCorrelationId()));

        List<AuditEventBuilder> events = AuditTestSupport.recorded(auditService);
        assertThat(events).hasSize(1);
        AuditEventBuilder event = events.get(0);
        assertThat(event.action()).isEqualTo(AuditActionCatalog.BULK_EXPORT_REQUESTED);
        assertThat(event.actorId()).isEqualTo(userId);
        assertThat(event.actorType()).isEqualTo(AuditActorType.USER);
        assertThat(event.objectType()).isEqualTo(AuditObjectTypes.EXPORT_JOB);
        assertThat(event.objectId()).isEqualTo(job.getExportJobId());
        assertThat((Map<String, Object>) event.newValue()).containsEntry("documentCount", 3)
                .containsEntry("totalBytes", 4096L)
                .containsEntry("metadataFormat", "xlsx");

        assertThat(response.status()).isEqualTo(ExportJobStatus.pending);
        assertThat(response.downloadAvailable()).isFalse();
    }

    @Test
    void request_csvFormat_isKept() {
        BulkExportSelection selection = new BulkExportSelection(List.of(UUID.randomUUID()), null);
        when(query.totals(any(), any(), any())).thenReturn(new ExportableDocumentQuery.Totals(1, 1));
        when(query.resolveIds(any(), any(), any(), anyInt())).thenReturn(selection.documentIds());

        service.request(new CreateBulkExportRequest(selection, MetadataFormat.csv));

        ArgumentCaptor<ExportJob> saved = ArgumentCaptor.forClass(ExportJob.class);
        verify(exportJobRepository).save(saved.capture());
        assertThat(saved.getValue().getMetadataFormat()).isEqualTo(MetadataFormat.csv);
    }

    @Test
    void request_activeExportAlready_isRefusedWith409BeforeAnythingIsResolved() {
        when(exportJobRepository.countByTenantIdAndCreatedByAndStatusInAndDeletedFalse(tenantId, userId,
                List.of(ExportJobStatus.pending, ExportJobStatus.processing))).thenReturn(1L);

        assertThatThrownBy(() -> service.request(new CreateBulkExportRequest(
                new BulkExportSelection(null, BulkExportFilters.none()), null)))
                .isInstanceOfSatisfying(ActiveExportLimitException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        verifyNoInteractions(query, outboxEventPublisher);
        verify(exportJobRepository, never()).save(any());
    }

    @Test
    void request_higherActiveLimit_allowsMore() {
        properties.setMaxActiveExportsPerUser(2);
        when(exportJobRepository.countByTenantIdAndCreatedByAndStatusInAndDeletedFalse(any(), any(), any()))
                .thenReturn(1L);
        when(query.totals(any(), any(), any())).thenReturn(new ExportableDocumentQuery.Totals(1, 1));
        when(query.resolveIds(any(), any(), any(), anyInt())).thenReturn(List.of(UUID.randomUUID()));

        service.request(new CreateBulkExportRequest(new BulkExportSelection(null, BulkExportFilters.none()), null));

        verify(exportJobRepository).save(any());
    }

    @Test
    void request_overTheCaps_orNothingExportable_isRefused() {
        properties.setMaxDocumentsPerExport(5);
        properties.setMaxExportSize(DataSize.ofBytes(100));
        BulkExportSelection selection = new BulkExportSelection(null, BulkExportFilters.none());

        when(query.totals(tenantId, userId, selection)).thenReturn(new ExportableDocumentQuery.Totals(6, 10));
        assertRequestRejected(selection, "EXPORT_TOO_MANY_DOCUMENTS");

        when(query.totals(tenantId, userId, selection)).thenReturn(new ExportableDocumentQuery.Totals(5, 101));
        assertRequestRejected(selection, "EXPORT_TOO_LARGE");

        when(query.totals(tenantId, userId, selection)).thenReturn(new ExportableDocumentQuery.Totals(0, 0));
        assertRequestRejected(selection, "EXPORT_NOTHING_SELECTED");

        verify(exportJobRepository, never()).save(any());
        verifyNoInteractions(outboxEventPublisher);
        assertThat(AuditTestSupport.recorded(auditService)).isEmpty();
    }

    @Test
    void request_explicitIdsSomeNotExportable_freezesOnlyTheExportableOnes() {
        List<UUID> picked = IntStream.range(0, 3).mapToObj(i -> UUID.randomUUID()).toList();
        BulkExportSelection selection = new BulkExportSelection(picked, null);
        when(query.totals(tenantId, userId, selection)).thenReturn(new ExportableDocumentQuery.Totals(2, 20));
        when(query.resolveIds(tenantId, userId, selection, 1000)).thenReturn(picked.subList(0, 2));

        service.request(new CreateBulkExportRequest(selection, null));

        ArgumentCaptor<ExportJob> saved = ArgumentCaptor.forClass(ExportJob.class);
        verify(exportJobRepository).save(saved.capture());
        BulkExportRequestParams params = BulkExportRequestParams.fromJson(objectMapper,
                saved.getValue().getRequestParams());
        assertThat(params.documentIds()).containsExactlyElementsOf(picked.subList(0, 2));
        assertThat(params.selectionMode()).isEqualTo(BulkExportRequestParams.MODE_DOCUMENTS);
    }

    private void assertRequestRejected(BulkExportSelection selection, String code) {
        assertThatThrownBy(() -> service.request(new CreateBulkExportRequest(selection, null)))
                .isInstanceOfSatisfying(BulkExportSelectionException.class, e -> assertThat(e.getCode())
                        .isEqualTo(code));
    }

    // ---- my exports

    @Test
    void listMine_isTheCallersJobsNewestFirstCappedAt50() {
        when(exportJobRepository.findAllByTenantIdAndCreatedByAndDeletedFalse(eq(tenantId), eq(userId), any()))
                .thenReturn(Page.empty());

        service.listMine(PageRequest.of(1, 500, Sort.by("status")));

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(exportJobRepository).findAllByTenantIdAndCreatedByAndDeletedFalse(eq(tenantId), eq(userId),
                pageable.capture());
        assertThat(pageable.getValue().getPageSize()).isEqualTo(50);
        assertThat(pageable.getValue().getPageNumber()).isEqualTo(1);
        assertThat(pageable.getValue().getSort())
                .isEqualTo(Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("exportJobId")));
    }

    @Test
    void getMine_someoneElsesOrMissingJob_is404() {
        UUID id = UUID.randomUUID();
        when(exportJobRepository.findByExportJobIdAndTenantIdAndCreatedByAndDeletedFalse(id, tenantId, userId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getMine(id)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.openDownload(id)).isInstanceOf(ResourceNotFoundException.class);
    }

    // ---- download

    @Test
    void openDownload_completedUnexpired_streamsTheZipAndAudits() {
        StorageLocation location = StorageLocation.builder().storageLocationId(UUID.randomUUID()).build();
        ExportJob job = completedJob(OffsetDateTime.now().plusHours(3), location.getStorageLocationId());
        stubMine(job);
        when(storageLocationRepository.findByStorageLocationIdAndDeletedFalse(location.getStorageLocationId()))
                .thenReturn(Optional.of(location));
        when(objectStorageServiceFactory.resolve(location)).thenReturn(storage);
        when(storage.download(location, job.getFileObjectKey())).thenReturn(
                new DownloadedObject(new ByteArrayInputStream(new byte[]{1, 2, 3}), 3, "application/zip"));

        BulkExportDownload download = service.openDownload(job.getExportJobId());

        assertThat(download.filename()).isEqualTo("20260301-093000-export.zip");
        assertThat(download.sizeBytes()).isEqualTo(3);
        List<AuditEventBuilder> events = AuditTestSupport.recorded(auditService);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).action()).isEqualTo(AuditActionCatalog.BULK_EXPORT_DOWNLOADED);
        assertThat(events.get(0).actorId()).isEqualTo(userId);
        assertThat(events.get(0).objectId()).isEqualTo(job.getExportJobId());
    }

    @Test
    void openDownload_notCompleted_is409() {
        for (ExportJobStatus status : List.of(ExportJobStatus.pending, ExportJobStatus.processing,
                ExportJobStatus.failed)) {
            ExportJob job = completedJob(OffsetDateTime.now().plusHours(3), UUID.randomUUID());
            job.setStatus(status);
            stubMine(job);

            assertThatThrownBy(() -> service.openDownload(job.getExportJobId()))
                    .isInstanceOfSatisfying(ExportFileUnavailableException.class,
                            e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        }
        assertThat(AuditTestSupport.recorded(auditService)).isEmpty();
        verifyNoInteractions(objectStorageServiceFactory);
    }

    @Test
    void openDownload_expiredOrPastExpiry_is410() {
        ExportJob expired = completedJob(OffsetDateTime.now().minusHours(1), UUID.randomUUID());
        expired.setStatus(ExportJobStatus.expired);
        stubMine(expired);
        assertThatThrownBy(() -> service.openDownload(expired.getExportJobId()))
                .isInstanceOfSatisfying(ExportFileUnavailableException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.GONE));

        // Past expiry, cleanup not run yet.
        ExportJob lapsed = completedJob(OffsetDateTime.now().minusMinutes(1), UUID.randomUUID());
        stubMine(lapsed);
        assertThatThrownBy(() -> service.openDownload(lapsed.getExportJobId()))
                .isInstanceOfSatisfying(ExportFileUnavailableException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.GONE));

        assertThat(AuditTestSupport.recorded(auditService)).isEmpty();
        verifyNoInteractions(objectStorageServiceFactory);
    }

    private ExportJob completedJob(OffsetDateTime expiresAt, UUID locationId) {
        return ExportJob.builder()
                .exportJobId(UUID.randomUUID())
                .tenantId(tenantId)
                .exportType(ExportJob.ExportType.bulk_selection)
                .metadataFormat(MetadataFormat.xlsx)
                .requestParams("{}")
                .status(ExportJobStatus.completed)
                .documentCount(2)
                .fileObjectKey("exports/" + tenantId + "/x.zip")
                .fileStorageLocationId(locationId)
                .fileSizeBytes(3L)
                .completedAt(OffsetDateTime.parse("2026-03-01T15:00:00+05:30"))
                .expiresAt(expiresAt)
                .correlationId(UUID.randomUUID())
                .createdBy(userId)
                .build();
    }

    private void stubMine(ExportJob job) {
        when(exportJobRepository.findByExportJobIdAndTenantIdAndCreatedByAndDeletedFalse(job.getExportJobId(),
                tenantId, userId)).thenReturn(Optional.of(job));
    }

    private ExportableDocumentRow row() {
        return new ExportableDocumentRow(UUID.randomUUID(), tenantId, "Invoice 7", UUID.randomUUID(), "Invoice",
                UUID.randomUUID(), "Asha", OffsetDateTime.now(), false, UUID.randomUUID(), 1, "inv7.pdf", 120L,
                "clean", false);
    }
}
