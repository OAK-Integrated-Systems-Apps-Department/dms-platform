package com.oak.dms.ownership.repository;

import com.oak.dms.ownership.entity.OwnershipTransfer;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface OwnershipTransferRepository extends JpaRepository<OwnershipTransfer, UUID> {

    Page<OwnershipTransfer> findByTenantIdAndDocumentIdOrderByTransferredAtDescTransferIdDesc(
            UUID tenantId, UUID documentId, Pageable pageable);
}
