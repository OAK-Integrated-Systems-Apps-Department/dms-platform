package com.oak.dms.importexport.repository;

import com.oak.dms.importexport.entity.UploadSession;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UploadSessionRepository extends JpaRepository<UploadSession, UUID> {

    /** A session is only ever usable by the user who started it. */
    Optional<UploadSession> findByUploadSessionIdAndTenantIdAndInitiatedByAndDeletedFalse(UUID uploadSessionId,
                                                                                          UUID tenantId,
                                                                                          UUID initiatedBy);

    /** One chunk at a time per session: a concurrent chunk waits, then sees the new offset. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from UploadSession s where s.uploadSessionId = :id")
    Optional<UploadSession> lockById(@Param("id") UUID uploadSessionId);

    List<UploadSession> findByDeletedFalseAndExpiresAtBefore(OffsetDateTime now);
}
