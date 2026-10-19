package com.oak.dms.document;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * What the document module needs to know about approval workflows, without
 * depending on the workflow module: which documents someone may read because
 * they take part in the approval running on them, and whether a document is
 * in an approval right now.
 * <p>
 * Implemented by {@code workflow/helper/WorkflowDocumentApprovals}. Kept as
 * an interface here so {@code document} and {@code folder} never import
 * {@code workflow} - the dependency only ever runs the other way.
 */
public interface DocumentApprovals {

    /**
     * Documents of in-progress workflow instances this user takes part in:
     * the initiator's own submissions, the documents of every open task they
     * currently hold (assignee, pool member, claimer, delegate or escalation
     * target - the {@code TaskRouting} rules) and of every open information
     * request addressed to them. Read access ends by itself when the task
     * moves on or the instance finishes, so the answer is never cached.
     */
    Set<UUID> readableDocumentIds(UUID tenantId, UUID userId);

    /** {@link #readableDocumentIds} for several users at once - one query, for the people pickers. */
    Map<UUID, Set<UUID>> readableDocumentIdsByUser(UUID tenantId, Collection<UUID> userIds);

    /** Whether an approval is running on the document right now. */
    boolean inProgress(UUID tenantId, UUID documentId);
}
