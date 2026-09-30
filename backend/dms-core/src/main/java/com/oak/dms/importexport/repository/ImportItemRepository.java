package com.oak.dms.importexport.repository;

import com.oak.dms.importexport.entity.ImportItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ImportItemRepository extends JpaRepository<ImportItem, UUID> {

    List<ImportItem> findAllByImportBatchIdOrderByRowNumberAsc(UUID importBatchId);
}
