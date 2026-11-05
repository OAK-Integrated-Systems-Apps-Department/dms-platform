package com.oak.dms.preview;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

/** One organisation's preview security policy (V135). No row means everything is allowed - see {@link PreviewPolicyService}. */
@Entity
@Table(name = "tenant_preview_policy")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TenantPreviewPolicy {

    @Id
    @Column(name = "tenant_id", updatable = false, nullable = false)
    private UUID tenantId;

    @Column(name = "print_mode", nullable = false)
    @Builder.Default
    private String printMode = PreviewPolicyService.ALLOW;

    @Column(name = "download_mode", nullable = false)
    @Builder.Default
    private String downloadMode = PreviewPolicyService.ALLOW;

    @Column(name = "copy_mode", nullable = false)
    @Builder.Default
    private String copyMode = PreviewPolicyService.ALLOW;

    @Column(name = "updated_by")
    private UUID updatedBy;

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private OffsetDateTime updatedAt = OffsetDateTime.now();
}
