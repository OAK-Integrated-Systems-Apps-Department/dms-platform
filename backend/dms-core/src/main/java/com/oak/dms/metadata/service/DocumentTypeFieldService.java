package com.oak.dms.metadata.service;

import com.oak.dms.metadata.dto.DocumentTypeFieldResponse;
import com.oak.dms.metadata.dto.DocumentTypeSchemaResponse;
import com.oak.dms.metadata.dto.ReplaceDocumentTypeFieldsRequest;

import java.util.List;
import java.util.UUID;

/**
 * The versioned document-type <-> field mapping (A6) - MDTM-FR-005.
 */
public interface DocumentTypeFieldService {

    /** The active mapping at the document type's current schema version. */
    List<DocumentTypeFieldResponse> getCurrentFields(UUID documentTypeId);

    /** The mapping as it was at a specific (possibly archived) version. */
    List<DocumentTypeFieldResponse> getFieldsAtVersion(UUID documentTypeId, int version);

    /**
     * Replaces the whole mapping set, publishing a new schema version:
     * copies the prior version forward with the given edits applied at
     * {@code version + 1}, archives the prior version's rows, and bumps
     * {@code document_types.current_schema_version}.
     */
    List<DocumentTypeFieldResponse> replaceMapping(UUID documentTypeId, ReplaceDocumentTypeFieldsRequest request);

    /** The fully resolved form schema at the current version (MDTM-FR-006). */
    DocumentTypeSchemaResponse getSchema(UUID documentTypeId);
}
