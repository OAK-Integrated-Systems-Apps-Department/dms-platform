package com.oak.dms.comment.dto;

import java.util.UUID;

/** hadDocumentAccess: whether they could open the document when mentioned (decision 10). */
public record CommentMentionResponse(UUID userId, String displayName, boolean hadDocumentAccess) {
}
