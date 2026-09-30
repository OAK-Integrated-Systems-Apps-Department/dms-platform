package com.oak.dms.scanning.entity;

import com.oak.dms.scanning.enums.ScanMode;
import com.oak.dms.scanning.enums.ScanProvider;
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
 * A configured malware-scanning engine: which {@link ScanProvider} it uses and
 * the connection details {@link com.oak.dms.scanning.service.ScanEngineFactory}
 * needs to resolve the right {@link com.oak.dms.scanning.service.ScanService}.
 * Mirrors {@link com.oak.dms.storage.entity.StorageLocation}. Never holds a raw
 * credential - see {@link #credentialRef}.
 * <p>
 * {@code tenantId} null means this is the platform-wide default row (seeded by
 * {@code ScanEngineBootstrap}), used by any tenant that hasn't registered its
 * own engine - see {@link com.oak.dms.scanning.service.ScanEngineResolver}.
 */
@Entity
@Table(name = "scan_engines")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ScanEngine {

    @Id
    @UuidGenerator
    @Column(name = "scan_engine_id", updatable = false, nullable = false)
    private UUID scanEngineId;

    @Column(name = "tenant_id")
    private UUID tenantId;

    @Column(name = "name", nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "provider", nullable = false)
    private ScanProvider provider;

    @Enumerated(EnumType.STRING)
    @Column(name = "scan_mode", nullable = false)
    private ScanMode scanMode;

    /**
     * CLAMAV: {@code host:port} of {@code clamd}. AZURE_DEFENDER: the Blob
     * Service endpoint of the Defender-protected storage account. Unused by
     * AWS_GUARDDUTY. Blank falls back to this deployment's
     * {@code dms.scanning.*} default at call time - the platform-default row
     * is seeded blank on purpose, so the same row works whether dms-core runs
     * on the host or inside Compose.
     */
    @Column(name = "endpoint")
    private String endpoint;

    /** AWS_GUARDDUTY only. Blank falls back to {@code dms.scanning.aws-guardduty.region}. */
    @Column(name = "region")
    private String region;

    /**
     * NEVER a raw credential - an indirection key the cloud adapters resolve
     * against environment variables at call time:
     * {@code SCAN_CRED_<ref>_ACCESS_KEY}/{@code _SECRET_KEY} (AWS) or
     * {@code SCAN_CRED_<ref>_ACCOUNT_NAME}/{@code _ACCOUNT_KEY} (Azure). Null
     * or blank falls back to this deployment's {@code dms.scanning.*}
     * credentials. ClamAV has no authentication.
     */
    @Column(name = "credential_ref")
    private String credentialRef;

    @Column(name = "timeout_seconds", nullable = false)
    private int timeoutSeconds;

    @Column(name = "max_file_size_bytes", nullable = false)
    private long maxFileSizeBytes;

    /** Pre-serialized JSON string, same convention as {@code StorageLocation.configuration}. Unused in Phase 1. */
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
