package com.oak.dms.comment.service;

import com.oak.dms.comment.dto.CommentResponse;
import com.oak.dms.comment.dto.CommentThreadResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

/**
 * Threaded comments on a document, optionally about one of its workflow
 * instances (Phase 2 S5, decision 9). Reading and writing require
 * docm.documents.view (checked at the controller); every lookup is
 * tenant-scoped and a foreign id is a 404.
 */
public interface DocumentCommentService {

    Page<CommentThreadResponse> list(UUID documentId, UUID workflowInstanceId, Pageable pageable);

    CommentResponse create(UUID documentId, String body, UUID parentCommentId, UUID workflowInstanceId);

    /** Author only. */
    CommentResponse update(UUID commentId, String body);

    /** The author, or a tenant admin. Replies stay; the comment reads "[deleted]". */
    void delete(UUID commentId);

    /**
     * One comment and its parent (for context). Readable with
     * docm.documents.view - or, without it, by a user mentioned in it
     * (decision 10).
     */
    CommentThreadResponse get(UUID commentId);
}
