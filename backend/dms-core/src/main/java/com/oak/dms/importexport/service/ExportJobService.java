package com.oak.dms.importexport.service;

import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.importexport.dto.ExportJobResponse;
import com.oak.dms.importexport.entity.ExportJob;
import com.oak.dms.importexport.enums.ExportJobStatus;
import com.oak.dms.importexport.enums.ExportType;
import com.oak.dms.importexport.exception.InvalidExportJobValueException;
import com.oak.dms.importexport.repository.ExportJobRepository;
import com.oak.dms.security.auth.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Data-access operations for export_jobs, with the enum validation done
 * here at the application layer (the V41 CHECK constraints are the second
 * line of defence, not the first). The String overloads are for values that
 * arrive from outside - they reject anything that isn't an exact enum value
 * with {@link InvalidExportJobValueException} before any write happens.
 * <p>
 * Out of scope until Phase 4: generating the export, setting
 * file_object_key / expires_at, and scheduling the purge.
 */
@Service
@RequiredArgsConstructor
public class ExportJobService {

    private final ExportJobRepository exportJobRepository;

    @Transactional
    public ExportJob create(UUID tenantId, UUID requestedBy, String exportType, UUID correlationId) {
        return create(tenantId, requestedBy, ExportType.fromValue(exportType), correlationId);
    }

    /** New job in status {@code pending}. */
    @Transactional
    public ExportJob create(UUID tenantId, UUID requestedBy, ExportType exportType, UUID correlationId) {
        requireNonNull(tenantId, "tenant_id");
        requireNonNull(requestedBy, "requested_by");
        requireNonNull(exportType, "export_type");
        return exportJobRepository.save(ExportJob.builder()
                .tenantId(tenantId)
                .requestedBy(requestedBy)
                .exportType(exportType)
                .status(ExportJobStatus.pending)
                .correlationId(correlationId)
                .build());
    }

    @Transactional(readOnly = true)
    public ExportJob get(UUID exportJobId, UUID tenantId) {
        return exportJobRepository.findByExportJobIdAndTenantId(exportJobId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("ExportJob", exportJobId));
    }

    @Transactional
    public ExportJob updateStatus(UUID exportJobId, UUID tenantId, String status) {
        return updateStatus(exportJobId, tenantId, ExportJobStatus.fromValue(status));
    }

    @Transactional
    public ExportJob updateStatus(UUID exportJobId, UUID tenantId, ExportJobStatus status) {
        requireNonNull(status, "status");
        ExportJob job = get(exportJobId, tenantId);
        job.setStatus(status);
        job.setUpdatedAt(OffsetDateTime.now());
        return exportJobRepository.save(job);
    }

    @Transactional(readOnly = true)
    public List<ExportJob> listByTenantAndStatus(UUID tenantId, String status) {
        return listByTenantAndStatus(tenantId, ExportJobStatus.fromValue(status));
    }

    @Transactional(readOnly = true)
    public List<ExportJob> listByTenantAndStatus(UUID tenantId, ExportJobStatus status) {
        requireNonNull(status, "status");
        return exportJobRepository.findByTenantIdAndStatusOrderByCreatedAtDesc(tenantId, status);
    }

    /**
     * "My exports": the calling user's own jobs in their own tenant, both
     * taken from the authenticated request (TenantContext) - never from
     * input - so nobody can list another user's or tenant's exports. During
     * "Act as User" that is the impersonated user, like every other
     * TenantContext-scoped read. {@code status} is optional (null/blank =
     * all) and strictly validated.
     */
    @Transactional(readOnly = true)
    public Page<ExportJobResponse> listMine(String status, Pageable pageable) {
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();
        Page<ExportJob> page = (status == null || status.isBlank())
                ? exportJobRepository.findByTenantIdAndRequestedBy(tenantId, userId, pageable)
                : exportJobRepository.findByTenantIdAndRequestedByAndStatus(tenantId, userId,
                        ExportJobStatus.fromValue(status), pageable);
        return page.map(ExportJobResponse::from);
    }

    /** Jobs past their expires_at - for the Phase 4 purge; not scheduled yet. */
    @Transactional(readOnly = true)
    public List<ExportJob> findExpired(OffsetDateTime now) {
        return exportJobRepository.findByExpiresAtBeforeOrderByExpiresAtAsc(now);
    }

    private static void requireNonNull(Object value, String field) {
        if (value == null) {
            throw new InvalidExportJobValueException(field + " is required");
        }
    }
}
