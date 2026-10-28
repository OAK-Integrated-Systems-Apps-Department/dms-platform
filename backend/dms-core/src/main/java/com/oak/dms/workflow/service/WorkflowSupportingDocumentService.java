package com.oak.dms.workflow.service;

import com.oak.dms.document.dto.DownloadedDocument;
import com.oak.dms.workflow.dto.SupportingDocumentInput;
import com.oak.dms.workflow.dto.SupportingDocumentView;
import com.oak.dms.workflow.entity.WorkflowInstance;
import com.oak.dms.workflow.entity.WorkflowSupportingDocument;

import java.util.List;
import java.util.UUID;

/**
 * Evidence attached to a workflow run (Phase 2 S8, decisions 17-18): linked
 * or newly uploaded documents, each pinned to one version. Never routed for
 * approval, never part of the version-change check, and never a way around
 * docm.documents.view - the sender must hold it, and a viewer without it
 * sees only the title and note.
 */
public interface WorkflowSupportingDocumentService {

    /** A participant attaches a document outside any request. */
    SupportingDocumentView add(UUID instanceId, SupportingDocumentInput input);

    List<SupportingDocumentView> list(UUID instanceId);

    /**
     * Streams the evidence's <em>pinned</em> version (decision 17), not the
     * document's current one. Allowed for a viewer who can see the run and can
     * open the document (docm.documents.view plus their own access); needs no
     * dvc.versions.view.
     */
    DownloadedDocument content(UUID instanceId, UUID supportingDocumentId);

    /** The person who added it, while the workflow is in progress. */
    void remove(UUID instanceId, UUID supportingDocumentId);

    /** Attached while answering a request, under the caller's locks (instance already locked). */
    List<WorkflowSupportingDocument> attach(WorkflowInstance instance, UUID infoRequestId,
                                            List<SupportingDocumentInput> inputs, UUID actorUserId);

    /** Views for display, with canOpen for the current viewer. */
    List<SupportingDocumentView> views(UUID tenantId, List<WorkflowSupportingDocument> documents);
}
