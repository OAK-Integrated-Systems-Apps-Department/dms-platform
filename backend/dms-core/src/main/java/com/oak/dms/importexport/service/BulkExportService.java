package com.oak.dms.importexport.service;

import com.oak.dms.importexport.dto.BulkExportDownload;
import com.oak.dms.importexport.dto.BulkExportFilters;
import com.oak.dms.importexport.dto.BulkExportJobResponse;
import com.oak.dms.importexport.dto.BulkExportPreviewResponse;
import com.oak.dms.importexport.dto.BulkExportSelection;
import com.oak.dms.importexport.dto.CreateBulkExportRequest;
import com.oak.dms.importexport.dto.ExportableDocumentResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

/**
 * Bulk export (Module 2.1): pick documents (one by one, or by filters),
 * request an export, and download its ZIP - every exported document's
 * current version plus a metadata file - once the worker has built it.
 * Everything here is scoped to the caller's tenant, only documents the
 * caller may export are ever listed, counted or exported (see
 * {@code BulkExportAccessPolicy}), and an export is only visible to the
 * user who requested it.
 */
public interface BulkExportService {

    /** Documents the caller may export, newest first (page size capped at 50). */
    Page<ExportableDocumentResponse> listDocuments(BulkExportFilters filters, Pageable pageable);

    /** How many documents, and how many bytes, an export of {@code selection} would contain, and whether that is within the limits. */
    BulkExportPreviewResponse preview(BulkExportSelection selection);

    /**
     * Queues an export of {@code request}'s selection: freezes the
     * documents it resolves to, then the worker builds the ZIP.
     *
     * @throws com.oak.dms.importexport.exception.ActiveExportLimitException
     *         if the caller already has the maximum active exports
     * @throws com.oak.dms.importexport.exception.BulkExportSelectionException
     *         if the selection is malformed, empty or over a limit
     */
    BulkExportJobResponse request(CreateBulkExportRequest request);

    /** The caller's exports, newest first (page size capped at 50). */
    Page<BulkExportJobResponse> listMine(Pageable pageable);

    BulkExportJobResponse getMine(UUID exportJobId);

    /**
     * Opens the caller's completed, unexpired export for download.
     *
     * @throws com.oak.dms.importexport.exception.ExportFileUnavailableException
     *         if it isn't completed, or has expired
     */
    BulkExportDownload openDownload(UUID exportJobId);
}
