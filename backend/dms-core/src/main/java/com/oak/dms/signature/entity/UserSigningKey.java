package com.oak.dms.signature.entity;

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

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One user's ECDSA P-256 signing key (V113), named
 * {@code dms-sig-<tenantId>-<userId>-<generation>}. {@code generation}
 * increments each time the active key is revoked, so a new key never reuses
 * an old Transit/file key name - see {@code SigningKeyService}.
 * <p>
 * {@code publicKeyPem} is stored here (as well as copied onto every
 * {@code DocumentSignature} it produces) so the current key can be shown/used
 * without a round trip to the signer backend. Revoking a key never
 * invalidates signatures it made earlier - only {@code status}/revoke fields
 * ever change after insert; {@code backend} is the wire value
 * ("transit"/"local-dev"), not the {@link com.oak.dms.signature.enums.SigningBackend}
 * enum name, so it is stored as a plain string.
 */
@Entity
@Table(name = "user_signing_keys")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserSigningKey {

    @Id
    @Column(name = "key_id", updatable = false, nullable = false)
    private UUID keyId;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "generation", nullable = false, updatable = false)
    private int generation;

    @Column(name = "key_name", nullable = false, updatable = false)
    private String keyName;

    @Column(name = "public_key_pem", nullable = false, updatable = false)
    private String publicKeyPem;

    @Column(name = "algorithm", nullable = false, updatable = false)
    @Builder.Default
    private String algorithm = "ECDSA_P256_SHA256";

    /** "transit" or "local-dev" - see {@link com.oak.dms.signature.enums.SigningBackend#wireValue()}. */
    @Column(name = "backend", nullable = false, updatable = false)
    private String backend;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    @Builder.Default
    private Status status = Status.active;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "revoked_by")
    private UUID revokedBy;

    @Column(name = "revoked_at")
    private OffsetDateTime revokedAt;

    @Column(name = "revoke_reason")
    private String revokeReason;

    public enum Status {
        active, revoked
    }
}
