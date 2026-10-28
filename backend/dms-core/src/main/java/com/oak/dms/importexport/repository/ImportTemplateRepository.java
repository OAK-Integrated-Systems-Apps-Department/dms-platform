package com.oak.dms.importexport.repository;

import com.oak.dms.importexport.entity.ImportTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Tenant-facing reads always take the tenant id. */
public interface ImportTemplateRepository extends JpaRepository<ImportTemplate, UUID> {

    Optional<ImportTemplate> findByImportTemplateIdAndTenantIdAndDeletedFalse(UUID importTemplateId, UUID tenantId);

    List<ImportTemplate> findByTenantIdAndDeletedFalseOrderByNameAsc(UUID tenantId);

    List<ImportTemplate> findByTenantIdAndDocumentTypeIdAndDeletedFalseOrderByNameAsc(UUID tenantId, UUID documentTypeId);

    boolean existsByTenantIdAndDocumentTypeIdAndNameIgnoreCaseAndDeletedFalse(UUID tenantId, UUID documentTypeId,
                                                                           String name);
}
