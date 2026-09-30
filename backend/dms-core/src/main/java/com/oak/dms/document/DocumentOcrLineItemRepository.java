package com.oak.dms.document;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DocumentOcrLineItemRepository extends JpaRepository<DocumentOcrLineItem, UUID> {

    /**
     * A version's lines, in the order they printed. Scoped by tenant as
     * well as version so a mis-scoped caller gets nothing rather than
     * another tenant's invoice.
     */
    List<DocumentOcrLineItem> findByVersionIdAndTenantIdOrderByLineNumberAsc(UUID versionId, UUID tenantId);
}
