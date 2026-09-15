package com.oak.dms.user;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "users", uniqueConstraints = {
        @UniqueConstraint(name = "uq_users_tenant_email", columnNames = {"tenant_id", "email"}),
        @UniqueConstraint(name = "uq_users_tenant_username", columnNames = {"tenant_id", "username"})
})
/**
 * A person's Oak DMS profile, scoped to exactly one tenant.
 * keycloakId links this row to the corresponding Keycloak account, which
 * remains the system of record for credentials, MFA enrollment, and login;
 * this table only holds DMS-domain profile and authorization data.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User {

    @Id
    @UuidGenerator
    @Column(name = "user_id", updatable = false, nullable = false)
    private UUID userId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    /**
     * Subject id ("sub" claim) of the corresponding account in Keycloak.
     * Keycloak remains the system of record for credentials, MFA enrollment
     * and login; this table stores DMS-domain profile/authorization data.
     */
    @Column(name = "keycloak_id", nullable = false, unique = true)
    private UUID keycloakId;

    @Column(name = "email", nullable = false)
    private String email;

    /**
     * The account's real login id, distinct from {@link #email} - set once
     * at creation (see {@code UserServiceImpl#createUser}) and mirrored
     * verbatim as the Keycloak account's own username (see
     * {@code KeycloakUserService#createUser}), never derived from the
     * email locally. Immutable after creation: there's no update path for
     * it, matching the Edit User form's own read-only treatment of this
     * field.
     */
    @Column(name = "username", nullable = false)
    private String username;

    @Column(name = "first_name", nullable = false)
    private String firstName;

    /** Nullable/blank-allowed: not everyone has a distinct last name. */
    @Column(name = "last_name")
    private String lastName;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Column(name = "mfa_enabled", nullable = false)
    @Builder.Default
    private boolean mfaEnabled = false;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    @Builder.Default
    private UserStatus status = UserStatus.active;

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_by")
    private UUID updatedBy;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @Column(name = "is_deleted", nullable = false)
    @Builder.Default
    private boolean deleted = false;

    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;

    /**
     * Whether this user has actually completed account setup (verified
     * their email and set a real password via Keycloak's VERIFY_EMAIL /
     * UPDATE_PASSWORD required actions), as opposed to still sitting on
     * an unopened invite. False from the moment createUser() invites
     * them; flipped by UserServiceImpl#markVerified the first time they
     * successfully authenticate. Independent of {@link #status} — a
     * freshly-invited, still-pending user has status active and
     * verified false at the same time.
     */
    @Column(name = "verified", nullable = false)
    @Builder.Default
    private boolean verified = false;

    @Column(name = "verified_at")
    private OffsetDateTime verifiedAt;

    /**
     * When this user last successfully signed in via {@code AuthService#login}
     * (the direct password/OTP grant) - null for a user who has never
     * logged in yet, including one who was just invited and hasn't
     * completed setup. Deliberately not touched by {@code AuthService#refresh}
     * (silently renewing an existing session isn't a new login) or by
     * anything Keycloak-side - this is dms-core's own record, written
     * once per successful direct-grant login.
     */
    @Column(name = "last_login_at")
    private OffsetDateTime lastLoginAt;

    /**
     * When this user's password was last actually changed via dms-core's
     * own flows - {@code PasswordResetService#resetPassword}, reached by
     * both the self-service ({@code /reset-password}) and admin-triggered
     * reset flows, which both funnel through that same method. Null for a
     * user whose password has never been changed under this tracking
     * (including one still on their initial invite-provisioned temporary
     * password - see {@code KeycloakUserService#setTemporaryPassword},
     * which deliberately does NOT stamp this). Like {@link #lastLoginAt},
     * this can't see a password changed directly through Keycloak's own
     * hosted Account Console, which stays a known gap.
     */
    @Column(name = "last_password_changed_at")
    private OffsetDateTime lastPasswordChangedAt;

    public enum UserStatus {
        active, locked, disabled
    }

    /**
     * Single source of truth for building a display name from parts, used
     * everywhere a {@link User} or the corresponding Keycloak account is
     * created or renamed, so display_name can never drift out of sync with
     * first_name/last_name (the previous approach - splitting a
     * caller-supplied display name into first/last on whitespace - could
     * silently produce a wrong split for single-word or 3+-word names).
     */
    public static String displayName(String firstName, String lastName) {
        String trimmedLast = lastName == null ? "" : lastName.trim();
        return trimmedLast.isEmpty() ? firstName.trim() : firstName.trim() + " " + trimmedLast;
    }
}