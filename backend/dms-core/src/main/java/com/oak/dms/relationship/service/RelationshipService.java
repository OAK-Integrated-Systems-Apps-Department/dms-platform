package com.oak.dms.relationship.service;

import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.catalog.AuditObjectTypes;
import com.oak.dms.audit.enums.AuditOutcome;
import com.oak.dms.audit.helper.AuditEventBuilder;
import com.oak.dms.audit.service.AuditService;
import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.document.Document;
import com.oak.dms.document.DocumentRepository;
import com.oak.dms.folder.DocumentAccessService;
import com.oak.dms.folder.DocumentAccessService.DocumentAccessView;
import com.oak.dms.folder.FolderAccessLevel;
import com.oak.dms.relationship.dto.BulkCreateRelationshipsRequest;
import com.oak.dms.relationship.dto.BulkError;
import com.oak.dms.relationship.dto.BulkRelationshipsResponse;
import com.oak.dms.relationship.dto.ChainItem;
import com.oak.dms.relationship.dto.CreateRelationshipRequest;
import com.oak.dms.relationship.dto.RelationshipResponse;
import com.oak.dms.relationship.dto.UpdateRelationshipRequest;
import com.oak.dms.relationship.entity.DocumentRelationship;
import com.oak.dms.relationship.entity.RelationshipType;
import com.oak.dms.relationship.event.RelationshipCreatedEvent;
import com.oak.dms.relationship.exception.RelationshipException;
import com.oak.dms.relationship.helper.CycleDetector;
import com.oak.dms.relationship.helper.RelationshipKeys;
import com.oak.dms.relationship.helper.RelationshipLocks;
import com.oak.dms.relationship.repository.DocumentRelationshipRepository;
import com.oak.dms.relationship.repository.RelationshipTypeRepository;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.user.User;
import com.oak.dms.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Creates, edits and removes links between two documents (D1-D3, D10).
 * <ul>
 *   <li>Create/edit need the DRM create permission (the controller), edit access on the source and
 *       view access on the target. Remove needs the remove permission and edit access on the source.</li>
 *   <li>One row per pair: a symmetric type is stored lower id first, so the reversed pair is the same row.</li>
 *   <li>Bulk is all-or-nothing in one transaction; each link is its own row and audit event, plus one batch event.</li>
 *   <li>Loop-preventing types are serialised and checked - see {@link RelationshipLocks} for the lock order.</li>
 * </ul>
 * A document the caller can't open is reported exactly like one that doesn't exist.
 */
@Service
@RequiredArgsConstructor
public class RelationshipService {

    public static final int MAX_BULK_LINKS = 200;

    private final DocumentRelationshipRepository relationships;
    private final RelationshipTypeRepository types;
    private final DocumentRepository documents;
    private final DocumentAccessService documentAccess;
    private final RelationshipLocks locks;
    private final CycleDetector cycleDetector;
    private final NamedParameterJdbcTemplate jdbc;
    private final AuditService auditService;
    private final ApplicationEventPublisher events;
    private final UserRepository users;

    // ---------------------------------------------------------------- create

    @Transactional
    public RelationshipResponse create(CreateRelationshipRequest request) {
        UUID tenantId = TenantContext.getTenantId();
        UUID actor = TenantContext.getUserId();
        locks.applyLockTimeout();
        RelationshipType type = enabledType(tenantId, request.relationshipTypeId());
        lockForType(tenantId, type);
        Map<UUID, Document> docs = lockDocuments(tenantId, new LinkedHashSet<>(List.of(request.sourceDocumentId(), request.targetDocumentId())));
        DocumentAccessView access = documentAccess.forCaller();

        Document source = docs.get(request.sourceDocumentId());
        if (source == null) {
            throw new ResourceNotFoundException("Document", request.sourceDocumentId());
        }
        documentAccess.require(access, source, FolderAccessLevel.edit);
        Document target = docs.get(request.targetDocumentId());
        if (target == null || !access.canSee(target)) {
            throw new ResourceNotFoundException("Document", request.targetDocumentId());
        }
        Problem problem = validate(tenantId, type, source, target, access);
        if (problem != null) {
            throw problem.toException();
        }
        DocumentRelationship saved = insert(tenantId, actor, type, source, target, request.note(),
                DocumentRelationship.ORIGIN_MANUAL, null);
        audit(AuditActionCatalog.RELATIONSHIP_CREATED, saved, type, source, target, null, null);
        publishCreated(saved, request.targetDocumentId(), actor);
        return toResponse(saved, type);
    }

