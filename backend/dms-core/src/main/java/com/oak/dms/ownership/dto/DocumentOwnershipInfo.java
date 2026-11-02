package com.oak.dms.ownership.dto;

import java.util.UUID;

/** Who owns a document now, for the Owner field on the document page. {@code canTransfer}: the caller may move it (permission and scope). */
public record DocumentOwnershipInfo(UUID documentId, PersonRef owner, DepartmentRef ownerDepartment, String ownershipState,
                                    boolean canTransfer) {
}
