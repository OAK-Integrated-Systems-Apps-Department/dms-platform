package com.oak.dms.scanning.service;

import com.oak.dms.scanning.dto.IngestionRequest;
import com.oak.dms.scanning.dto.IngestionTicketResponse;

import java.util.UUID;

/**
 * The security boundary of the ingestion path: the only way bytes become a
 * {@code DocumentVersion}. Checks the file type, stages the upload (hashing it
 * in the same pass), scans it on the tenant's engine, and - only on a CLEAN
 * verdict - promotes it through {@link DocumentPromoter}. Infected files are
 * quarantined; no document row is ever created for them.
 * <p>
 * Every {@code DocumentService} path that creates a version - initial upload
 * today; replacement upload, workflow update, signed copy and migration
 * later - calls {@link #submit}.
 */
public interface IngestionGate {

    /** Must be called outside any transaction - the gate commits its own steps. */
    IngestionTicketResponse submit(IngestionRequest request);

    /** The ticket for one of the caller's own uploads (tenant and uploader must match). */
    IngestionTicketResponse getTicket(UUID scanResultId);
}
