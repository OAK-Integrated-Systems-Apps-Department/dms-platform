package com.oak.dms.comment.service.impl;

import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.catalog.AuditObjectTypes;
import com.oak.dms.audit.enums.AuditOutcome;
import com.oak.dms.audit.service.AuditService;
import com.oak.dms.comment.dto.CommentResponse;
import com.oak.dms.comment.dto.CommentThreadResponse;
import com.oak.dms.comment.entity.DocumentComment;
import com.oak.dms.comment.event.CommentCreatedEvent;
import com.oak.dms.comment.exception.CommentAccessException;
import com.oak.dms.comment.exception.CommentValidationException;
import com.oak.dms.comment.helper.CommentMapper;
import com.oak.dms.comment.repository.CommentMentionRepository;
import com.oak.dms.comment.repository.DocumentCommentRepository;
import com.oak.dms.comment.service.DocumentCommentService;
import com.oak.dms.comment.service.MentionService;
import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.document.Document;
import com.oak.dms.document.DocumentRepository;
import com.oak.dms.security.auth.AuthorizationService;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.security.rbac.catalog.PermissionCodes;
import com.oak.dms.workflow.entity.WorkflowInstance;
import com.oak.dms.workflow.enums.WorkflowActionType;
import com.oak.dms.workflow.helper.WorkflowActionRecorder;
import com.oak.dms.workflow.repository.WorkflowInstanceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DocumentCommentServiceImpl implements DocumentCommentService {

    private final DocumentCommentRepository commentRepository;
    private final CommentMentionRepository mentionRepository;
    private final DocumentRepository documentRepository;
    private final WorkflowInstanceRepository instanceRepository;
    private final MentionService mentionService;
    private final CommentMapper mapper;
    private final WorkflowActionRecorder actions;
    private final AuthorizationService authorizationService;
    private final AuditService auditService;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    @Transactional(readOnly = true)
    public Page<CommentThreadResponse> list(UUID documentId, UUID workflowInstanceId, Pageable pageable) {
        UUID tenantId = TenantContext.getTenantId();
        findDocument(documentId, tenantId);
        Page<DocumentComment> roots = commentRepository.findRoots(tenantId, documentId, workflowInstanceId, pageable);
        List<DocumentComment> replies = roots.isEmpty() ? List.of()
                : commentRepository.findByTenantIdAndParentCommentIdInAndDeletedFalseOrderByCreatedAtAsc(tenantId,
                roots.map(DocumentComment::getCommentId).toList());
        List<DocumentComment> all = new ArrayList<>(roots.getContent());
        all.addAll(replies);
        CommentMapper.Context context = mapper.context(tenantId, all);
        return roots.map(root -> new CommentThreadResponse(mapper.toResponse(root, context),
                replies.stream().filter(r -> root.getCommentId().equals(r.getParentCommentId()))
                        .map(r -> mapper.toResponse(r, context)).toList()));
    }

    @Override
    @Transactional
    public CommentResponse create(UUID documentId, String body, UUID parentCommentId, UUID workflowInstanceId) {
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();
        Document document = findDocument(documentId, tenantId);

        UUID rootId = null;
        UUID instanceId = workflowInstanceId;
        if (parentCommentId != null) {
            DocumentComment parent = commentRepository.findByCommentIdAndTenantIdAndDeletedFalse(parentCommentId, tenantId)
                    .orElseThrow(() -> new CommentValidationException("COMMENT_INVALID_PARENT",
                            "The comment you are replying to no longer exists"));
            if (!parent.getDocumentId().equals(documentId)) {
                throw new CommentValidationException("COMMENT_INVALID_PARENT",
                        "The comment you are replying to belongs to another document");
            }
            // Two levels: a reply to a reply joins the root's thread, in its scope.
            rootId = parent.getParentCommentId() != null ? parent.getParentCommentId() : parent.getCommentId();
            if (instanceId != null && !instanceId.equals(parent.getWorkflowInstanceId())) {
                throw new CommentValidationException("COMMENT_INSTANCE_MISMATCH",
                        "A reply belongs to the same workflow as the comment it answers");
            }
            instanceId = parent.getWorkflowInstanceId();
        }
        WorkflowInstance instance = instanceId == null ? null : findInstance(instanceId, documentId, tenantId);

        OffsetDateTime now = OffsetDateTime.now();
        DocumentComment comment = commentRepository.saveAndFlush(DocumentComment.builder()
                .tenantId(tenantId)
                .documentId(documentId)
                .workflowInstanceId(instanceId)
                .parentCommentId(rootId)
                .body(body.trim())
                .authorUserId(userId)
                .createdBy(userId)
                .createdAt(now)
                .build());
        int mentioned = mentionService.recordMentions(comment, document);

        if (instance != null) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("commentId", comment.getCommentId().toString());
            detail.put("reply", rootId != null);
            actions.record(instance, null, null, WorkflowActionType.commented, userId, null, detail);
        }
        audit(AuditActionCatalog.DOCUMENT_COMMENT_CREATED, comment, document, mentioned);
        eventPublisher.publishEvent(new CommentCreatedEvent(tenantId, comment.getCommentId()));
        return mapper.toResponse(comment, mapper.context(tenantId, List.of(comment)));
    }

    @Override
    @Transactional
    public CommentResponse update(UUID commentId, String body) {
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();
        DocumentComment comment = commentRepository.findByCommentIdAndTenantIdAndDeletedFalse(commentId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("DocumentComment", commentId));
        if (!userId.equals(comment.getAuthorUserId())) {
            throw new CommentAccessException("COMMENT_NOT_AUTHOR", "Only the author can edit a comment");
        }
        Document document = findDocument(comment.getDocumentId(), tenantId);
        OffsetDateTime now = OffsetDateTime.now();
        comment.setBody(body.trim());
        comment.setEditedAt(now);
        comment.setUpdatedBy(userId);
        comment.setUpdatedAt(now);
        commentRepository.saveAndFlush(comment);
        int mentioned = mentionService.recordMentions(comment, document);
        audit(AuditActionCatalog.DOCUMENT_COMMENT_UPDATED, comment, document, mentioned);
        return mapper.toResponse(comment, mapper.context(tenantId, List.of(comment)));
    }

    @Override
    @Transactional
    public void delete(UUID commentId) {
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();
        DocumentComment comment = commentRepository.findByCommentIdAndTenantIdAndDeletedFalse(commentId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("DocumentComment", commentId));
        if (!userId.equals(comment.getAuthorUserId()) && !authorizationService.isTenantAdmin()) {
            throw new CommentAccessException("COMMENT_NOT_AUTHOR", "Only the author or an administrator can delete a comment");
        }
        Document document = findDocument(comment.getDocumentId(), tenantId);
        OffsetDateTime now = OffsetDateTime.now();
        comment.setDeleted(true);
        comment.setDeletedAt(now);
        comment.setUpdatedBy(userId);
        comment.setUpdatedAt(now);
        commentRepository.save(comment);
        audit(AuditActionCatalog.DOCUMENT_COMMENT_DELETED, comment, document, 0);
    }

    @Override
    @Transactional(readOnly = true)
    public CommentThreadResponse get(UUID commentId) {
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();
        DocumentComment comment = commentRepository.findByCommentIdAndTenantId(commentId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("DocumentComment", commentId));
        boolean canView = authorizationService.hasPermission(PermissionCodes.DOCM_DOCUMENTS_VIEW);
        if (!canView && !mentionRepository.existsByTenantIdAndCommentIdAndMentionedUserId(tenantId, commentId, userId)) {
            throw new AccessDeniedException("Not allowed to read comment " + commentId);
        }
        findDocument(comment.getDocumentId(), tenantId);
        DocumentComment parent = comment.getParentCommentId() == null ? null
                : commentRepository.findByCommentIdAndTenantId(comment.getParentCommentId(), tenantId).orElse(null);
        List<DocumentComment> all = new ArrayList<>(List.of(comment));
        if (parent != null) {
            all.add(parent);
        }
        CommentMapper.Context context = mapper.context(tenantId, all);
        return parent == null
                ? new CommentThreadResponse(mapper.toResponse(comment, context), List.of())
                : new CommentThreadResponse(mapper.toResponse(parent, context), List.of(mapper.toResponse(comment, context)));
    }

    // ---------------------------------------------------------------- helpers

    private Document findDocument(UUID documentId, UUID tenantId) {
        return documentRepository.findByDocumentIdAndTenantIdAndDeletedFalse(documentId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Document", documentId));
    }

    private WorkflowInstance findInstance(UUID instanceId, UUID documentId, UUID tenantId) {
        WorkflowInstance instance = instanceRepository.findByInstanceIdAndTenantIdAndDeletedFalse(instanceId, tenantId)
                .orElseThrow(() -> new CommentValidationException("COMMENT_INSTANCE_MISMATCH",
                        "The workflow doesn't exist"));
        if (!instance.getDocumentId().equals(documentId)) {
            throw new CommentValidationException("COMMENT_INSTANCE_MISMATCH",
                    "That workflow belongs to another document");
        }
        return instance;
    }

    /** Ids and length only - never the comment's text. */
    private void audit(String action, DocumentComment comment, Document document, int mentionCount) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("commentId", comment.getCommentId());
        value.put("documentId", comment.getDocumentId());
        value.put("workflowInstanceId", comment.getWorkflowInstanceId());
        value.put("parentCommentId", comment.getParentCommentId());
        value.put("length", comment.getBody().length());
        value.put("mentionCount", mentionCount);
        auditService.event(action)
                .objectType(AuditObjectTypes.DOCUMENT_COMMENT)
                .objectId(comment.getCommentId())
                .resourceName(document.getTitle())
                .outcome(AuditOutcome.SUCCESS)
                .newValue(value)
                .log();
    }
}
