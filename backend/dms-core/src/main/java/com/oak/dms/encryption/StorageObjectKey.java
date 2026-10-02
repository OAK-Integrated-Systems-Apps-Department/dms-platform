package com.oak.dms.encryption;

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

/** The wrapped data key of one encrypted stored object (storage_object_keys, V55). */
@Entity
@Table(name = "storage_object_keys")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StorageObjectKey {

    @Id
    @UuidGenerator
    @Column(name = "storage_object_key_id", updatable = false, nullable = false)
    private UUID storageObjectKeyId;

    @Column(name = "storage_location_id", nullable = false, updatable = false)
    private UUID storageLocationId;

    @Column(name = "object_key", nullable = false, updatable = false)
    private String objectKey;

    @Column(name = "wrapped_key", nullable = false)
    private String wrappedKey;

    @Column(name = "key_provider", nullable = false)
    private String keyProvider;

    @Column(name = "key_version", nullable = false)
    private int keyVersion;

    /** Whose object this is (V57); null for an object written outside any tenant. */
    @Column(name = "tenant_id")
    private UUID tenantId;

    /** Which master key wraps it: the tenant's id, or "platform" (V57). */
    @Column(name = "key_scope", nullable = false)
    @Builder.Default
    private String keyScope = KeyEncryptionService.PLATFORM;

    @Column(name = "algorithm", nullable = false)
    private String algorithm;

    @Column(name = "plaintext_size", nullable = false)
    private long plaintextSize;

    /**
     * Where the encrypted bytes physically live when that isn't {@link #objectKey} - set for a document
     * encrypted after it was stored (V56), whose version key can't change. Null: at {@code objectKey}.
     */
    @Column(name = "physical_object_key")
    private String physicalObjectKey;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "rewrapped_at")
    private OffsetDateTime rewrappedAt;

    /** Set when the tenant's keys were crypto-shredded (V60): the wrapped key is gone, the object unreadable. */
    @Column(name = "shredded_at")
    private OffsetDateTime shreddedAt;
}
