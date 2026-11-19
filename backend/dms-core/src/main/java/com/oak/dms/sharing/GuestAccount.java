package com.oak.dms.sharing;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * An external collaborator with their own credentials (guest_accounts, V144). Not a Keycloak user: a guest can
 * only reach the client portal and only what a {@link GuestGrant} names.
 */
@Entity
@Table(name = "guest_accounts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GuestAccount {

    public static final String INVITED = "invited";
    public static final String ACTIVE = "active";
    public static final String DISABLED = "disabled";

    public static final String DISABLED_BY_ADMIN = "admin";
    public static final String DISABLED_INACTIVE = "inactivity";

    @Id
    @UuidGenerator
    @Column(name = "guest_id", updatable = false, nullable = false)
    private UUID guestId;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    /** Lower-cased. */
    @Column(name = "email", nullable = false)
    private String email;

    @Column(name = "display_name")
    private String displayName;

    @Column(name = "password_hash")
    private String passwordHash;

    @Column(name = "status", nullable = false)
    @Builder.Default
    private String status = INVITED;

    @Column(name = "disabled_reason")
    private String disabledReason;

    @Column(name = "invite_token_hash", length = 64)
    private String inviteTokenHash;

    @Column(name = "invite_expires_at")
    private OffsetDateTime inviteExpiresAt;

    @Column(name = "failed_attempts", nullable = false)
    @Builder.Default
    private int failedAttempts = 0;

    @Column(name = "locked_until")
    private OffsetDateTime lockedUntil;

    @Column(name = "last_login_at")
    private OffsetDateTime lastLoginAt;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();

    public String label() {
        return displayName != null && !displayName.isBlank() ? displayName + " <" + email + ">" : email;
    }
}
