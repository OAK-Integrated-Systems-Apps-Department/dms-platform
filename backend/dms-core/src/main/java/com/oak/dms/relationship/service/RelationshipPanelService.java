package com.oak.dms.relationship.service;

import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.document.Document;
import com.oak.dms.document.DocumentRepository;
import com.oak.dms.folder.DocumentAccessService;
import com.oak.dms.folder.DocumentAccessService.DocumentAccessView;
import com.oak.dms.folder.FolderAccessLevel;
import com.oak.dms.metadata.entity.DocumentType;
import com.oak.dms.metadata.repository.DocumentTypeRepository;
import com.oak.dms.relationship.dto.RelatedDocumentsResponse;
import com.oak.dms.relationship.dto.RelationshipCountsResponse;
import com.oak.dms.relationship.entity.DocumentRelationship;
import com.oak.dms.relationship.entity.RelationshipType;
import com.oak.dms.relationship.repository.DocumentRelationshipRepository;
import com.oak.dms.relationship.repository.RelationshipTypeRepository;
import com.oak.dms.security.auth.AuthorizationService;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.security.rbac.catalog.PermissionCodes;
import com.oak.dms.user.User;
import com.oak.dms.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The read side of relationships: the Related Documents panel and the batched counts. A fixed
 * number of queries however many links there are - the links of the document, the documents at
 * their other ends, the types, the people, the workflow state - plus one access view for the
 * caller, so the panel stays fast at 100 links (NFR-001).
 */
@Service
@RequiredArgsConstructor
public class RelationshipPanelService {

    private final DocumentRelationshipRepository relationships;
    private final RelationshipTypeRepository types;
    private final DocumentRepository documents;
    private final DocumentTypeRepository documentTypes;
    private final UserRepository users;
    private final DocumentAccessService documentAccess;
    private final AuthorizationService authorizationService;
    private final NamedParameterJdbcTemplate jdbc;

