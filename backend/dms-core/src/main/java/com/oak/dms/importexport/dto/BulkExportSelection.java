package com.oak.dms.importexport.dto;

import java.util.List;
import java.util.UUID;

/**
 * Which documents a bulk export covers: either the documents picked one by
 * one ({@code documentIds}) or every document matching {@code filters} -
 * exactly one of the two. Either way only documents the caller may export
 * are counted; anything else in {@code documentIds} is silently left out.
 */
public record BulkExportSelection(List<UUID> documentIds, BulkExportFilters filters) {
}
