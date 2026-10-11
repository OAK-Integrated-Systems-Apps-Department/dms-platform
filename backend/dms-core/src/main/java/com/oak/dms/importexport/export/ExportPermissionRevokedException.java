package com.oak.dms.importexport.export;

import java.util.UUID;

/**
 * The requester of an export was looked up successfully and no longer
 * holds what exporting needs (both permission codes, or the TENANT_ADMIN
 * role), or is no longer an active user. Definitive - the export fails.
 * A lookup that itself failed is never reported this way; see
 * {@link BulkExportAccessPolicy#requireUserMayExport}.
 */
public class ExportPermissionRevokedException extends RuntimeException {

    public ExportPermissionRevokedException(UUID userId, String reason) {
        super("User " + userId + " may no longer export documents: " + reason);
    }
}
