package com.oak.dms.comment.helper;

import com.oak.dms.comment.dto.CommentMentionResponse;
import com.oak.dms.comment.dto.CommentResponse;
import com.oak.dms.comment.entity.CommentMention;
import com.oak.dms.comment.entity.DocumentComment;
import com.oak.dms.comment.repository.CommentMentionRepository;
import com.oak.dms.user.UserRepository;
import com.oak.dms.workflow.dto.UserRef;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Builds comment responses with author and mention names loaded in two batched queries. */
@Component
@RequiredArgsConstructor
public class CommentMapper {

    public static final String DELETED_BODY = "[deleted]";

    private final CommentMentionRepository mentionRepository;
    private final UserRepository userRepository;

    public record Context(Map<UUID, String> userNames, Map<UUID, List<CommentMention>> mentions) {
    }

    public Context context(UUID tenantId, Collection<DocumentComment> comments) {
        Map<UUID, List<CommentMention>> mentions = comments.isEmpty() ? Map.of()
                : mentionRepository.findByTenantIdAndCommentIdIn(tenantId,
                        comments.stream().map(DocumentComment::getCommentId).toList()).stream()
                .collect(Collectors.groupingBy(CommentMention::getCommentId));
        Set<UUID> users = new HashSet<>();
        comments.forEach(c -> users.add(c.getAuthorUserId()));
        mentions.values().forEach(list -> list.forEach(m -> users.add(m.getMentionedUserId())));
        Map<UUID, String> names = new HashMap<>();
        userRepository.findAllById(users).stream()
                .filter(u -> tenantId.equals(u.getTenantId()))
                .forEach(u -> names.put(u.getUserId(), u.getDisplayName()));
        return new Context(names, mentions);
    }

    public CommentResponse toResponse(DocumentComment c, Context context) {
        List<CommentMentionResponse> mentions = c.isDeleted() ? List.of()
                : context.mentions().getOrDefault(c.getCommentId(), List.of()).stream()
                .map(m -> new CommentMentionResponse(m.getMentionedUserId(),
                        context.userNames().get(m.getMentionedUserId()), m.isHadDocumentAccess()))
                .toList();
        return new CommentResponse(c.getCommentId(), c.getDocumentId(), c.getWorkflowInstanceId(),
                c.getParentCommentId(), c.isDeleted() ? DELETED_BODY : c.getBody(),
                new UserRef(c.getAuthorUserId(), context.userNames().get(c.getAuthorUserId())), c.getCreatedAt(),
                c.getEditedAt(), c.isDeleted(), mentions);
    }
}
