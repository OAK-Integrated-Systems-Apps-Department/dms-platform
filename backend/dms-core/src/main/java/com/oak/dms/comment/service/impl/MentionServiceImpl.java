package com.oak.dms.comment.service.impl;

import com.oak.dms.comment.dto.MentionResponse;
import com.oak.dms.comment.entity.CommentMention;
import com.oak.dms.comment.entity.DocumentComment;
import com.oak.dms.comment.event.CommentMentionedEvent;
import com.oak.dms.comment.exception.CommentValidationException;
import com.oak.dms.comment.helper.CommentMapper;
import com.oak.dms.comment.helper.MentionParser;
import com.oak.dms.comment.repository.CommentMentionRepository;
import com.oak.dms.comment.repository.DocumentCommentRepository;
import com.oak.dms.comment.service.MentionService;
import com.oak.dms.document.Document;
import com.oak.dms.document.DocumentRepository;
import com.oak.dms.folder.DocumentAccessService;
import com.oak.dms.folder.DocumentAccessService.DocumentAccessView;
import com.oak.dms.security.auth.AuthorizationService;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.security.auth.UserPermissions;
import com.oak.dms.security.rbac.catalog.PermissionCodes;
import com.oak.dms.user.User;
import com.oak.dms.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class MentionServiceImpl implements MentionService {

    static final int MAX_MENTIONS = 20;

    private final CommentMentionRepository mentionRepository;
    private final DocumentCommentRepository commentRepository;
    private final DocumentRepository documentRepository;
    private final UserRepository userRepository;
    private final UserPermissions userPermissions;
    private final DocumentAccessService documentAccessService;
    private final AuthorizationService authorizationService;
    private final CommentMapper mapper;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public int recordMentions(DocumentComment comment, Document document) {
        UUID tenantId = comment.getTenantId();
        Set<UUID> ids = MentionParser.mentionedUserIds(comment.getBody());
        ids.remove(comment.getAuthorUserId());
        if (ids.size() > MAX_MENTIONS) {
            throw new CommentValidationException("COMMENT_INVALID_MENTION",
                    "A comment can mention at most " + MAX_MENTIONS + " people");
        }
        List<User> mentioned = new ArrayList<>();
        for (UUID id : ids) {
            User user = userRepository.findByUserIdAndTenantIdAndDeletedFalse(id, tenantId)
                    .filter(u -> u.getStatus() == User.UserStatus.active)
                    .orElseThrow(() -> new CommentValidationException("COMMENT_INVALID_MENTION",
                            "A mentioned person doesn't exist or is not active"));
            if (!mentionRepository.existsByTenantIdAndCommentIdAndMentionedUserId(tenantId, comment.getCommentId(),
                    user.getUserId())) {
                mentioned.add(user);
            }
        }
        // Decision 10: they are notified either way; this records whether they could actually open
        // this document - the permission AND folder/document access (V53/V54), worked out for them.
        Map<UUID, DocumentAccessView> access = documentAccessService.forUsers(tenantId,
                mentioned.stream().map(User::getUserId).toList());
        List<CommentMention> created = new ArrayList<>();
        for (User user : mentioned) {
            boolean canOpen = userPermissions.holds(tenantId, user.getUserId(), PermissionCodes.DOCM_DOCUMENTS_VIEW)
                    && access.get(user.getUserId()).canSee(document);
            created.add(CommentMention.builder()
                    .tenantId(tenantId)
                    .commentId(comment.getCommentId())
                    .mentionedUserId(user.getUserId())
                    .hadDocumentAccess(canOpen)
                    .build());
        }
        if (created.isEmpty()) {
            return 0;
        }
        created = mentionRepository.saveAllAndFlush(created);
        eventPublisher.publishEvent(new CommentMentionedEvent(tenantId, comment.getCommentId(),
                created.stream().map(CommentMention::getMentionId).toList()));
        return created.size();
    }

    @Override
    @Transactional(readOnly = true)
    public Page<MentionResponse> myMentions(Pageable pageable) {
        UUID tenantId = TenantContext.getTenantId();
        UUID userId = TenantContext.getUserId();
        Page<CommentMention> page = mentionRepository.findMine(tenantId, userId, pageable);
        Map<UUID, DocumentComment> comments = new HashMap<>();
        commentRepository.findAllById(page.map(CommentMention::getCommentId).toSet())
                .forEach(c -> comments.put(c.getCommentId(), c));
        Map<UUID, Document> documents = new HashMap<>();
        documentRepository.findAllById(comments.values().stream().map(DocumentComment::getDocumentId).toList())
                .forEach(d -> documents.put(d.getDocumentId(), d));
        CommentMapper.Context context = mapper.context(tenantId, comments.values());
        // canOpen is per document: the permission plus the caller's own access to that one document.
        boolean hasPermission = authorizationService.hasPermission(PermissionCodes.DOCM_DOCUMENTS_VIEW);
        DocumentAccessView access = hasPermission ? documentAccessService.forCaller() : null;
        return page.map(m -> {
            DocumentComment c = comments.get(m.getCommentId());
            Document d = documents.get(c.getDocumentId());
            boolean canOpen = access != null && d != null && !d.isDeleted() && access.canSee(d);
            return new MentionResponse(m.getMentionId(), mapper.toResponse(c, context), c.getDocumentId(),
                    d == null ? null : d.getTitle(), canOpen, m.getCreatedAt());
        });
    }
}
