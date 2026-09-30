package com.oak.dms.importexport.repository;

import com.oak.dms.importexport.entity.ExportJob;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ExportJobRepository extends JpaRepository<ExportJob, UUID> {
}