    @Transactional
    public BulkRelationshipsResponse createBulk(BulkCreateRelationshipsRequest request) {
        UUID tenantId = TenantContext.getTenantId();
        UUID actor = TenantContext.getUserId();
        Set<UUID> sources = new LinkedHashSet<>(request.sourceDocumentIds());
        Set<UUID> targets = new LinkedHashSet<>(request.targetDocumentIds());
        boolean anchorIsSource = sources.size() == 1;
        if (!anchorIsSource && targets.size() != 1) {
            throw RelationshipException.invalid("RELATIONSHIP_BULK_SHAPE",
                    "Link several documents to one document, or one document to several - not several to several.");
        }
        if (sources.size() * targets.size() > MAX_BULK_LINKS) {
            throw RelationshipException.invalid("RELATIONSHIP_BULK_TOO_LARGE",
                    "A bulk request can create at most " + MAX_BULK_LINKS + " links.");
        }
        locks.applyLockTimeout();
        RelationshipType type = enabledType(tenantId, request.relationshipTypeId());
        lockForType(tenantId, type);
        Set<UUID> all = new LinkedHashSet<>(sources);
        all.addAll(targets);
        Map<UUID, Document> docs = lockDocuments(tenantId, all);
        DocumentAccessView access = documentAccess.forCaller();

        // The anchor is the one document the caller named on its own: it must be there, whatever the others are.
        UUID anchorId = anchorIsSource ? sources.iterator().next() : targets.iterator().next();
        Document anchor = docs.get(anchorId);
        if (anchor == null || !access.canSee(anchor)) {
            throw new ResourceNotFoundException("Document", anchorId);
        }
        if (anchorIsSource) {
            documentAccess.require(access, anchor, FolderAccessLevel.edit);
        }

        UUID batchId = UUID.randomUUID();
        List<BulkError> errors = new ArrayList<>();
        List<DocumentRelationship> created = new ArrayList<>();
        Map<UUID, UUID> notifiedByRelationship = new LinkedHashMap<>();
        for (UUID sourceId : sources) {
            for (UUID targetId : targets) {
                UUID otherId = anchorIsSource ? targetId : sourceId;
                Document source = docs.get(sourceId);
                Document target = docs.get(targetId);
                Document other = docs.get(otherId);
                if (other == null || !access.canSee(other)) {
                    errors.add(new BulkError(otherId, "NOT_FOUND_OR_NO_ACCESS", "Document not found or you can't open it."));
                    continue;
                }
                if (!anchorIsSource && !access.level(source).atLeast(FolderAccessLevel.edit)) {
                    errors.add(new BulkError(otherId, "NO_EDIT_ACCESS", "You can open this document but not change it."));
                    continue;
                }
                Problem problem = validate(tenantId, type, source, target, access);
                if (problem != null) {
                    errors.add(new BulkError(otherId, problem.code(), problem.message()));
                    continue;
                }
                DocumentRelationship saved = insert(tenantId, actor, type, source, target, request.note(),
                        DocumentRelationship.ORIGIN_BULK, batchId);
                created.add(saved);
                notifiedByRelationship.put(saved.getRelationshipId(), targetId);
            }
        }
        if (!errors.isEmpty()) {
            throw new RelationshipException(HttpStatus.UNPROCESSABLE_CONTENT, "RELATIONSHIP_BULK_INVALID",
                    errors.size() + " of " + (sources.size() * targets.size()) + " links can't be created; nothing was saved.",
                    errors);
        }
        for (DocumentRelationship saved : created) {
            audit(AuditActionCatalog.RELATIONSHIP_CREATED, saved, type, docs.get(saved.getSourceDocumentId()),
                    docs.get(saved.getTargetDocumentId()), null, null);
        }
        Map<String, Object> batch = new LinkedHashMap<>();
        batch.put("batchId", batchId);
        batch.put("typeCode", type.getCode());
        batch.put("typeLabel", type.getLabel());
        batch.put("anchorDocumentId", anchorId);
        batch.put("anchorRole", anchorIsSource ? "source" : "target");
        batch.put("count", created.size());
        batch.put("relationshipIds", created.stream().map(DocumentRelationship::getRelationshipId).toList());
        auditService.event(AuditActionCatalog.RELATIONSHIP_BATCH_CREATED)
                .objectType(AuditObjectTypes.DOCUMENT_RELATIONSHIP)
                .objectId(batchId)
                .resourceName(type.getLabel() + " x" + created.size())
                .outcome(AuditOutcome.SUCCESS)
                .newValue(batch)
                .log();
        for (DocumentRelationship saved : created) {
            publishCreated(saved, notifiedByRelationship.get(saved.getRelationshipId()), actor);
        }
        return new BulkRelationshipsResponse(batchId, created.stream().map(r -> toResponse(r, type)).toList());
    }

