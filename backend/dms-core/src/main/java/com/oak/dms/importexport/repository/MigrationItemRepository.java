package com.oak.dms.importexport.repository;

import com.oak.dms.importexport.entity.MigrationItem;
import com.oak.dms.importexport.enums.MigrationItemStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.UUID;

public interface MigrationItemRepository extends JpaRepository<MigrationItem, UUID> {

    Page<MigrationItem> findByMigrationBatchIdAndTenantId(UUID migrationBatchId, UUID tenantId, Pageable pageable);

    Page<MigrationItem> findByMigrationBatchIdAndTenantIdAndStatus(UUID migrationBatchId, UUID tenantId,
                                                                  MigrationItemStatus status, Pageable pageable);

    List<MigrationItem> findByMigrationBatchIdAndStatusOrderByRowNumberAsc(UUID migrationBatchId,
                                                                          MigrationItemStatus status);

    /** Rows not yet handed to the scan gate. */
    List<MigrationItem> findByMigrationBatchIdAndStatusAndScanResultIdIsNullOrderByRowNumberAsc(
            UUID migrationBatchId, MigrationItemStatus status);

    long countByMigrationBatchIdAndStatus(UUID migrationBatchId, MigrationItemStatus status);

    /** Rows whose file was handed to the scan gate before its scan finished (ix_migration_items_awaiting_scan). */
    @Query("select i from MigrationItem i where i.status = com.oak.dms.importexport.enums.MigrationItemStatus.pending "
            + "and i.scanResultId is not null order by i.createdAt")
    List<MigrationItem> findAwaitingScan(Pageable pageable);
}
