package com.oak.dms.storage.entity;

import com.oak.dms.storage.enums.StorageProvider;
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
 * A physical/logical object-storage destination: which {@link StorageProvider}
 * family it uses, and the connection details {@link com.oak.dms.storage.service.ObjectStorageServiceFactory}
 * needs to resolve the right {@link com.oak.dms.storage.service.ObjectStorageService} for it. Never holds a
 * raw credential - see {@link #credentialRef}'s own doc comment.
 * <p>
 * {@code tenantId} null means this is the platform-wide default row (see
 * {@code StorageLocationBootstrap}), selectable by any tenant that hasn't
 * registered its own location - see {@link com.oak.dms.storage.service.StorageLocationResolver}.
 */
@Entity
@Table(name = "storage_locations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StorageLocation {

    @Id
    @UuidGenerator
    @Column(name = "storage_location_id", updatable = false, nullable = false)
    private UUID storageLocationId;

    @Column(name = "tenant_id")
    private UUID tenantId;

    @Column(name = "name", nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false)
    private StorageProvider provider;

    /**
     * S3: blank/null means the provider's own default endpoint (real AWS S3).
     * AZURE_BLOB: the Blob Service endpoint URL (e.g.
     * {@code https://<account>.blob.core.windows.net}) - Azure has no single
     * universal default, so a blank value here falls back to this
     * deployment's {@code dms.storage.azure-blob.endpoint} default.
     */
    @Column(name = "endpoint")
    private String endpoint;

    /** S3 only. */
    @Column(name = "region")
    private String region;

    /**
     * S3: the bucket this location's objects live in. AZURE_BLOB: the blob
     * container name (reused rather than adding a separate column, same
     * connection-detail role either way).
     */
    @Column(name = "bucket")
    private String bucket;

    /** FILE_SYSTEM only - the confinement root every object key is resolved under. */
    @Column(name = "base_path")
    private String basePath;

    /** S3 only - MinIO/SeaweedFS typically need path-style bucket addressing; real AWS S3 does not. */
    @Column(name = "path_style_access", nullable = false)
    @Builder.Default
    private boolean pathStyleAccess = false;

    /**
     * NEVER a raw credential - an indirection key each adapter resolves
     * against environment variables at call time: S3's
     * {@link com.oak.dms.storage.adapter.S3ObjectStorageAdapter} reads
     * {@code STORAGE_CRED_<ref>_ACCESS_KEY}/{@code _SECRET_KEY}; AZURE_BLOB's
     * {@link com.oak.dms.storage.adapter.AzureBlobStorageAdapter} reads
     * {@code STORAGE_CRED_<ref>_ACCOUNT_NAME}/{@code _ACCOUNT_KEY}. Null/blank
     * falls back to this deployment's single {@code dms.storage.s3.*}/
     * {@code dms.storage.azure-blob.*} configuration (see
     * {@code StorageProperties}) - covers both the platform-default location
     * and any tenant content with the deployment's one configured backend.
     */
    @Column(name = "credential_ref")
    private String credentialRef;

    /**
     * Pre-serialized JSON string (same convention as
     * {@code AuditEvent.previousValue}/{@code newValue}), not a native jsonb
     * mapping - an escape hatch for provider-specific extras a future adapter
     * (e.g. Azure Blob's connection-string shape) needs without another
     * migration adding another rigid column. Null for both providers this
     * pass actually implements.
     */
    @Column(name = "configuration")
    private String configuration;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    @Builder.Default
    private Status status = Status.active;

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

    public enum Status {
        active, inactive
    }
}
