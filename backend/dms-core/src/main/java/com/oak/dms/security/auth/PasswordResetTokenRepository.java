package com.oak.dms.security.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, UUID> {

    Optional<PasswordResetToken> findByTokenHash(String tokenHash);

    List<PasswordResetToken> findAllByUserIdAndUsedAtIsNull(UUID userId);

    /**
     * Best-effort housekeeping, not a security boundary: {@link #findByTokenHash}
     * already re-checks {@code expiresAt}/{@code usedAt} on every lookup, so a
     * stale row left behind by a skipped sweep can never be redeemed. Called
     * opportunistically from {@link PasswordResetService} rather than on a
     * schedule, matching {@code LoginAttemptLimiter}'s "no background thread"
     * approach for this single-instance deployment.
     */
    @Modifying
    @Query("delete from PasswordResetToken t where t.expiresAt < :cutoff")
    void deleteAllExpiredBefore(@Param("cutoff") OffsetDateTime cutoff);
}
