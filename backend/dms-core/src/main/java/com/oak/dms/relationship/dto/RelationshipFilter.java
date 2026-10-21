package com.oak.dms.relationship.dto;

import java.util.UUID;

/**
 * The browser's relationship filter, resolved and checked: documents with an active link of
 * {@code typeId} (null = any type) to or from {@code relatedTo} (null = any document). {@code asSource}
 * keeps documents that are the source of such a link, {@code asTarget} those that are its target.
 */
public record RelationshipFilter(UUID typeId, UUID relatedTo, boolean asSource, boolean asTarget) {
}
