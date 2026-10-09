package com.oak.dms.importexport.repository;

import com.oak.dms.importexport.entity.ImportBatch;
import com.oak.dms.importexport.entity.ImportItem;
import com.oak.dms.importexport.worker.PendingCompletion;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ImportItemRepository extends JpaRepository<ImportItem, UUID> {

    List<ImportItem> findAllByImportBatchIdOrderByRowNumberAsc(UUID importBatchId);

    List<ImportItem> findAllByImportBatchIdAndStatusOrderByRowNumberAsc(UUID importBatchId,
                                                                         ImportItem.ImportItemStatus status);

    List<ImportItem> findAllByImportBatchIdAndImportItemIdIn(UUID importBatchId, Collection<UUID> importItemIds);

    List<ImportItem> findAllByImportBatchIdAndStagedObjectKeyIsNotNull(UUID importBatchId);

    long countByImportBatchIdAndStatus(UUID importBatchId, ImportItem.ImportItemStatus status);

    // Intake reads that touch only the rows they need (ix_import_items_batch_filename,
    // V52), instead of loading the whole batch for every uploaded file.

    /** The rows listing this filename - trimmed, ignoring case, as ImportFilenameMatcher compares. */
    @Query("select i from ImportItem i where i.importBatchId = :batchId "
            + "and lower(trim(i.filenameReference)) = lower(:filename) order by i.rowNumber")
    List<ImportItem> findAllByBatchAndFilename(@Param("batchId") UUID importBatchId, @Param("filename") String filename);

    @Query("select coalesce(sum(i.stagedSizeBytes), 0) from ImportItem i where i.importBatchId = :batchId")
    long sumStagedBytes(@Param("batchId") UUID importBatchId);

    /** Rows whose file has arrived - staged, or already handed on to the scan gate. */
    @Query("select count(i) from ImportItem i where i.importBatchId = :batchId "
            + "and (i.stagedObjectKey is not null or i.scanResultId is not null)")
    long countReceived(@Param("batchId") UUID importBatchId);

    /** Filenames of the rows still waiting for their file, in row order. */
    @Query("select i.filenameReference from ImportItem i where i.importBatchId = :batchId "
            + "and i.stagedObjectKey is null and i.scanResultId is null order by i.rowNumber")
    List<String> findMissingFilenames(@Param("batchId") UUID importBatchId);

    /**
     * Row-locks an item whose file the scan gate has accepted and which is
     * still waiting on the verdict - empty if it has been resolved already
     * or another worker holds it right now ({@code SKIP LOCKED}), so the
     * import worker and every instance of the completion job can race for
     * the same item and only one ever writes its outcome.
     */
    @Query(value = """
            select * from import_items
             where import_item_id = :importItemId
               and status = 'pending'
               and scan_result_id is not null
            for update skip locked
            """, nativeQuery = true)
    Optional<ImportItem> lockPendingForCompletion(@Param("importItemId") UUID importItemId);

    /**
     * Items still waiting on a scan verdict in batches that are running,
     * oldest first, with what the completion job needs from their batch.
     * Not locked - {@link #lockPendingForCompletion} claims each one.
     */
    @Query("""
            select new com.oak.dms.importexport.worker.PendingCompletion(i.importItemId, i.scanResultId,
                   b.importBatchId, b.tenantId, b.createdBy, b.documentTypeId, b.schemaVersion)
              from ImportItem i, ImportBatch b
             where i.importBatchId = b.importBatchId
               and b.status = :batchStatus
               and b.deleted = false
               and i.status = :itemStatus
               and i.scanResultId is not null
             order by i.updatedAt asc
            """)
    List<PendingCompletion> findPendingCompletions(@Param("batchStatus") ImportBatch.ImportBatchStatus batchStatus,
                                                   @Param("itemStatus") ImportItem.ImportItemStatus itemStatus,
                                                   Pageable pageable);

    default List<PendingCompletion> findPendingCompletions(Pageable pageable) {
        return findPendingCompletions(ImportBatch.ImportBatchStatus.running, ImportItem.ImportItemStatus.pending,
                pageable);
    }

    /**
     * {@code [ocr_status, count]} for the documents this batch created, for
     * the import's "OCR pending / done / failed" display (V84).
     *
     * <p>Joined through {@code document_id} to the document's CURRENT version,
     * not every version: a document someone has since uploaded a new version
     * of should report that version's OCR state, which is what the review
     * queue and the document list show too. One grouped query for the whole
     * batch, so the display costs the same for 10 rows and for 5,000.
     *
     * <p>Native, not JPQL: {@code Document}/{@code DocumentVersion} are in the
     * document module and are deliberately not mapped as associations from an
     * import item - the link is a recorded id, not ownership. A join through
     * entities would invent a relationship that the model does not have.
     */
    @Query(value = """
            select v.ocr_status, count(*)
              from import_items i
              join documents d on d.document_id = i.document_id
              join document_versions v on v.version_id = d.current_version_id
             where i.import_batch_id = :batchId
               and i.document_id is not null
               and v.ocr_status is not null
               and v.ocr_status <> 'not_required'
             group by v.ocr_status
            """, nativeQuery = true)
    List<Object[]> countOcrStatusesForBatch(@Param("batchId") UUID batchId);
}
