package com.oak.dms.importexport.export;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.oak.dms.audit.helper.AuditEventBuilder;
import com.oak.dms.audit.service.AuditService;
import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.folder.FolderAccessService;
import com.oak.dms.folder.FolderAccessView;
import com.oak.dms.importexport.config.ExportJobProperties;
import com.oak.dms.importexport.dto.CreateExportRequest;
import com.oak.dms.importexport.dto.ExportJobResponse;
import com.oak.dms.importexport.entity.ExportJob;
import com.oak.dms.importexport.enums.ExportJobStatus;
import com.oak.dms.importexport.enums.ExportType;
import com.oak.dms.importexport.exception.BulkImportException;
import com.oak.dms.importexport.repository.ExportJobRepository;
import com.oak.dms.metadata.entity.DocumentType;
import com.oak.dms.metadata.repository.DocumentTypeRepository;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.storage.repository.StorageLocationRepository;
import com.oak.dms.storage.service.ObjectStorageServiceFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.http.HttpStatus;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Starting an export: what it may contain, the limits, and that it's started for the requester only. */
class BulkExportServiceTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID USER = UUID.randomUUID();
    private static final UUID TYPE = UUID.randomUUID();

    private final ExportJobRepository repository = mock(ExportJobRepository.class);
    private final ExportDocumentSelector selector = mock(ExportDocumentSelector.class);
    private final ExportJobWorker worker = mock(ExportJobWorker.class);
    private final DocumentTypeRepository types = mock(DocumentTypeRepository.class);
    private final AuditService auditService = mock(AuditService.class);
    private final ExportJobProperties properties = new ExportJobProperties();
    private final FolderAccessService access = mock(FolderAccessService.class);
    private final com.oak.dms.folder.DocumentAccessService documentAccess =
            mock(com.oak.dms.folder.DocumentAccessService.class);
    private final BulkExportService service = new BulkExportService(repository, selector, worker, types,
            mock(StorageLocationRepository.class), mock(ObjectStorageServiceFactory.class), auditService, properties,
            new ObjectMapper().findAndRegisterModules(), access, documentAccess);

    @BeforeEach
    void setUp() {
        TenantContext.set(TENANT, USER);
        // No folder is hidden from the requester.
        when(access.forCaller()).thenReturn(new FolderAccessView(java.util.List.of(), java.util.Map.of(), false));
        when(documentAccess.forCaller()).thenReturn(new com.oak.dms.folder.DocumentAccessService.DocumentAccessView(
                new FolderAccessView(java.util.List.of(), java.util.Map.of(), false), java.util.Map.of(), false, false));
        when(auditService.event(anyString())).thenReturn(mock(AuditEventBuilder.class, Answers.RETURNS_SELF));
        when(types.findByDocumentTypeIdAndTenantIdAndDeletedFalse(TYPE, TENANT))
                .thenReturn(Optional.of(DocumentType.builder().documentTypeId(TYPE).tenantId(TENANT).build()));
        when(repository.save(any())).thenAnswer(call -> {
            ExportJob job = call.getArgument(0);
            job.setExportJobId(UUID.randomUUID());
            return job;
        });
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void request_byFilter_savesAPendingBulkSelectionForTheCaller_andStartsIt() throws Exception {
        CreateExportRequest request = new CreateExportRequest(null, TYPE, LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 9, 30), "csv", true);
        when(selector.count(TENANT, request.withHidden(java.util.Set.of(), java.util.Set.of()))).thenReturn(12L);

        ExportJobResponse response = service.request(request);

        assertThat(response.exportType()).isEqualTo(ExportType.bulk_selection);
        assertThat(response.status()).isEqualTo(ExportJobStatus.pending);
        assertThat(response.documentCount()).isEqualTo(12);
        verify(repository).save(org.mockito.ArgumentMatchers.argThat(job -> job.getRequestedBy().equals(USER)
                && job.getTenantId().equals(TENANT) && job.getRequestParams().contains(TYPE.toString())));
        verify(worker).start(response.exportJobId()); // no transaction in a unit test: started at once
        // What was stored round-trips into the same request for the worker - with the
        // requester's hidden folders (none here) fixed by the server.
        assertThat(new ObjectMapper().findAndRegisterModules().readValue(
                captureParams(), CreateExportRequest.class)).isEqualTo(request.withHidden(java.util.Set.of(), java.util.Set.of()));
    }

    @Test
    void request_typeFollowsWhatWasAsked() {
        UUID doc = UUID.randomUUID();
        CreateExportRequest single = new CreateExportRequest(List.of(doc), null, null, null, null, null);
        CreateExportRequest report = new CreateExportRequest(null, TYPE, null, null, "xlsx", false);
        when(selector.count(eq(TENANT), any())).thenReturn(1L);

        assertThat(service.request(single).exportType()).isEqualTo(ExportType.single_document);
        assertThat(service.request(report).exportType()).isEqualTo(ExportType.report_data);
    }

    @Test
    void request_invalidOrTooBig_isRefusedAndNothingStarts() {
        assertThatThrownBy(() -> service.request(new CreateExportRequest(null, null, null, null, null, null)))
                .isInstanceOf(BulkImportException.class).hasMessageContaining("Choose the documents");
        assertThatThrownBy(() -> service.request(new CreateExportRequest(null, TYPE, LocalDate.of(2026, 9, 30),
                LocalDate.of(2026, 1, 1), null, null)))
                .isInstanceOf(BulkImportException.class).hasMessageContaining("start date is after");
        assertThatThrownBy(() -> service.request(new CreateExportRequest(null, TYPE, null, null, "pdf", null)))
                .isInstanceOf(BulkImportException.class);
        assertThatThrownBy(() -> service.request(new CreateExportRequest(null, UUID.randomUUID(), null, null, null, null)))
                .isInstanceOf(ResourceNotFoundException.class);

        CreateExportRequest everything = new CreateExportRequest(null, TYPE, null, null, null, null);
        when(selector.count(TENANT, everything.withHidden(java.util.Set.of(), java.util.Set.of()))).thenReturn(5000L);
        assertThatThrownBy(() -> service.request(everything))
                .isInstanceOf(BulkImportException.class).hasMessageContaining("at most 1000");
        when(selector.count(TENANT, everything.withHidden(java.util.Set.of(), java.util.Set.of()))).thenReturn(0L);
        assertThatThrownBy(() -> service.request(everything))
                .isInstanceOf(BulkImportException.class).hasMessageContaining("No documents match");

        when(repository.countByTenantIdAndRequestedByAndStatusIn(eq(TENANT), eq(USER), any())).thenReturn(2L);
        assertThatThrownBy(() -> service.request(everything))
                .isInstanceOf(BulkImportException.class).hasMessageContaining("in progress");

        verify(repository, never()).save(any());
        verify(worker, never()).start(any());
    }

    @Test
    void download_onlyTheRequestersUnexpiredFinishedExport() {
        UUID jobId = UUID.randomUUID();
        ExportJob job = ExportJob.builder().exportJobId(jobId).tenantId(TENANT).requestedBy(UUID.randomUUID())
                .exportType(ExportType.bulk_selection).status(ExportJobStatus.completed).fileObjectKey("k").build();
        when(repository.findByExportJobIdAndTenantId(jobId, TENANT)).thenReturn(Optional.of(job));
        assertThatThrownBy(() -> service.download(jobId)).isInstanceOf(ResourceNotFoundException.class);

        job = ExportJob.builder().exportJobId(jobId).tenantId(TENANT).requestedBy(USER)
                .exportType(ExportType.bulk_selection).status(ExportJobStatus.completed).fileObjectKey("k")
                .expiresAt(OffsetDateTime.now().minusMinutes(1)).build();
        when(repository.findByExportJobIdAndTenantId(jobId, TENANT)).thenReturn(Optional.of(job));
        assertThatThrownBy(() -> service.download(jobId))
                .isInstanceOfSatisfying(BulkImportException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.GONE));

        job.setExpiresAt(null);
        job.setStatus(ExportJobStatus.processing);
        assertThatThrownBy(() -> service.download(jobId))
                .isInstanceOf(BulkImportException.class).hasMessageContaining("isn't ready");
    }

    private String captureParams() {
        var captor = org.mockito.ArgumentCaptor.forClass(ExportJob.class);
        verify(repository).save(captor.capture());
        return captor.getValue().getRequestParams();
    }
}
