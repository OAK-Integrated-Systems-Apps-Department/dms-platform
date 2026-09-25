package com.oak.dms.importexport.repository;

import com.oak.dms.importexport.entity.ImportBatch;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ImportBatchRepository extends JpaRepository<ImportBatch, UUID> {
}
