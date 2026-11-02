package com.oak.dms.document;

import java.util.UUID;

/** Optional "owned by" narrowing of the document browser (DOT-NFR-004): a user and/or a department; both null = no filter. */
public record OwnerFilter(UUID ownerId, UUID ownerDepartmentId) {

    public static final OwnerFilter NONE = new OwnerFilter(null, null);
}
