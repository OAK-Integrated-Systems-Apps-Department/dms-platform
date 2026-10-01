package com.oak.dms.importexport.dto;

import java.util.UUID;

/**
 * A document type the caller can import into - just enough for the Bulk
 * Import screen's picker, so the endpoint serving it can be gated on the
 * import permission rather than the metadata-management ones.
 */
public record ImportDocumentTypeOption(
        UUID documentTypeId,
        String name,
        String typeCode
) {
}
