package com.oak.dms.security.auth;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A single-use, expiring token backing the self-service "forgot password"
 * flow in {@link PasswordResetService}. Deliberately owned by dms-core
 * rather than Keycloak: this is what lets the person land on our own
 * {@code /update-password} page instead of Keycloak's hosted
 * UPDATE_PASSWORD required-action UI.
 * <p>
 * Only {@link #tokenHash} is ever persisted - the raw token exists only
 * in the emailed link and the browser's address bar, never in this table
 * or the application logs. See the migration
 * ({@code V11__create_password_reset_tokens.sql}) for the same reasoning.
 */
@Entity
@Table(name = "password_reset_tokens")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PasswordResetToken {

    @Id
    @UuidGenerator
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "token_hash", nullable = false, unique = true)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    @Column(name = "used_at")
    private OffsetDateTime usedAt;

    @Column(name = "requested_ip")
    private String requestedIp;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();

    /**
     * Which of {@link PasswordResetService}'s issuance paths created this
     * token - {@code NEW_USER} for a brand-new invited account setting a
     * password for the first time, {@code PASSWORD_RESET} for both the
     * self-service "forgot password" flow and an admin-triggered reset
     * (end users experience those two identically, so they share one
     * value). Read by {@code GET /api/v1/auth/reset-password/validate} so
     * {@code /update-password} can show "welcome, set your password" vs
     * "reset your password" copy without ever exposing which internal
     * code path issued the token beyond that one distinction.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false)
    private TokenKind kind;

    public boolean isExpired(OffsetDateTime now) {
        return now.isAfter(expiresAt);
    }

    public boolean isUsed() {
        return usedAt != null;
    }

    public enum TokenKind {
        NEW_USER,
        PASSWORD_RESET
    }
}
