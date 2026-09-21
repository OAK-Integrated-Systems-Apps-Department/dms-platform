package com.oak.dms.metadata.entity;

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
 * One selectable value of a dropdown/multi_select {@link MetadataField}.
 * {@link #parentOptionId} is optional and self-referencing, enabling
 * cascading lists (e.g. a "Department" -> "Sub-Department" chain).
 */
@Entity
@Table(name = "metadata_field_options")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MetadataFieldOption {

    @Id
    @UuidGenerator
    @Column(name = "option_id", updatable = false, nullable = false)
    private UUID optionId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "field_id", nullable = false)
    private UUID fieldId;

    @Column(name = "parent_option_id")
    private UUID parentOptionId;

    @Column(name = "option_value", nullable = false)
    private String optionValue;

    @Column(name = "option_label", nullable = false)
    private String optionLabel;

    @Column(name = "display_order", nullable = false)
    @Builder.Default
    private int displayOrder = 0;

    /**
     * Read-only mirror of the generated column backing
     * {@code uq_mfo_field_order} (V26) - collapses to null for soft-deleted
     * rows so they never collide with a live row's display_order. Never set
     * by application code.
     */
    @Column(name = "display_order_uniq_key", insertable = false, updatable = false)
    private Integer displayOrderUniqKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    @Builder.Default
    private OptionStatus status = OptionStatus.active;

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

    public enum OptionStatus {
        active, inactive
    }
}
