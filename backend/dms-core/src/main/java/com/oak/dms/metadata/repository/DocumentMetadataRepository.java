package com.oak.dms.metadata.repository;

import com.oak.dms.metadata.entity.DocumentMetadata;
import com.oak.dms.metadata.entity.DocumentMetadataId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface DocumentMetadataRepository extends JpaRepository<DocumentMetadata, DocumentMetadataId>,
        DocumentMetadataRepositoryCustom {

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
