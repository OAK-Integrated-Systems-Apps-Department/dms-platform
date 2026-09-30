package com.oak.dms.scanning.service;

import com.oak.dms.scanning.dto.QuarantineRecordDetailResponse;
import com.oak.dms.scanning.dto.QuarantineRecordResponse;
import com.oak.dms.scanning.dto.QuarantineReleaseResponse;
import com.oak.dms.scanning.enums.QuarantineStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Administrator review of quarantined files (VMSBDI FR-005). Everything is
 * scoped to the caller's tenant - another tenant's record is indistinguishable
 * from a missing one. Never returns or streams the quarantined bytes.
 */
public interface QuarantineService {

    Page<QuarantineRecordResponse> list(QuarantineStatus status, OffsetDateTime detectedFrom,
                                        OffsetDateTime detectedTo, String search, Pageable pageable);

    QuarantineRecordDetailResponse get(UUID quarantineId);

    /** Removes the object from quarantine storage and marks the record {@code deleted}. */
    void delete(UUID quarantineId, String reason);

    /** Re-scans the file on the tenant's current engine; releases it only if it now comes back clean. */
    QuarantineReleaseResponse release(UUID quarantineId, String reason);

    /** Force-releases a file whose re-scan was refused. Separate permission, HIGH audit. */
    QuarantineReleaseResponse overrideRelease(UUID quarantineId, String reason);
}
