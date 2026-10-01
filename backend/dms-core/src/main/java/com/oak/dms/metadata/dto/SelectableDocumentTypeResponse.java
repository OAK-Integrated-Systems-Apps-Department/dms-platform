package com.oak.dms.metadata.dto;

import java.util.UUID;

/**
 * The minimum a document uploader needs to pick a type: an active type's id,
 * code and name - nothing about its schema, retention or settings. Served by
 * GET /api/v1/document-types/selectable to anyone who may upload documents,
 * which the full admin list (MDTM_* permissions) deliberately is not.
 */
public record SelectableDocumentTypeResponse(
        UUID documentTypeId,
        String typeCode,
        String typeName
) {
}
