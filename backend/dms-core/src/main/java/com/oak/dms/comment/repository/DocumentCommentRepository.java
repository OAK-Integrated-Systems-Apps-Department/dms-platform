package com.oak.dms.comment.repository;

import com.oak.dms.comment.entity.DocumentComment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DocumentCommentRepository extends JpaRepository<DocumentComment, UUID> {

    Optional<DocumentComment> findByCommentIdAndTenantId(UUID commentId, UUID tenantId);

    Optional<DocumentComment> findByCommentIdAndTenantIdAndDeletedFalse(UUID commentId, UUID tenantId);

    /**
     * Root comments of a document, oldest first, optionally only those about
     * one workflow instance. A deleted root stays while it still has a live
     * reply, so the thread keeps its place.
     */
    @Query(value = """
            select c from DocumentComment c
            where c.tenantId = :tenantId and c.documentId = :documentId and c.parentCommentId is null
              and (:instanceId is null or c.workflowInstanceId = :instanceId)
              and (c.deleted = false or exists (select r.commentId from DocumentComment r
                                                where r.parentCommentId = c.commentId and r.deleted = false))
            order by c.createdAt asc
            """,
            countQuery = """
            select count(c) from DocumentComment c
            where c.tenantId = :tenantId and c.documentId = :documentId and c.parentCommentId is null
              and (:instanceId is null or c.workflowInstanceId = :instanceId)
              and (c.deleted = false or exists (select r.commentId from DocumentComment r
                                                where r.parentCommentId = c.commentId and r.deleted = false))
            """)
    Page<DocumentComment> findRoots(@Param("tenantId") UUID tenantId, @Param("documentId") UUID documentId,
                                    @Param("instanceId") UUID instanceId, Pageable pageable);

    List<DocumentComment> findByTenantIdAndParentCommentIdInAndDeletedFalseOrderByCreatedAtAsc(UUID tenantId,
                                                                                              Collection<UUID> parentIds);
}
