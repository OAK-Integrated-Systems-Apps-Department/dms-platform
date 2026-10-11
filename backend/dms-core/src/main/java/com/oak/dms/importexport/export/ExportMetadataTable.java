package com.oak.dms.importexport.export;

import java.util.List;

/**
 * The metadata file's content, one row per exported document, before it
 * is written as xlsx or CSV.
 *
 * @param fixedColumns how many leading columns are the fixed document
 *                     columns; the rest are metadata fields
 */
public record ExportMetadataTable(List<String> headers, int fixedColumns, List<List<ExportCell>> rows) {
}
