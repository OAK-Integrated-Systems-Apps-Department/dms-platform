package com.oak.dms.comment.service;

import com.oak.dms.comment.dto.MentionResponse;
import com.oak.dms.comment.entity.DocumentComment;
import com.oak.dms.document.Document;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/** @mentions in comments (Phase 2 S6, decision 10, A5). */
public interface MentionService {

    /**
     * Parses the comment's mention tokens and stores the ones not stored yet
     * - on an edit only newly added people are notified; removed tokens keep
     * their rows as history. Returns how many new mentions were recorded.
     * 422 COMMENT_INVALID_MENTION for an id that isn't an active user of the
     * tenant, or more than 20 people.
     */
    int recordMentions(DocumentComment comment, Document document);

    /** Comments the caller was mentioned in, newest first. */
    Page<MentionResponse> myMentions(Pageable pageable);
}
