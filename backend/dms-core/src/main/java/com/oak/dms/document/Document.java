package com.oak.dms.document;

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
 * docs/database/V4 4.1, ported into a real migration at V32 (folder_id,
 * document-relationship/tag fields from the fuller V4 spec omitted --
 * nothing in this integration needs them yet). documentTypeId/
 * ownerUserId/*VersionId are plain UUID columns, not JPA relationships --
 * this mirrors ocr-worker's own SQLAlchemy models (FK column only, no ORM
 * traversal), which is all this minimal feature needs.
 * <p>
 * latestVersionId is NOT NULL and currently a foreign key back into
 * {@link DocumentVersion}, which in turn has a NOT NULL foreign key back
 * to this table's documentId -- see V32's migration comment for why both
 * constraints are DEFERRABLE INITIALLY DEFERRED, which is what makes it
 * safe to persist a Document and its first DocumentVersion (client-
 * generated UUIDs) in either order within one @Transactional method.
 */
@Entity
@Table(name = "documents")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Document {

    // No @UuidGenerator: DocumentServiceImpl assigns this UUID itself before
    // save() (it needs the value up front to build latestVersionId's
    // matching DocumentVersion row) -- generating it here too would make
    // Spring Data's isNew() check treat the entity as detached and merge()
    // instead of persist(), which fails with StaleObjectStateException.
    @Id
    @Column(name = "document_id", updatable = false, nullable = false)
    private UUID documentId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "document_type_id", nullable = false)
    private UUID documentTypeId;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "current_version_id")
    private UUID currentVersionId;

    @Column(name = "latest_version_id", nullable = false)
    private UUID latestVersionId;

    @Column(name = "published_version_id")
    private UUID publishedVersionId;

    @Column(name = "owner_user_id", nullable = false)
    private UUID ownerUserId;

    @Column(name = "status", nullable = false)
    @Builder.Default
    private String status = "draft";

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

    /**
     * A document type's template (V69): offered for download and as a starting point, never
     * listed as a document, and view-only except through DocumentTemplateService.
     */
    @Column(name = "is_template", nullable = false)
    @Builder.Default
    private boolean template = false;

    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;

    /** Set when it was permanently deleted from the recycle bin (V62) - its files and keys are gone. */
    @Column(name = "purged_at")
    private OffsetDateTime purgedAt;

    /** The folder the document is filed in (V51); null = not in a folder. */
    @Column(name = "folder_id")
    private UUID folderId;

    /**
     * inherit = follow the folder (top level: everyone); restricted = only the
     * people and roles in document_access (V54) - see
     * {@link com.oak.dms.folder.DocumentAccessService}.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "access_mode", nullable = false)
    @Builder.Default
    private com.oak.dms.folder.FolderAccessMode accessMode = com.oak.dms.folder.FolderAccessMode.inherit;

    /**
     * Set together (V105, both null or both set - see the CHECK) once a
     * workflow with at least one valid signature is approved - see
     * {@code com.oak.dms.signature.service.DocumentSignatureLockService}.
     * While set, new versions, restore, metadata edits, move and delete are
     * all refused (409 DOCUMENT_SIGNATURE_LOCKED) until a permission-gated,
     * reason-required unlock.
     */
    @Column(name = "signature_locked_at")
    private OffsetDateTime signatureLockedAt;

    @Column(name = "signature_locked_version_id")
    private UUID signatureLockedVersionId;
}
