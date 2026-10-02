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
}
