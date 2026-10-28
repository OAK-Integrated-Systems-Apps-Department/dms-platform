package com.oak.dms.relationship.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * The Related Documents panel of one document: every active link touching it, grouped by type.
 * Each row reads from this document's side - the type's label when it is the source, the inverse
 * label when it is the target. A document the caller can't open is only
 * {@code other = {accessible: false}}: nothing that identifies it (D5).
 */
public record RelatedDocumentsResponse(UUID documentId, int total, boolean canAdd, List<Group> groups) {

    public record Group(UUID relationshipTypeId, String typeCode, String typeLabel, String typeInverseLabel,
                        boolean typeEnabled, List<Item> items) {
    }

    public record Item(UUID relationshipId, String direction, String label, String origin, String note,
                       OffsetDateTime createdAt, Person createdBy, boolean canEdit, boolean canRemove, Other other) {
    }

    public record Person(UUID userId, String name) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Other(boolean accessible, UUID documentId, String title, String documentTypeName, String status,
                        Boolean inRecycleBin, UUID folderId) {
        public static Other hidden() {
            return new Other(false, null, null, null, null, null, null);
        }
    }
}