    /**
     * The automatic "Duplicate of" (D3): the copy points at its original. Not a user action, so it
     * needs no DRM permission - the caller (a listener) has already checked that the original is
     * there and visible to the uploader. Quietly does nothing if the type is gone or disabled, or
     * the link would be a duplicate. Must run inside a transaction of its own.
     */
    @Transactional
    public DocumentRelationship createAutomatic(UUID tenantId, UUID actor, String typeCode, UUID sourceDocumentId,
                                                UUID targetDocumentId, String origin, UUID batchId) {
        locks.applyLockTimeout();
        RelationshipType type = types.findByTenantIdAndCodeAndDeletedFalse(tenantId, typeCode).orElse(null);
        if (type == null || !type.isEnabled()) {
            return null;
        }
        types.shareActive(type.getRelationshipTypeId(), tenantId);
        lockForType(tenantId, type);
        Map<UUID, Document> docs = lockDocuments(tenantId, new LinkedHashSet<>(List.of(sourceDocumentId, targetDocumentId)));
        Document source = docs.get(sourceDocumentId);
        Document target = docs.get(targetDocumentId);
        if (source == null || target == null || source.isTemplate() || target.isTemplate()) {
            return null;
        }
        Problem problem = validate(tenantId, type, source, target, null);
        if (problem != null) {
            return null;
        }
        DocumentRelationship saved = insert(tenantId, actor, type, source, target, null, origin, batchId);
        audit(AuditActionCatalog.RELATIONSHIP_CREATED, saved, type, source, target, null, actor);
        publishCreated(saved, targetDocumentId, actor);
        return saved;
    }

    // ---------------------------------------------------------------- edit

