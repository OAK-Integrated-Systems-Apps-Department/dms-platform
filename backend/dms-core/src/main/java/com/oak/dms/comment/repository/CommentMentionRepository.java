package com.oak.dms.comment.repository;

import com.oak.dms.comment.entity.CommentMention;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface CommentMentionRepository extends JpaRepository<CommentMention, UUID> {

    List<CommentMention> findByTenantIdAndCommentIdIn(UUID tenantId, Collection<UUID> commentIds);

    boolean existsByTenantIdAndCommentIdAndMentionedUserId(UUID tenantId, UUID commentId, UUID mentionedUserId);

    /** The user's mentions on comments that still exist, newest first. */
    @Query(value = """
            select m from CommentMention m, DocumentComment c
            where m.commentId = c.commentId and m.tenantId = :tenantId and m.mentionedUserId = :userId
              and c.deleted = false
            order by m.createdAt desc
            """,
            countQuery = """
            select count(m) from CommentMention m, DocumentComment c
            where m.commentId = c.commentId and m.tenantId = :tenantId and m.mentionedUserId = :userId
              and c.deleted = false
            """)
    Page<CommentMention> findMine(@Param("tenantId") UUID tenantId, @Param("userId") UUID userId, Pageable pageable);

    /** Set after the mention mail went out (it runs after commit, outside the request transaction). */
    @org.springframework.transaction.annotation.Transactional
    @org.springframework.data.jpa.repository.Modifying
    @Query("update CommentMention m set m.notifiedAt = :at where m.mentionId = :id")
    int markNotified(@Param("id") UUID mentionId, @Param("at") java.time.OffsetDateTime at);
}
