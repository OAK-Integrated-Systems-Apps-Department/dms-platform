package com.oak.dms.metadata.repository;

import com.oak.dms.metadata.entity.DocumentMetadata;
import com.oak.dms.metadata.entity.DocumentMetadataId;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface DocumentMetadataRepository extends JpaRepository<DocumentMetadata, DocumentMetadataId>,
        DocumentMetadataRepositoryCustom {

    /**
     * Same row {@link #findById} would return, but held with
     * {@code SELECT ... FOR UPDATE} for the rest of the caller's
     * transaction.
     *
     * <p>{@link com.oak.dms.document.DocumentMetadataServiceImpl#writeValues}
     * reads the whole {@code field_values} map, merges the caller's changes
     * into it in Java (so "manual always wins" can compare each touched
     * field's current {@code src} before deciding whether to overwrite it),
     * then writes the whole map back - a read-modify-write that two
     * concurrent writers (an OCR result and a manual save racing on the same
     * document, the exact case "manual always wins" exists for) can
     * interleave: both read the pre-race map, both merge independently, and
     * whichever commits second silently discards the other's change,
     * "manual always wins" included - its check only ever saw the stale map
     * the losing writer read before the race, not what actually ended up
     * committed.
     *
     * <p>Locking this row for the duration of the read-merge-write forces
     * the two writers to serialize instead: the second writer's lock
     * acquisition blocks until the first commits, so by the time it reads
     * the row it sees the first writer's result for real, and its own
     * (already-correct) manual-wins check runs against current data instead
     * of stale data.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from DocumentMetadata m where m.id = :id")
    Optional<DocumentMetadata> findByIdForUpdate(@Param("id") DocumentMetadataId id);

    /**
     * Sets one field's value in a single atomic {@code UPDATE}, leaving every
     * other key untouched - so two concurrent edits to different fields of
     * the same document never overwrite each other the way a read-modify-
     * write of the whole map would.
     *
     * @param valueJson the serialized {@code {"v": ..., "src": ..., "conf": ...}} object
     * @return rows updated (0 when the document has no metadata row)
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            update document_metadata
            set field_values = field_values || jsonb_build_object(cast(:fieldId as text), cast(:valueJson as jsonb)),
                updated_by = :userId,
                updated_at = now()
            where tenant_id = :tenantId and document_id = :documentId
            """, nativeQuery = true)
    int setFieldValue(@Param("tenantId") UUID tenantId,
                      @Param("documentId") UUID documentId,
                      @Param("fieldId") String fieldId,
                      @Param("valueJson") String valueJson,
                      @Param("userId") UUID userId);

    /** Removes one field's value in a single atomic {@code UPDATE}; see {@link #setFieldValue}. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            update document_metadata
            set field_values = field_values - cast(:fieldId as text),
                updated_by = :userId,
                updated_at = now()
            where tenant_id = :tenantId and document_id = :documentId
            """, nativeQuery = true)
    int clearFieldValue(@Param("tenantId") UUID tenantId,
                        @Param("documentId") UUID documentId,
                        @Param("fieldId") String fieldId,
                        @Param("userId") UUID userId);
}
