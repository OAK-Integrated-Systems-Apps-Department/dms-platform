package com.oak.dms.reporting.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.UUID;

/** A tenant administrator's override for one catalogue field (V138 {@code report_field_settings}). No row = the field's defaults. */
@Entity
@Table(name = "report_field_settings")
@IdClass(ReportFieldSetting.Key.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReportFieldSetting {

    @Id
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Id
    @Column(name = "field_key", nullable = false, updatable = false)
    private String fieldKey;

    @Column(name = "enabled", nullable = false)
    @Builder.Default
    private boolean enabled = true;

    @Column(name = "label_override")
    private String labelOverride;

    @Column(name = "selectable", nullable = false)
    @Builder.Default
    private boolean selectable = true;

    @Column(name = "filterable", nullable = false)
    @Builder.Default
    private boolean filterable = true;

    @Column(name = "sortable", nullable = false)
    @Builder.Default
    private boolean sortable = true;

    @Column(name = "groupable", nullable = false)
    @Builder.Default
    private boolean groupable = true;

    @Column(name = "default_selected", nullable = false)
    private boolean defaultSelected;

    @Column(name = "display_order")
    private Integer displayOrder;

    @Column(name = "show_code", nullable = false)
    private boolean showCode;

    @Column(name = "updated_by")
    private UUID updatedBy;

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    public record Key(UUID tenantId, String fieldKey) implements Serializable {
    }
}
