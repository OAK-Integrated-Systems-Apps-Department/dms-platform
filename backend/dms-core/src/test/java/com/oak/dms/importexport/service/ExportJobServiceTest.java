package com.oak.dms.importexport.service;

import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.importexport.entity.ExportJob;
import com.oak.dms.importexport.enums.ExportJobStatus;
import com.oak.dms.importexport.enums.ExportType;
import com.oak.dms.importexport.exception.InvalidExportJobValueException;
import com.oak.dms.importexport.dto.ExportJobResponse;
import com.oak.dms.importexport.repository.ExportJobRepository;
import com.oak.dms.security.auth.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Application-layer validation of export_jobs writes: an invalid enum value
 * (or a missing required one) must be rejected before the repository is
 * ever called - the V41 CHECK constraints are only the second line of
 * defence.
 */
class ExportJobServiceTest {

    private final UUID tenantId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private ExportJobRepository repository;
    private ExportJobService service;

    @BeforeEach
    void setUp() {
        repository = mock(ExportJobRepository.class);
        when(repository.save(any(ExportJob.class))).thenAnswer(inv -> inv.getArgument(0));
        service = new ExportJobService(repository);
    }

    @Test
    void create_withValidTypeString_savesPendingJob() {
        UUID correlationId = UUID.randomUUID();

        ExportJob job = service.create(tenantId, userId, "bulk_folder", correlationId);

        assertThat(job.getExportType()).isEqualTo(ExportType.bulk_folder);
        assertThat(job.getStatus()).isEqualTo(ExportJobStatus.pending);
        assertThat(job.getTenantId()).isEqualTo(tenantId);
        assertThat(job.getRequestedBy()).isEqualTo(userId);
        assertThat(job.getCorrelationId()).isEqualTo(correlationId);
        verify(repository).save(any(ExportJob.class));
    }

    @Test
    void create_withUnknownType_isRejectedBeforeAnyWrite() {
        assertThatThrownBy(() -> service.create(tenantId, userId, "everything", null))
                .isInstanceOf(InvalidExportJobValueException.class)
                .hasMessageContaining("export_type")
                .hasMessageContaining("single_document, bulk_selection, bulk_folder, report_data")
                .satisfies(e -> assertThat(((InvalidExportJobValueException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        verify(repository, never()).save(any());
    }

    @Test
    void create_matchIsExact_notCaseInsensitive() {
        // "BULK_FOLDER" would violate the lowercase DB CHECK - reject it here.
        assertThatThrownBy(() -> service.create(tenantId, userId, "BULK_FOLDER", null))
                .isInstanceOf(InvalidExportJobValueException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void create_withMissingRequiredFields_isRejected() {
        assertThatThrownBy(() -> service.create(tenantId, userId, (String) null, null))
                .isInstanceOf(InvalidExportJobValueException.class);
        assertThatThrownBy(() -> service.create(null, userId, ExportType.report_data, null))
                .isInstanceOf(InvalidExportJobValueException.class).hasMessageContaining("tenant_id");
        assertThatThrownBy(() -> service.create(tenantId, null, ExportType.report_data, null))
                .isInstanceOf(InvalidExportJobValueException.class).hasMessageContaining("requested_by");
        verify(repository, never()).save(any());
    }

    @Test
    void updateStatus_withUnknownStatus_isRejectedBeforeAnyLookupOrWrite() {
        assertThatThrownBy(() -> service.updateStatus(UUID.randomUUID(), tenantId, "done"))
                .isInstanceOf(InvalidExportJobValueException.class)
                .hasMessageContaining("pending, processing, completed, failed, expired");
        verify(repository, never()).findByExportJobIdAndTenantId(any(), any());
        verify(repository, never()).save(any());
    }

    @Test
    void updateStatus_setsStatusAndBumpsUpdatedAt() {
        OffsetDateTime before = OffsetDateTime.now().minusMinutes(5);
        ExportJob existing = ExportJob.builder().exportJobId(UUID.randomUUID()).tenantId(tenantId)
                .requestedBy(userId).exportType(ExportType.single_document).updatedAt(before).build();
        when(repository.findByExportJobIdAndTenantId(existing.getExportJobId(), tenantId)).thenReturn(Optional.of(existing));

        ExportJob updated = service.updateStatus(existing.getExportJobId(), tenantId, "processing");

        assertThat(updated.getStatus()).isEqualTo(ExportJobStatus.processing);
        assertThat(updated.getUpdatedAt()).isAfter(before);
    }

    @Test
    void updateStatus_ofAnotherTenantsJob_isNotFound() {
        when(repository.findByExportJobIdAndTenantId(any(), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateStatus(UUID.randomUUID(), tenantId, ExportJobStatus.failed))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(repository, never()).save(any());
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    @Test
    void listMine_scopesToTheSessionsTenantAndUser_andHidesTheStorageKey() {
        TenantContext.set(tenantId, userId);
        Pageable pageable = PageRequest.of(0, 20);
        ExportJob ready = ExportJob.builder().exportJobId(UUID.randomUUID()).tenantId(tenantId).requestedBy(userId)
                .exportType(ExportType.bulk_folder).status(ExportJobStatus.completed)
                .fileObjectKey("/" + tenantId + "/tmp/exports/x/batch.zip").build();
        when(repository.findByTenantIdAndRequestedBy(tenantId, userId, pageable))
                .thenReturn(new PageImpl<>(List.of(ready), pageable, 1));

        Page<ExportJobResponse> page = service.listMine(null, pageable);

        assertThat(page.getContent()).singleElement().satisfies(r -> {
            assertThat(r.exportJobId()).isEqualTo(ready.getExportJobId());
            assertThat(r.downloadAvailable()).isTrue();
        });
        // The response type has no field for the object key at all.
        assertThat(ExportJobResponse.class.getRecordComponents()).extracting("name").doesNotContain("fileObjectKey");
    }

    @Test
    void listMine_withStatus_usesTheStatusQuery() {
        TenantContext.set(tenantId, userId);
        Pageable pageable = PageRequest.of(0, 20);
        when(repository.findByTenantIdAndRequestedByAndStatus(eq(tenantId), eq(userId), eq(ExportJobStatus.failed), eq(pageable)))
                .thenReturn(Page.empty(pageable));

        assertThat(service.listMine("failed", pageable)).isEmpty();
        verify(repository, never()).findByTenantIdAndRequestedBy(any(), any(), any());
    }

    @Test
    void listMine_withUnknownStatus_isRejected() {
        TenantContext.set(tenantId, userId);
        assertThatThrownBy(() -> service.listMine("archived", PageRequest.of(0, 20)))
                .isInstanceOf(InvalidExportJobValueException.class);
    }

    @Test
    void downloadAvailable_onlyWhenCompletedWithAFile() {
        ExportJob completedNoFile = ExportJob.builder().exportType(ExportType.report_data).status(ExportJobStatus.completed).build();
        ExportJob processingWithFile = ExportJob.builder().exportType(ExportType.report_data).status(ExportJobStatus.processing)
                .fileObjectKey("k").build();
        assertThat(ExportJobResponse.from(completedNoFile).downloadAvailable()).isFalse();
        assertThat(ExportJobResponse.from(processingWithFile).downloadAvailable()).isFalse();
    }

    @Test
    void listByTenantAndStatus_withUnknownStatus_isRejected() {
        assertThatThrownBy(() -> service.listByTenantAndStatus(tenantId, "archived"))
                .isInstanceOf(InvalidExportJobValueException.class);
    }
}
