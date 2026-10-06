package com.oak.dms.comment.controller;

import com.oak.dms.comment.dto.CommentResponse;
import com.oak.dms.comment.dto.CommentThreadResponse;
import com.oak.dms.comment.dto.CreateCommentRequest;
import com.oak.dms.comment.dto.MentionResponse;
import com.oak.dms.comment.dto.UpdateCommentRequest;
import com.oak.dms.comment.service.DocumentCommentService;
import com.oak.dms.comment.service.MentionService;
import com.oak.dms.common.dto.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Threaded document comments and @mentions (3.1 Phase 2, decisions 9-10).
 * Reading needs docm.documents.view; writing also needs
 * docm.document-comments.create. A mentioned user without view access still
 * reads the comments they were mentioned in, through /comments/{id} and
 * /mentions/my.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "Document comments")
public class DocumentCommentController {

    private static final String VIEW = "@authz.hasPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).DOCM_DOCUMENTS_VIEW)";
    private static final String WRITE = VIEW
            + " and @authz.hasPermission(T(com.oak.dms.security.rbac.catalog.PermissionCodes).DOCM_COMMENTS_CREATE)";
    private static final int MAX_PAGE_SIZE = 100;

    private final DocumentCommentService commentService;
    private final MentionService mentionService;

    @GetMapping("/documents/{documentId}/comments")
    @PreAuthorize(VIEW)
    @Operation(summary = "Comment threads of a document, oldest first",
            description = "workflowInstanceId narrows to the discussion of one workflow run.")
    public PageResponse<CommentThreadResponse> list(@PathVariable("documentId") UUID documentId,
                                                    @RequestParam(required = false) UUID workflowInstanceId,
                                                    @RequestParam(defaultValue = "0") int page,
                                                    @RequestParam(defaultValue = "20") int size) {
        return PageResponse.from(commentService.list(documentId, workflowInstanceId,
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE))));
    }

    @PostMapping("/documents/{documentId}/comments")
    @PreAuthorize(WRITE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Comment, or reply (parentCommentId)",
            description = "Mention people with @[Name](user:<id>) tokens; 422 COMMENT_INVALID_MENTION for an unknown id.")
    public CommentResponse create(@PathVariable("documentId") UUID documentId,
                                  @Valid @RequestBody CreateCommentRequest request) {
        return commentService.create(documentId, request.body(), request.parentCommentId(),
                request.workflowInstanceId());
    }

    @PutMapping("/comments/{commentId}")
    @PreAuthorize(WRITE)
    @Operation(summary = "Edit my comment", description = "Newly added mentions are notified.")
    public CommentResponse update(@PathVariable("commentId") UUID commentId,
                                  @Valid @RequestBody UpdateCommentRequest request) {
        return commentService.update(commentId, request.body());
    }

    @DeleteMapping("/comments/{commentId}")
    @PreAuthorize(VIEW)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a comment (author or tenant admin); replies stay")
    public void delete(@PathVariable("commentId") UUID commentId) {
        commentService.delete(commentId);
    }

    @GetMapping("/comments/{commentId}")
    @Operation(summary = "One comment with its parent",
            description = "Readable with docm.documents.view, or by someone mentioned in it.")
    public CommentThreadResponse get(@PathVariable("commentId") UUID commentId) {
        return commentService.get(commentId);
    }

    @GetMapping("/mentions/my")
    @Operation(summary = "Comments I was mentioned in, newest first")
    public PageResponse<MentionResponse> myMentions(@RequestParam(defaultValue = "0") int page,
                                                    @RequestParam(defaultValue = "20") int size) {
        return PageResponse.from(mentionService.myMentions(
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE))));
    }
}
