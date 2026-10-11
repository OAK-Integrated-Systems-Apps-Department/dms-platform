package com.oak.dms.importexport.dto;

import com.oak.dms.importexport.entity.ExportJob.MetadataFormat;
import jakarta.validation.constraints.NotNull;

/**
 * @param metadataFormat format of the metadata file in the ZIP - xlsx (for
 *                       people, the default when omitted) or csv (for
 *                       systems)
 */
public record CreateBulkExportRequest(@NotNull BulkExportSelection selection, MetadataFormat metadataFormat) {
}
