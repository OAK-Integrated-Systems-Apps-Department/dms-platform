package com.oak.dms.importexport.export;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What {@code export_jobs.request_params} holds for a bulk export. The
 * document IDs are frozen at request time - the worker exports exactly
 * these (each re-checked), never whatever the filters match later. The
 * filters and the readable selection lines are kept for the record and
 * for the ZIP's "About this export" sheet.
 *
 * @param selectionMode {@value #MODE_DOCUMENTS} (picked one by one) or
 *                      {@value #MODE_FILTERS}
 * @param filters       the filters as given, ISO strings, only those set
 * @param selection     label/value lines describing the selection, with
 *                      names resolved at request time
 * @param totalBytes    the documents' combined size at request time
 */
public record BulkExportRequestParams(String selectionMode, Map<String, String> filters,
                                      List<SelectionLine> selection, List<UUID> documentIds, long totalBytes) {

    public static final String MODE_DOCUMENTS = "documents";
    public static final String MODE_FILTERS = "filters";

    public record SelectionLine(String label, String value) {
    }

    public String toJson(ObjectMapper objectMapper) {
        try {
            return objectMapper.writeValueAsString(this);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize bulk export request params", e);
        }
    }

    public static BulkExportRequestParams fromJson(ObjectMapper objectMapper, String json) {
        try {
            return objectMapper.readValue(json, BulkExportRequestParams.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Unreadable bulk export request params", e);
        }
    }
}
