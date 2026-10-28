package com.oak.dms.document;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface DocumentOcrCorrectionRepository extends JpaRepository<DocumentOcrCorrection, UUID> {

    /**
     * Every decision recorded for a version, newest first -- so the first
     * row per field_key is the one that currently stands. The table is
     * append-only (see {@link DocumentOcrCorrection}), so "latest wins"
     * is the read rule, not a constraint.
     */
    List<DocumentOcrCorrection> findByVersionIdAndTenantIdOrderByCorrectedAtDesc(UUID versionId, UUID tenantId);
}