    @Transactional(readOnly = true)
    public RelatedDocumentsResponse panel(UUID documentId) {
        UUID tenantId = TenantContext.getTenantId();
        Document document = documents.findByDocumentIdAndTenantIdAndDeletedFalse(documentId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Document", documentId));
        DocumentAccessView access = documentAccess.forCaller();
        documentAccess.require(access, document, FolderAccessLevel.view);
        boolean mayCreate = authorizationService.hasPermission(PermissionCodes.DRM_RELATIONSHIPS_CREATE);
        boolean mayRemove = authorizationService.hasPermission(PermissionCodes.DRM_RELATIONSHIPS_REMOVE);

        List<DocumentRelationship> links = relationships.findActiveTouching(tenantId, documentId);
        Set<UUID> otherIds = new LinkedHashSet<>();
        for (DocumentRelationship l : links) {
            otherIds.add(otherEnd(l, documentId));
        }
        Map<UUID, Document> others = loadDocuments(tenantId, otherIds);
        Map<UUID, RelationshipType> typeById = types.findByTenantIdAndRelationshipTypeIdIn(tenantId,
                        links.stream().map(DocumentRelationship::getRelationshipTypeId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(RelationshipType::getRelationshipTypeId, t -> t));
        Map<UUID, String> typeNames = documentTypeNames(others.values());
        Map<UUID, String> people = personNames(links.stream().map(DocumentRelationship::getCreatedBy).filter(java.util.Objects::nonNull).collect(Collectors.toSet()));
        Map<UUID, String> workflow = latestWorkflowStatus(tenantId, others.keySet());

        Map<UUID, List<RelatedDocumentsResponse.Item>> itemsByType = new LinkedHashMap<>();
        for (DocumentRelationship l : links) {
            RelationshipType type = typeById.get(l.getRelationshipTypeId());
            boolean viewingIsSource = l.getSourceDocumentId().equals(documentId);
            UUID otherId = otherEnd(l, documentId);
            Document other = others.get(otherId);
            boolean visible = other != null && other.getPurgedAt() == null && !other.isTemplate() && access.canSee(other);

            Document source = viewingIsSource ? document : other;
            boolean editsSource = source != null && source.getPurgedAt() == null && !source.isDeleted()
                    && access.level(source).atLeast(FolderAccessLevel.edit);
            boolean targetVisible = viewingIsSource ? visible : access.canSee(document);
            boolean canEdit = mayCreate && editsSource && targetVisible;
            boolean canRemove = mayRemove && editsSource;

            String direction = type.isSymmetric() ? "symmetric" : viewingIsSource ? "outgoing" : "incoming";
            String label = viewingIsSource || type.isSymmetric() ? type.getLabel() : type.getInverseLabel();
            RelatedDocumentsResponse.Other shown = visible
                    ? new RelatedDocumentsResponse.Other(true, other.getDocumentId(), other.getTitle(),
                    typeNames.get(other.getDocumentTypeId()), statusLabel(other, workflow.get(other.getDocumentId())),
                    other.isDeleted(), other.getFolderId())
                    : RelatedDocumentsResponse.Other.hidden();
            itemsByType.computeIfAbsent(type.getRelationshipTypeId(), k -> new ArrayList<>()).add(
                    new RelatedDocumentsResponse.Item(l.getRelationshipId(), direction, label, l.getOrigin(), l.getNote(),
                            l.getCreatedAt(), l.getCreatedBy() == null ? null
                            : new RelatedDocumentsResponse.Person(l.getCreatedBy(), people.get(l.getCreatedBy())),
                            canEdit, canRemove, shown));
        }
        List<RelatedDocumentsResponse.Group> groups = typeById.values().stream()
                .filter(t -> itemsByType.containsKey(t.getRelationshipTypeId()))
                .sorted(Comparator.comparingInt(RelationshipType::getDisplayOrder).thenComparing(RelationshipType::getLabel))
                .map(t -> new RelatedDocumentsResponse.Group(t.getRelationshipTypeId(), t.getCode(), t.getLabel(),
                        t.getInverseLabel(), t.isEnabled(), itemsByType.get(t.getRelationshipTypeId())))
                .toList();
        boolean canAdd = mayCreate && !document.isTemplate() && access.level(document).atLeast(FolderAccessLevel.edit);
        return new RelatedDocumentsResponse(documentId, links.size(), canAdd, groups);
    }

    /** Link counts for the documents the caller can open; one query for the documents, one for the counts. */
    @Transactional(readOnly = true)
    public RelationshipCountsResponse counts(Collection<UUID> documentIds) {
        UUID tenantId = TenantContext.getTenantId();
        Set<UUID> ids = new LinkedHashSet<>(documentIds);
        DocumentAccessView access = documentAccess.forCaller();
        List<UUID> openable = documents.findAllByDocumentIdInAndTenantIdAndDeletedFalse(ids, tenantId).stream()
                .filter(access::canSee).map(Document::getDocumentId).toList();
        Map<UUID, Long> counts = new LinkedHashMap<>();
        openable.forEach(id -> counts.put(id, 0L));
        if (!openable.isEmpty()) {
            for (Object[] row : relationships.activeCountsPerDocument(tenantId, openable)) {
                counts.put((UUID) row[0], ((Number) row[1]).longValue());
            }
        }
        return new RelationshipCountsResponse(counts);
    }

    // ---------------------------------------------------------------- helpers

    private static UUID otherEnd(DocumentRelationship l, UUID documentId) {
        return l.getSourceDocumentId().equals(documentId) ? l.getTargetDocumentId() : l.getSourceDocumentId();
    }

    /** Documents by id inside the tenant, soft-deleted ones included (their links stay, flagged). */
    private Map<UUID, Document> loadDocuments(UUID tenantId, Collection<UUID> ids) {
        Map<UUID, Document> byId = new HashMap<>();
        if (!ids.isEmpty()) {
            documents.findAllById(ids).stream().filter(d -> d.getTenantId().equals(tenantId))
                    .forEach(d -> byId.put(d.getDocumentId(), d));
        }
        return byId;
    }

    private Map<UUID, String> documentTypeNames(Collection<Document> docs) {
        Set<UUID> ids = docs.stream().map(Document::getDocumentTypeId).collect(Collectors.toSet());
        Map<UUID, String> names = new HashMap<>();
        if (!ids.isEmpty()) {
            for (DocumentType t : documentTypes.findAllById(ids)) {
                names.put(t.getDocumentTypeId(), t.getTypeName());
            }
        }
        return names;
    }

    private Map<UUID, String> personNames(Set<UUID> ids) {
        Map<UUID, String> names = new HashMap<>();
        if (!ids.isEmpty()) {
            for (User u : users.findAllById(ids)) {
                names.put(u.getUserId(), u.getDisplayName());
            }
        }
        return names;
    }

    /** The newest workflow run's status per document - in_progress, approved, rejected or cancelled. */
    private Map<UUID, String> latestWorkflowStatus(UUID tenantId, Collection<UUID> documentIds) {
        Map<UUID, String> statuses = new HashMap<>();
        if (documentIds.isEmpty()) {
            return statuses;
        }
        jdbc.query("select distinct on (document_id) document_id, status from workflow_instances"
                        + " where tenant_id = :tenantId and document_id in (:ids) and is_deleted = false"
                        + " order by document_id, started_at desc",
                new MapSqlParameterSource("tenantId", tenantId).addValue("ids", documentIds),
                rs -> {
                    statuses.put(rs.getObject(1, UUID.class), rs.getString(2));
                });
        return statuses;
    }

    /** What the panel shows as status: the latest approval's state, else the document's own (Q1). */
    static String statusLabel(Document document, String workflowStatus) {
        if (workflowStatus != null) {
            switch (workflowStatus) {
                case "in_progress":
                    return "In approval";
                case "approved":
                    return "Approved";
                case "rejected":
                    return "Rejected";
                default:
                    break;
            }
        }
        return switch (document.getStatus()) {
            case "published" -> "Published";
            case "archived" -> "Archived";
            case "pending-deletion" -> "Pending deletion";
            default -> "Draft";
        };
    }
}
