package com.oak.dms.workflow.service;

import com.oak.dms.workflow.dto.InfoRequestView;
import com.oak.dms.workflow.dto.RespondInfoRequest;
import com.oak.dms.workflow.enums.InfoRequestStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Information requests (Phase 2 S7, decisions 11-16): the holder of a task
 * pauses it to ask someone for a document or an answer; other approvers
 * carry on. While any request on the task is open the task is
 * awaiting_info, can't be decided and its SLA clock is paused; the last one
 * closing resumes it with due_at moved forward by the paused time.
 */
public interface WorkflowInfoRequestService {

    InfoRequestView raise(UUID taskId, UUID requestedFromUserId, String message, OffsetDateTime dueAt);

    /** The person asked, with a note and/or supporting documents. */
    InfoRequestView respond(UUID infoRequestId, RespondInfoRequest response);

    /** The person asked, with a mandatory reason. */
    InfoRequestView decline(UUID infoRequestId, String reason);

    /** The requester takes it back. */
    InfoRequestView withdraw(UUID infoRequestId, String reason);

    /** The initiator or a tenant admin points it at someone else; the task stays paused. */
    InfoRequestView reassign(UUID infoRequestId, UUID requestedFromUserId, String reason);

    List<InfoRequestView> listForInstance(UUID instanceId);

    /** "Requests for me". */
    Page<InfoRequestView> addressedToMe(InfoRequestStatus status, Pageable pageable);
}
