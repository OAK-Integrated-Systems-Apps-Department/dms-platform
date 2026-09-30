package com.oak.dms.scanning.service.impl;

import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.catalog.AuditObjectTypes;
import com.oak.dms.audit.enums.AuditOutcome;
import com.oak.dms.audit.service.AuditService;
import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.scanning.dto.QuarantineRecordDetailResponse;
import com.oak.dms.scanning.dto.QuarantineRecordResponse;
import com.oak.dms.scanning.dto.QuarantineReleaseResponse;
import com.oak.dms.scanning.dto.QuarantineUserSummary;
import com.oak.dms.scanning.entity.QuarantineRecord;
import com.oak.dms.scanning.entity.ScanResult;
import com.oak.dms.scanning.enums.QuarantineStatus;
import com.oak.dms.scanning.exception.QuarantineStateException;
import com.oak.dms.scanning.helper.QuarantineMapper;
import com.oak.dms.scanning.repository.QuarantineRecordRepository;
import com.oak.dms.scanning.repository.QuarantineRecordSpecifications;
import com.oak.dms.scanning.repository.ScanResultRepository;
import com.oak.dms.scanning.service.QuarantineService;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.storage.entity.StorageLocation;
import com.oak.dms.storage.repository.StorageLocationRepository;
import com.oak.dms.storage.service.ObjectStorageServiceFactory;
import com.oak.dms.user.User;
import com.oak.dms.user.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * See {@link QuarantineService}. List/get/delete live here; release and
 * override are delegated to {@link QuarantineReleaseService}, which drives
 * the scan gate.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QuarantineServiceImpl implements QuarantineService {

    private final QuarantineRecordRepository quarantineRecordRepository;
    private final ScanResultRepository scanResultRepository;
    private final StorageLocationRepository storageLocationRepository;
    private final ObjectStorageServiceFactory objectStorageServiceFactory;
    private final UserRepository userRepository;
    private final QuarantineMapper quarantineMapper;
    private final QuarantineReleaseService quarantineReleaseService;
    private final AuditService auditService;

    @Override
    @Transactional(readOnly = true)
    public Page<QuarantineRecordResponse> list(QuarantineStatus status, OffsetDateTime detectedFrom,
                                               OffsetDateTime detectedTo, String search, Pageable pageable) {
        Specification<QuarantineRecord> spec = Specification.allOf(
                QuarantineRecordSpecifications.tenant(TenantContext.getTenantId()),
                QuarantineRecordSpecifications.status(status),
                QuarantineRecordSpecifications.detectedFrom(detectedFrom),
                QuarantineRecordSpecifications.detectedTo(detectedTo),
                QuarantineRecordSpecifications.search(search));
        Page<QuarantineRecord> page = quarantineRecordRepository.findAll(spec, pageable);

        Set<UUID> userIds = new HashSet<>();
        page.forEach(record -> {
            userIds.add(record.getCreatedBy());
            userIds.add(record.getReviewedBy());
        });
        Map<UUID, QuarantineUserSummary> users = loadUsers(userIds);
        return page.map(record -> quarantineMapper.toListRow(record, users));
    }

    @Override
    @Transactional(readOnly = true)
    public QuarantineRecordDetailResponse get(UUID quarantineId) {
        QuarantineRecord record = findOwned(quarantineId);
        ScanResult original = scanResultRepository.findById(record.getScanResultId()).orElse(null);
        List<ScanResult> attempts = scanResultRepository
                .findByReleasedFromScanResultIdOrderByCreatedAtDesc(record.getScanResultId());

        Set<UUID> userIds = new HashSet<>();
        userIds.add(record.getCreatedBy());
        userIds.add(record.getReviewedBy());
        if (original != null) {
            userIds.add(original.getUploadedBy());
        }
        attempts.forEach(attempt -> userIds.add(attempt.getReviewRequestedBy()));
        return quarantineMapper.toDetail(record, original, attempts, loadUsers(userIds));
    }

    /**
     * One transaction holding the record's row lock: the object is deleted
     * from quarantine storage first (idempotent - a no-op if already gone),
     * then the record is marked. If the commit fails after the object was
     * removed, the record simply stays {@code held} and a retry completes it.
     */
    @Override
    @Transactional
    public void delete(UUID quarantineId, String reason) {
        UUID userId = TenantContext.getUserId();
        QuarantineRecord record = quarantineRecordRepository.lockByIdAndTenant(quarantineId, TenantContext.getTenantId())
                .orElseThrow(() -> new ResourceNotFoundException("QuarantineRecord", quarantineId));
        requireHeld(record);
        boolean releaseInFlight = scanResultRepository
                .findByReleasedFromScanResultIdOrderByCreatedAtDesc(record.getScanResultId()).stream()
                .anyMatch(attempt -> QuarantineMapper.ACTIVE_RELEASE_STATUSES.contains(attempt.getStatus()));
        if (releaseInFlight) {
            throw new QuarantineStateException("QUARANTINE_RELEASE_IN_PROGRESS",
                    "A release of this file is in progress - wait for it to finish before deleting");
        }

        StorageLocation location = storageLocationRepository
                .findByStorageLocationIdAndDeletedFalse(record.getQuarantineLocationId())
                .orElse(null);
        if (location != null) {
            objectStorageServiceFactory.resolve(location).delete(location, record.getQuarantineObjectKey());
        }

        OffsetDateTime now = OffsetDateTime.now();
        record.setStatus(QuarantineStatus.deleted);
        record.setReviewedBy(userId);
        record.setReviewedAt(now);
        record.setReviewReason(reason.trim());
        record.setUpdatedBy(userId);
        record.setUpdatedAt(now);
        quarantineRecordRepository.save(record);

        auditService.event(AuditActionCatalog.QUARANTINE_DELETED)
                .objectType(AuditObjectTypes.QUARANTINE_RECORD)
                .objectId(record.getQuarantineId())
                .resourceName(record.getOriginalFilename())
                .outcome(AuditOutcome.SUCCESS)
                .reason(reason)
                .newValue(Map.of("threatName", record.getThreatName(), "fileHash", record.getFileHash(),
                        "reviewer", userId, "scanResultId", record.getScanResultId()))
                .log();
        log.info("Quarantine record {} permanently deleted by {}", quarantineId, userId);
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public QuarantineReleaseResponse release(UUID quarantineId, String reason) {
        return quarantineReleaseService.release(quarantineId, reason.trim());
    }

    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public QuarantineReleaseResponse overrideRelease(UUID quarantineId, String reason) {
        return quarantineReleaseService.overrideRelease(quarantineId, reason.trim());
    }

    static void requireHeld(QuarantineRecord record) {
        if (record.getStatus() != QuarantineStatus.held) {
            throw new QuarantineStateException("QUARANTINE_NOT_HELD",
                    "This file is no longer held in quarantine (" + record.getStatus() + ")");
        }
    }

    private QuarantineRecord findOwned(UUID quarantineId) {
        return quarantineRecordRepository.findByQuarantineIdAndTenantId(quarantineId, TenantContext.getTenantId())
                .orElseThrow(() -> new ResourceNotFoundException("QuarantineRecord", quarantineId));
    }

    private Map<UUID, QuarantineUserSummary> loadUsers(Collection<UUID> ids) {
        List<UUID> wanted = ids.stream().filter(Objects::nonNull).distinct().toList();
        if (wanted.isEmpty()) {
            return Map.of();
        }
        UUID tenantId = TenantContext.getTenantId();
        return userRepository.findAllById(wanted).stream()
                .filter(user -> tenantId.equals(user.getTenantId()))
                .collect(Collectors.toMap(User::getUserId,
                        user -> new QuarantineUserSummary(user.getUserId(), user.getDisplayName()),
                        (a, b) -> a));
    }
}
