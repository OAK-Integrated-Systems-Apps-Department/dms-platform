package com.oak.dms.workflow.service;

import com.oak.dms.document.Document;
import com.oak.dms.workflow.dto.SupportingDocumentInput;
import com.oak.dms.workflow.entity.WorkflowInstance;
import com.oak.dms.workflow.entity.WorkflowTemplate;

import java.util.List;
import java.util.UUID;

/**
 * Submit-to-workflow and withdrawal (W5). A submission is all-or-nothing:
 * any precondition failure leaves no instance, stage instance, task or
 * action behind (decision 11).
 */
public interface WorkflowSubmissionService {

    /** Submit a document of the caller's tenant as the caller. */
    default UUID submit(UUID documentId) {
        return submit(documentId, List.of());
    }

    /**
     * Submit a document, optionally attaching supporting documents in the same
     * transaction. An invalid attachment fails the whole submission - nothing
     * is created (decision 11). Each attachment follows the normal
     * supporting-document rules (you can see it, it isn't the document being
     * submitted, its version is clean).
     */
    UUID submit(UUID documentId, List<SupportingDocumentInput> supportingDocuments);

    /** Withdraw an in-progress instance - its initiator, or a tenant admin. */
    void cancel(UUID instanceId, String reason);

    /**
     * Start an instance on an already-locked document - shared by submit and
     * by the version-change restart (which runs without a TenantContext).
     */
    WorkflowInstance start(Document lockedDocument, WorkflowTemplate template, UUID initiatorId);

    /** The checks start() makes before writing anything, without writing - throws the same 422s. */
    void precheck(Document document, WorkflowTemplate template, UUID initiatorId);
}
