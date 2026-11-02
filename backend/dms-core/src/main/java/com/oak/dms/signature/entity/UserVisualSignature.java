package com.oak.dms.signature.entity;

import com.oak.dms.signature.enums.VisualSignatureKind;
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
 * One saved visual signature per user (V109) - a typed name in a script font,
 * or a small PNG drawn on a canvas, shown next to the real cryptographic
 * signature it accompanies. Purely cosmetic: not part of the signed manifest,
 * never checked by {@code SignatureVerifier} - the password/TOTP step-up and
 * the ECDSA signature remain the only thing actually verified.
 * <p>
 * Unlike {@link DocumentSignature}, this row is NOT append-only - it is the
 * user's own changeable preference (one row per {@code (tenantId, userId)},
 * upserted in place), not evidence. Each signature that uses it copies the
 * current value onto {@code document_signatures.visual_signature_*} at the
 * moment of signing, so a later change here never rewrites a past signature's
 * record.
 */
@Entity
@Table(name = "user_visual_signatures")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserVisualSignature {

    @Id
    @Column(name = "visual_signature_id", updatable = false, nullable = false)
    private UUID visualSignatureId;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false)
    private VisualSignatureKind kind;

    @Column(name = "typed_text")
    private String typedText;

    @Column(name = "image_png")
    private byte[] imagePng;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_by", nullable = false)
    private UUID updatedBy;

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private OffsetDateTime updatedAt = OffsetDateTime.now();
}