    @Transactional
    public RelationshipResponse update(UUID relationshipId, UpdateRelationshipRequest request) {
        UUID tenantId = TenantContext.getTenantId();
        UUID actor = TenantContext.getUserId();
        locks.applyLockTimeout();
        DocumentRelationship link = relationships.lockActive(relationshipId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Relationship", relationshipId));
        RelationshipType current = types.findByTenantIdAndRelationshipTypeIdIn(tenantId, Set.of(link.getRelationshipTypeId()))
                .stream().findFirst().orElseThrow();
        RelationshipType type = current;
        boolean typeChanged = request.relationshipTypeId() != null
                && !request.relationshipTypeId().equals(link.getRelationshipTypeId());
        if (typeChanged) {
            type = enabledType(tenantId, request.relationshipTypeId());
            if (!type.getDirectionality().equals(current.getDirectionality())) {
                throw RelationshipException.invalid("RELATIONSHIP_TYPE_DIRECTION_MISMATCH",
                        "A link can only change to a type with the same direction; remove it and add a new one instead.");
            }
            lockForType(tenantId, type);
        }
        Map<UUID, Document> docs = lockDocuments(tenantId, new LinkedHashSet<>(List.of(link.getSourceDocumentId(), link.getTargetDocumentId())));
        DocumentAccessView access = documentAccess.forCaller();
        Document source = docs.get(link.getSourceDocumentId());
        if (source == null) {
            throw new ResourceNotFoundException("Relationship", relationshipId);
        }
        documentAccess.require(access, source, FolderAccessLevel.edit);
        Document target = docs.get(link.getTargetDocumentId());
        if (target == null || !access.canSee(target)) {
            throw new ResourceNotFoundException("Relationship", relationshipId);
        }
        Map<String, Object> before = snapshot(link, current, source, target);
        if (typeChanged) {
            Problem problem = validate(tenantId, type, source, target, access);
            if (problem != null) {
                throw problem.toException();
            }
            link.setRelationshipTypeId(type.getRelationshipTypeId());
        }
        if (request.note() != null) {
            link.setNote(request.note().isBlank() ? null : request.note().strip());
        }
        link.setUpdatedBy(actor);
        link.setUpdatedAt(OffsetDateTime.now());
        relationships.saveAndFlush(link);
        auditService.event(AuditActionCatalog.RELATIONSHIP_UPDATED)
                .objectType(AuditObjectTypes.DOCUMENT_RELATIONSHIP)
                .objectId(link.getRelationshipId())
                .resourceName(type.getLabel())
                .outcome(AuditOutcome.SUCCESS)
                .previousValue(before)
                .newValue(snapshot(link, type, source, target))
                .log();
        return toResponse(link, type);
    }

    // ---------------------------------------------------------------- remove

    @Transactional
    public void remove(UUID relationshipId) {
        UUID tenantId = TenantContext.getTenantId();
        UUID actor = TenantContext.getUserId();
        locks.applyLockTimeout();
        DocumentRelationship link = relationships.lockActive(relationshipId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Relationship", relationshipId));
        Document source = documents.findByDocumentIdAndTenantIdAndDeletedFalse(link.getSourceDocumentId(), tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Relationship", relationshipId));
        documentAccess.require(documentAccess.forCaller(), source, FolderAccessLevel.edit);
        Document target = documents.findById(link.getTargetDocumentId()).orElse(null);
        RelationshipType type = types.findByTenantIdAndRelationshipTypeIdIn(tenantId, Set.of(link.getRelationshipTypeId()))
                .stream().findFirst().orElseThrow();
        Map<String, Object> before = snapshot(link, type, source, target);
        link.setRemovedAt(OffsetDateTime.now());
        link.setRemovedBy(actor);
        link.setRemovedReason(DocumentRelationship.REMOVED_BY_USER);
        link.setUpdatedBy(actor);
        link.setUpdatedAt(link.getRemovedAt());
        relationships.saveAndFlush(link);
        auditService.event(AuditActionCatalog.RELATIONSHIP_REMOVED)
                .objectType(AuditObjectTypes.DOCUMENT_RELATIONSHIP)
                .objectId(link.getRelationshipId())
                .resourceName(type.getLabel())
                .outcome(AuditOutcome.SUCCESS)
                .previousValue(before)
                .log();
    }

    // ---------------------------------------------------------------- shared

    /** What is wrong with a proposed link, if anything - thrown for one link, collected for a bulk request. */
    private record Problem(String code, String message, HttpStatus status, Object details) {
        RelationshipException toException() {
            return new RelationshipException(status, "RELATIONSHIP_" + code, message, details);
        }
    }

    private Problem validate(UUID tenantId, RelationshipType type, Document source, Document target,
                             DocumentAccessView access) {
        if (source.getDocumentId().equals(target.getDocumentId())) {
            return new Problem("SELF_LINK", "A document can't be linked to itself.", HttpStatus.UNPROCESSABLE_CONTENT, null);
        }
        if (source.isTemplate() || target.isTemplate()) {
            return new Problem("TEMPLATE", "A document template can't be linked.", HttpStatus.UNPROCESSABLE_CONTENT, null);
        }
        UUID from = source.getDocumentId();
        UUID to = target.getDocumentId();
        if (type.isSymmetric() && RelationshipKeys.compare(from, to) > 0) {
            from = target.getDocumentId();
            to = source.getDocumentId();
        }
        if (relationships.existsByTenantIdAndRelationshipTypeIdAndSourceDocumentIdAndTargetDocumentIdAndRemovedAtIsNull(
                tenantId, type.getRelationshipTypeId(), from, to)) {
            return new Problem("DUPLICATE", "These documents are already linked this way.", HttpStatus.CONFLICT, null);
        }
        if (type.isPreventsCycles()) {
            CycleDetector.Walk walk = cycleDetector.findPath(tenantId, type.getRelationshipTypeId(), target.getDocumentId(),
                    source.getDocumentId());
            if (walk.found()) {
                List<ChainItem> chain = chain(tenantId, walk.path(), access);
                return new Problem("CYCLE", "This would create a loop: " + describe(chain, source, access)
                        + ". Remove one of those links first.", HttpStatus.CONFLICT, Map.of("chain", chain));
            }
            if (walk.truncated()) {
                return new Problem("CHAIN_TOO_LONG", "The chain of " + type.getLabel() + " links is longer than "
                        + CycleDetector.MAX_DEPTH + " documents, so a loop can't be ruled out.", HttpStatus.CONFLICT, null);
            }
        }
        return null;
    }

    /** The loop as people read it: S -> T => ... => S, hidden documents named generically (D5). */
    private String describe(List<ChainItem> chain, Document source, DocumentAccessView access) {
        List<String> names = new ArrayList<>();
        names.add(name(source, access));
        chain.forEach(c -> names.add(c.title()));
        return String.join(" → ", names);
    }

    private static String name(Document document, DocumentAccessView access) {
        return access == null || access.canSee(document) ? document.getTitle() : ChainItem.HIDDEN_TITLE;
    }

    /** The documents along a path, titled only if the caller can open them. */
    private List<ChainItem> chain(UUID tenantId, List<UUID> path, DocumentAccessView access) {
        Map<UUID, Document> byId = new LinkedHashMap<>();
        documents.findAllById(path).stream().filter(d -> d.getTenantId().equals(tenantId))
                .forEach(d -> byId.put(d.getDocumentId(), d));
        List<ChainItem> items = new ArrayList<>();
        for (UUID id : path) {
            Document d = byId.get(id);
            if (d != null && (access == null || access.canSee(d))) {
                items.add(new ChainItem(id, d.getTitle()));
            } else {
                items.add(new ChainItem(null, ChainItem.HIDDEN_TITLE));
            }
        }
        return items;
    }

    private DocumentRelationship insert(UUID tenantId, UUID actor, RelationshipType type, Document source, Document target,
                                        String note, String origin, UUID batchId) {
        UUID from = source.getDocumentId();
        UUID to = target.getDocumentId();
        if (type.isSymmetric() && RelationshipKeys.compare(from, to) > 0) {
            from = target.getDocumentId();
            to = source.getDocumentId();
        }
        return relationships.saveAndFlush(DocumentRelationship.builder()
                .tenantId(tenantId)
                .relationshipTypeId(type.getRelationshipTypeId())
                .sourceDocumentId(from)
                .targetDocumentId(to)
                .origin(origin)
                .batchId(batchId)
                .note(note == null || note.isBlank() ? null : note.strip())
                .createdBy(actor)
                .build());
    }

    private RelationshipType enabledType(UUID tenantId, UUID typeId) {
        RelationshipType type = types.shareActive(typeId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Relationship type", typeId));
        if (!type.isEnabled()) {
            throw RelationshipException.conflict("RELATIONSHIP_TYPE_DISABLED",
                    "\"" + type.getLabel() + "\" has been switched off, so no new links can use it.");
        }
        return type;
    }

    private void lockForType(UUID tenantId, RelationshipType type) {
        if (type.isPreventsCycles()) {
            locks.lockTypeForCycleCheck(tenantId, type.getRelationshipTypeId());
        }
    }

    /**
     * Row-locks the documents in ascending id order, after finding which exist with plain id
     * lookups (loading an entity first would hand back a stale cached copy from the lock query).
     * Missing, deleted and other-tenant ids are simply absent from the result.
     */
    private Map<UUID, Document> lockDocuments(UUID tenantId, Set<UUID> ids) {
        List<UUID> live = new ArrayList<>(jdbc.queryForList(
                "select document_id from documents where tenant_id = :tenantId and document_id in (:ids) and is_deleted = false",
                new MapSqlParameterSource("tenantId", tenantId).addValue("ids", ids), UUID.class));
        live.sort(RelationshipKeys::compare);
        Map<UUID, Document> locked = new LinkedHashMap<>();
        for (UUID id : live) {
            documents.lockByDocumentIdAndTenantId(id, tenantId).ifPresent(d -> locked.put(id, d));
        }
        return locked;
    }

    private void publishCreated(DocumentRelationship saved, UUID notifiedDocumentId, UUID actor) {
        events.publishEvent(new RelationshipCreatedEvent(saved.getTenantId(), saved.getRelationshipId(),
                saved.getRelationshipTypeId(), saved.getSourceDocumentId(), saved.getTargetDocumentId(),
                notifiedDocumentId, actor, saved.getBatchId()));
    }

    private void audit(String action, DocumentRelationship link, RelationshipType type, Document source, Document target,
                       Object previous, UUID automaticActor) {
        AuditEventBuilder event = auditService.event(action);
        if (automaticActor != null) {
            // Not a request: the uploader, named explicitly, and the tenant the link belongs to.
            event.tenantId(link.getTenantId()).actor(automaticActor,
                    users.findById(automaticActor).map(User::getDisplayName).orElse(null));
        }
        event
                .objectType(AuditObjectTypes.DOCUMENT_RELATIONSHIP)
                .objectId(link.getRelationshipId())
                .resourceName(type.getLabel())
                .outcome(AuditOutcome.SUCCESS)
                .previousValue(previous)
                .newValue(snapshot(link, type, source, target))
                .log();
    }

    /** What an audit record keeps: who was linked to what, and how. Titles are as of now. */
    static Map<String, Object> snapshot(DocumentRelationship link, RelationshipType type, Document source, Document target) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("relationshipId", link.getRelationshipId());
        value.put("typeId", type.getRelationshipTypeId());
        value.put("typeCode", type.getCode());
        value.put("typeLabel", type.getLabel());
        value.put("inverseLabel", type.getInverseLabel());
        value.put("sourceDocumentId", link.getSourceDocumentId());
        value.put("sourceTitle", source == null ? null : source.getTitle());
        value.put("targetDocumentId", link.getTargetDocumentId());
        value.put("targetTitle", target == null ? null : target.getTitle());
        value.put("origin", link.getOrigin());
        value.put("batchId", link.getBatchId());
        value.put("note", link.getNote());
        return value;
    }

    static RelationshipResponse toResponse(DocumentRelationship link, RelationshipType type) {
        return new RelationshipResponse(link.getRelationshipId(), type.getRelationshipTypeId(), type.getCode(), type.getLabel(),
                type.getInverseLabel(), link.getSourceDocumentId(), link.getTargetDocumentId(), link.getOrigin(),
                link.getBatchId(), link.getNote(), link.getCreatedAt());
    }
}
