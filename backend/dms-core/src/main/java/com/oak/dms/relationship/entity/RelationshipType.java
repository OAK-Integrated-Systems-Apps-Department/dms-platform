package com.oak.dms.relationship.entity;

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
 * A kind of link between two documents (V111), per tenant and editable by an admin. The ten
 * seeded ones are system types: they can be renamed and disabled, never deleted.
 */
@Entity
@Table(name = "relationship_types")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RelationshipType {

    public static final String DIRECTIONAL = "directional";
    public static final String SYMMETRIC = "symmetric";

    @Id
    @UuidGenerator
    @Column(name = "relationship_type_id", updatable = false, nullable = false)
    private UUID relationshipTypeId;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    /** Stable key (SUPERSEDES, ...); never changes after creation. */
    @Column(name = "code", nullable = false, updatable = false)
    private String code;

    @Column(name = "category", nullable = false)
    private String category;

    @Column(name = "label", nullable = false)
    private String label;

    /** How the link reads from the target document; equal to {@link #label} for a symmetric type. */
    @Column(name = "inverse_label", nullable = false)
    private String inverseLabel;

    @Column(name = "directionality", nullable = false)
    private String directionality;

    @Column(name = "prevents_cycles", nullable = false)
    private boolean preventsCycles;

    @Column(name = "is_system", nullable = false, updatable = false)
    private boolean system;

    @Column(name = "is_enabled", nullable = false)
    @Builder.Default
    private boolean enabled = true;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

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

    public boolean isSymmetric() {
        return SYMMETRIC.equals(directionality);
    }
}
