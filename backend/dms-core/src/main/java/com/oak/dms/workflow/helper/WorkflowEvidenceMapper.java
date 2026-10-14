package com.oak.dms.workflow.helper;

import com.oak.dms.document.Document;
import com.oak.dms.document.DocumentRepository;
import com.oak.dms.document.DocumentVersion;
import com.oak.dms.document.DocumentVersionRepository;
import com.oak.dms.folder.DocumentAccessService;
import com.oak.dms.folder.DocumentAccessService.DocumentAccessView;
import com.oak.dms.security.auth.AuthorizationService;
import com.oak.dms.security.rbac.catalog.PermissionCodes;
import com.oak.dms.user.UserRepository;
import com.oak.dms.workflow.dto.InfoRequestView;
import com.oak.dms.workflow.dto.SupportingDocumentView;
import com.oak.dms.workflow.dto.UserRef;
import com.oak.dms.workflow.entity.WorkflowInfoRequest;
import com.oak.dms.workflow.entity.WorkflowInstance;
import com.oak.dms.workflow.entity.WorkflowSupportingDocument;
import com.oak.dms.workflow.repository.WorkflowInstanceRepository;
import com.oak.dms.workflow.repository.WorkflowStageInstanceRepository;
import com.oak.dms.workflow.repository.WorkflowSupportingDocumentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Response views of information requests and supporting documents, built
 * with batched lookups. Shared by the request, supporting-document and
 * status services (which would otherwise depend on each other).
 * canOpen is the current viewer's docm.documents.view (decision 18) and
 * their own access to that one supporting document (V53/V54).
 */
@Component
@RequiredArgsConstructor
public class WorkflowEvidenceMapper {

    private final WorkflowSupportingDocumentRepository supportingRepository;
    private final WorkflowStageInstanceRepository stageInstanceRepository;
    private final WorkflowInstanceRepository instanceRepository;
    private final DocumentRepository documentRepository;
    private final DocumentVersionRepository versionRepository;
    private final UserRepository userRepository;
    private final DocumentAccessService documentAccessService;
    private final AuthorizationService authorizationService;

    public List<SupportingDocumentView> supportingViews(UUID tenantId, List<WorkflowSupportingDocument> documents) {
        if (documents.isEmpty()) {
            return List.of();
        }
        boolean hasPermission = authorizationService.hasPermission(PermissionCodes.DOCM_DOCUMENTS_VIEW);
        DocumentAccessView access = hasPermission ? documentAccessService.forCaller() : null;
        Map<UUID, String> titles = new HashMap<>();
        Set<UUID> openable = new HashSet<>();
        for (Document d : documentRepository.findAllById(
                documents.stream().map(WorkflowSupportingDocument::getDocumentId).toList())) {
            titles.put(d.getDocumentId(), d.getTitle());
            if (access != null && tenantId.equals(d.getTenantId()) && !d.isDeleted() && access.canSee(d)) {
                openable.add(d.getDocumentId());
            }
        }
        Map<UUID, DocumentVersion> versions = new HashMap<>();
        versionRepository.findAllById(documents.stream().map(WorkflowSupportingDocument::getDocumentVersionId).toList())
                .forEach(v -> versions.put(v.getVersionId(), v));
        Map<UUID, String> names = names(tenantId, documents.stream().map(WorkflowSupportingDocument::getAddedBy).toList());
        return documents.stream().map(d -> {
            boolean canOpen = openable.contains(d.getDocumentId());
            DocumentVersion version = versions.get(d.getDocumentVersionId());
            // The file fields feed the shared viewer and are given out only when the viewer may open the
            // document (decision 18) - a locked supporting document shows title and note alone.
            return new SupportingDocumentView(d.getSupportingDocumentId(), d.getInstanceId(), d.getDocumentId(),
                    titles.get(d.getDocumentId()), d.getDocumentVersionId(),
                    version != null ? version.getVersionNumber() : null, d.getSource().name(), d.getNote(),
                    new UserRef(d.getAddedBy(), names.get(d.getAddedBy())), d.getAddedAt(), d.getInfoRequestId(), canOpen,
                    canOpen && version != null ? version.getOriginalFilename() : null,
                    canOpen && version != null ? version.getMimeType() : null,
                    canOpen && version != null ? version.getSizeBytes() : null);
        }).toList();
    }

    /** Each request with the supporting documents sent in answer to it. */
    public List<InfoRequestView> requestViews(UUID tenantId, List<WorkflowInfoRequest> requests) {
        if (requests.isEmpty()) {
            return List.of();
        }
        Set<UUID> people = new HashSet<>();
        for (WorkflowInfoRequest r : requests) {
            people.add(r.getRequestedBy());
            people.add(r.getRequestedFrom());
            people.add(r.getRespondedBy());
        }
        Map<UUID, String> names = names(tenantId, people);
        Map<UUID, Integer> stageOrders = new HashMap<>();
        stageInstanceRepository.findAllById(requests.stream().map(WorkflowInfoRequest::getStageInstanceId).toList())
                .forEach(si -> stageOrders.put(si.getStageInstanceId(), si.getStageOrder()));
        Map<UUID, WorkflowInstance> instances = new HashMap<>();
        instanceRepository.findAllById(requests.stream().map(WorkflowInfoRequest::getInstanceId).toList())
                .forEach(i -> instances.put(i.getInstanceId(), i));
        Map<UUID, String> titles = new HashMap<>();
        documentRepository.findAllById(instances.values().stream().map(WorkflowInstance::getDocumentId).toList())
                .forEach(d -> titles.put(d.getDocumentId(), d.getTitle()));
        Map<UUID, List<SupportingDocumentView>> byRequest = supportingViews(tenantId,
                supportingRepository.findByInfoRequestIdInAndTenantIdAndDeletedFalse(
                        requests.stream().map(WorkflowInfoRequest::getInfoRequestId).toList(), tenantId)).stream()
                .collect(Collectors.groupingBy(SupportingDocumentView::infoRequestId));
        List<InfoRequestView> result = new ArrayList<>();
        for (WorkflowInfoRequest r : requests) {
            WorkflowInstance i = instances.get(r.getInstanceId());
            UUID documentId = i == null ? null : i.getDocumentId();
            result.add(new InfoRequestView(r.getInfoRequestId(), r.getInstanceId(), documentId, titles.get(documentId),
                    r.getTaskId(), r.getStageInstanceId(), stageOrders.get(r.getStageInstanceId()),
                    new UserRef(r.getRequestedBy(), names.get(r.getRequestedBy())),
                    new UserRef(r.getRequestedFrom(), names.get(r.getRequestedFrom())), r.getMessage(),
                    r.getStatus().name(), r.getResponseMessage(), r.getDeclineReason(), r.getCancelReason(),
                    r.getDueAt(), r.getRequestedAt(), r.getRespondedAt(),
                    r.getRespondedBy() == null ? null : new UserRef(r.getRespondedBy(), names.get(r.getRespondedBy())),
                    byRequest.getOrDefault(r.getInfoRequestId(), List.of())));
        }
        return result;
    }

    private Map<UUID, String> names(UUID tenantId, Collection<UUID> ids) {
        Map<UUID, String> names = new HashMap<>();
        Set<UUID> wanted = ids.stream().filter(Objects::nonNull).collect(Collectors.toSet());
        userRepository.findAllById(wanted).stream().filter(u -> tenantId.equals(u.getTenantId()))
                .forEach(u -> names.put(u.getUserId(), u.getDisplayName()));
        return names;
    }
}
