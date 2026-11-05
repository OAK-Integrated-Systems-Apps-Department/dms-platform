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
// Only the columns a caller changed are written. Ownership is changed with set-based SQL by the transfer code; a
// stale copy of this entity (loaded before a transfer committed) must not write the old owner back when it is saved.
@org.hibernate.annotations.DynamicUpdate
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

    /** The department the owner belongs to (V123); null when the owner had none. Set with the owner. */
    @Column(name = "owner_department_id")
    private UUID ownerDepartmentId;

    /** assigned = a person took ownership; holding = parked with a holding owner pending reassignment (V123). */
    @Column(name = "ownership_state", nullable = false)
    @Builder.Default
    private String ownershipState = OWNERSHIP_ASSIGNED;

    public static final String OWNERSHIP_ASSIGNED = "assigned";
    public static final String OWNERSHIP_HOLDING = "holding";

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

    /**
     * Exclusive check-out (V127): set together with {@link #checkedOutAt} and
     * {@link #checkoutExpiresAt}. Past the expiry the lock no longer blocks anyone - see
     * {@code com.oak.dms.checkout.DocumentCheckoutService}. Changed only through the
     * repository's atomic queries, never by saving this entity, so two people checking out at
     * once cannot both win.
     */
    @Column(name = "checked_out_by")
    private UUID checkedOutBy;

    @Column(name = "checked_out_at")
    private OffsetDateTime checkedOutAt;

    @Column(name = "checkout_expires_at")
    private OffsetDateTime checkoutExpiresAt;

    /** Legal hold (V127): while true, check-out and every edit path are refused. */
    @Column(name = "legal_hold", nullable = false)
    @Builder.Default
    private boolean legalHold = false;

    @Column(name = "legal_hold_at")
    private OffsetDateTime legalHoldAt;

    @Column(name = "legal_hold_by")
    private UUID legalHoldBy;

    @Column(name = "legal_hold_reason")
    private String legalHoldReason;

    // ---- Records lifecycle (V130, CRM) - separate from the approval status above. Changed only through the
    // repository's atomic queries (so a legal hold placed at the same moment always wins), never by saving this entity.

    public static final String ACTIVE = "active";
    public static final String INACTIVE = "inactive";
    public static final String ARCHIVED = "archived";

    /** active, inactive (switched off by hand) or archived (by its retention policy). */
    @Column(name = "lifecycle_state", nullable = false, insertable = false, updatable = false)
    @Builder.Default
    private String lifecycleState = ACTIVE;

    @Column(name = "lifecycle_changed_at", insertable = false, updatable = false)
    private OffsetDateTime lifecycleChangedAt;

    @Column(name = "lifecycle_changed_by", insertable = false, updatable = false)
    private UUID lifecycleChangedBy;

    /** manual, retention or legal_hold. */
    @Column(name = "lifecycle_change_source", insertable = false, updatable = false)
    private String lifecycleChangeSource;

    @Column(name = "lifecycle_reason", insertable = false, updatable = false)
    private String lifecycleReason;

    /** When it was last brought back to Active by hand - a reinstated document gets a fresh active period. */
    @Column(name = "reactivated_at", insertable = false, updatable = false)
    private OffsetDateTime reactivatedAt;

    /** The retention rule that applied at the last run, and the dates it gave (for the "archives on" display). */
    @Column(name = "retention_policy_id", insertable = false, updatable = false)
    private UUID retentionPolicyId;

    @Column(name = "archive_due_at", insertable = false, updatable = false)
    private OffsetDateTime archiveDueAt;

    @Column(name = "review_due_at", insertable = false, updatable = false)
    private OffsetDateTime reviewDueAt;

    /** Set once its total retention has elapsed - it waits here for an administrator's decision. */
    @Column(name = "review_flagged_at", insertable = false, updatable = false)
    private OffsetDateTime reviewFlaggedAt;

    /** An administrator extended retention (instead of proceeding to disposal) until this moment. */
    @Column(name = "retention_extended_until", insertable = false, updatable = false)
    private OffsetDateTime retentionExtendedUntil;

    /** No rule covered it, so it waits for an administrator to assign one. */
    @Column(name = "no_policy_flagged_at", insertable = false, updatable = false)
    private OffsetDateTime noPolicyFlaggedAt;

    /** True while an unexpired check-out is in force - the one place "is it locked" is decided. */
    public boolean isCheckedOutAt(OffsetDateTime now) {
        return checkedOutBy != null && checkoutExpiresAt != null && checkoutExpiresAt.isAfter(now);
    }

    /**
     * Set the first time a standalone signing action (adding a page, or
     * placing a mark) touches this document since it last had none (V119) -
     * the version "remove signature" reverts {@link #currentVersionId} back
     * to. Cleared to null once that removal completes, so the next
     * standalone signing session pins a fresh baseline rather than reverting
     * past it. Mirrors {@code WorkflowTask#signaturePlacementBaseVersionId},
     * but lives on the document (not a task) since a standalone session has
     * no task/instance to hold it, and resets instead of staying pinned
     * forever - a document can be signed, unsigned and re-signed many times
     * over its life, unlike one task's single signing ceremony.
     */
    @Column(name = "standalone_signature_base_version_id")
    private UUID standaloneSignatureBaseVersionId;
}
