package com.oak.dms.metadata;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Every query filters on {@code tenantId} explicitly, even where the
 * field/document type id alone would already pin the rows down - same
 * belt-and-braces tenant scoping as the other metadata repositories.
 */
public interface MetadataFieldLabelAliasRepository extends JpaRepository<MetadataFieldLabelAlias, UUID> {

    @Query("""
            select a from MetadataFieldLabelAlias a
            where a.tenantId = :tenantId and a.fieldId = :fieldId and a.documentTypeId is null
            order by a.displayOrder asc
            """)
    List<MetadataFieldLabelAlias> findGlobalByField(@Param("tenantId") UUID tenantId,
                                                    @Param("fieldId") UUID fieldId);

    /** Batch form of {@link #findGlobalByField}, for list endpoints (one query, not one per field). */
    @Query("""
            select a from MetadataFieldLabelAlias a
            where a.tenantId = :tenantId and a.fieldId in :fieldIds and a.documentTypeId is null
            order by a.fieldId, a.displayOrder asc
            """)
    List<MetadataFieldLabelAlias> findGlobalByFields(@Param("tenantId") UUID tenantId,
                                                     @Param("fieldIds") Collection<UUID> fieldIds);

    @Query("""
            select a from MetadataFieldLabelAlias a
            where a.tenantId = :tenantId and a.documentTypeId = :documentTypeId and a.fieldId = :fieldId
            order by a.displayOrder asc
            """)
    List<MetadataFieldLabelAlias> findTypeScopedByField(@Param("tenantId") UUID tenantId,
                                                        @Param("documentTypeId") UUID documentTypeId,
                                                        @Param("fieldId") UUID fieldId);

    /** Every type-scoped hint of one document type, across all its fields - batch-loads a mapping listing. */
    @Query("""
            select a from MetadataFieldLabelAlias a
            where a.tenantId = :tenantId and a.documentTypeId = :documentTypeId
            order by a.fieldId, a.displayOrder asc
            """)
    List<MetadataFieldLabelAlias> findTypeScopedByDocumentType(@Param("tenantId") UUID tenantId,
                                                               @Param("documentTypeId") UUID documentTypeId);

    /*
     * The deletes below are JPQL bulk deletes on purpose, not a derived
     * deleteBy... (which loads each entity and em.remove()s it): Hibernate
     * flushes queued INSERTs before queued DELETEs, so re-inserting a hint
     * whose text is unchanged would trip uq_mfla_field_scope_alias against
     * its own not-yet-deleted old row. A bulk delete executes against the
     * database immediately, before any replacement row is even queued.
     * flushAutomatically writes out any earlier pending changes in the same
     * transaction first (e.g. the field update that accompanies a global
     * hint replace), so the delete never runs ahead of them.
     */

    @Modifying(flushAutomatically = true)
    @Query("""
            delete from MetadataFieldLabelAlias a
            where a.tenantId = :tenantId and a.fieldId = :fieldId and a.documentTypeId is null
            """)
    int deleteGlobalByField(@Param("tenantId") UUID tenantId, @Param("fieldId") UUID fieldId);

    @Modifying(flushAutomatically = true)
    @Query("""
            delete from MetadataFieldLabelAlias a
            where a.tenantId = :tenantId and a.documentTypeId = :documentTypeId and a.fieldId = :fieldId
            """)
    int deleteTypeScopedByField(@Param("tenantId") UUID tenantId,
                                @Param("documentTypeId") UUID documentTypeId,
                                @Param("fieldId") UUID fieldId);

    /**
     * A publish's cleanup: every type-scoped hint of the document type whose
     * field is NOT among {@code mappedFieldIds} (the new version's fields).
     * Needs a non-empty collection - an empty {@code not in ()} isn't
     * portable JPQL - so a publish that maps no fields at all uses
     * {@link #deleteTypeScopedByDocumentType} instead.
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            delete from MetadataFieldLabelAlias a
            where a.tenantId = :tenantId and a.documentTypeId = :documentTypeId
              and a.fieldId not in :mappedFieldIds
            """)
    int deleteTypeScopedByDocumentTypeExceptFields(@Param("tenantId") UUID tenantId,
                                                   @Param("documentTypeId") UUID documentTypeId,
                                                   @Param("mappedFieldIds") Collection<UUID> mappedFieldIds);

    @Modifying(flushAutomatically = true)
    @Query("""
            delete from MetadataFieldLabelAlias a
            where a.tenantId = :tenantId and a.documentTypeId = :documentTypeId
            """)
    int deleteTypeScopedByDocumentType(@Param("tenantId") UUID tenantId,
                                       @Param("documentTypeId") UUID documentTypeId);
}
