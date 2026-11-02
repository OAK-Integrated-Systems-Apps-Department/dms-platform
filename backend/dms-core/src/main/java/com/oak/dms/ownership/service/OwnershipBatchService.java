package com.oak.dms.ownership.service;

import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.catalog.AuditObjectTypes;
import com.oak.dms.audit.enums.AuditOutcome;
import com.oak.dms.audit.service.AuditService;
import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.folder.DocumentAccessService;
import com.oak.dms.folder.DocumentAccessService.DocumentAccessView;
import com.oak.dms.ownership.dto.BatchSummaryResponse;
import com.oak.dms.ownership.dto.BulkPreviewResponse;
import com.oak.dms.ownership.dto.BulkTransferRequest;
import com.oak.dms.ownership.dto.DepartmentRef;
import com.oak.dms.ownership.dto.PersonRef;
import com.oak.dms.ownership.dto.SkippedDocuments;
import com.oak.dms.ownership.entity.OwnershipTransfer;
import com.oak.dms.ownership.entity.OwnershipTransferBatch;
import com.oak.dms.ownership.event.OwnershipTransferredEvent;
import com.oak.dms.ownership.helper.OwnedDocument;
import com.oak.dms.ownership.helper.OwnershipException;
import com.oak.dms.ownership.helper.OwnershipLocks;
import com.oak.dms.ownership.helper.OwnershipNames;
import com.oak.dms.ownership.helper.OwnershipScope;
import com.oak.dms.ownership.helper.OwnershipScope.Scope;
import com.oak.dms.ownership.repository.OwnershipTransferBatchRepository;
import com.oak.dms.ownership.service.OwnershipAccessService.Plan;
import com.oak.dms.ownership.service.OwnershipTargets.Target;
import com.oak.dms.security.auth.AuthorizationService;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.security.auth.UserPermissions;
import com.oak.dms.security.rbac.catalog.PermissionCodes;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Bulk and selective transfer (DOT-FR-003, DOT-FR-005, DOT-NFR-002). One request is one transaction: every
 * affected document is locked in ascending id order first, then moved in chunks of
 * {@value OwnershipTransferEngine#CHUNK} with set-based statements. Up to {@value #MAX_DOCUMENTS} documents per
 * request; a caller with more splits the set (the {@code limit} field lets it repeat "all"). A lock timeout
 * rolls the whole request back (409).
 */
@Service
@RequiredArgsConstructor
public class OwnershipBatchService {

    public static final int MAX_DOCUMENTS = 5000;
    private static final int LISTED_IDS = 100;
    /** %s is {@link OwnedDocumentsSql#where()}: fixed fragments only, every value bound as a :parameter. */
    private static final String CANDIDATE_IDS = "select d.document_id from documents d where %s order by d.document_id limit :take";

    private final OwnershipLocks locks;
    private final OwnershipScope ownershipScope;
    private final OwnershipTargets targets;
    private final OwnershipTransferEngine engine;
    private final OwnershipAccessService accessService;
    private final OwnershipQueryService queryService;
    private final OwnershipNames names;
    private final HoldingOwnerResolver holdingResolver;
    private final DocumentAccessService documentAccess;
    private final AuditService auditService;
    private final ApplicationEventPublisher events;
    private final NamedParameterJdbcTemplate jdbc;
    private final OwnershipTransferBatchRepository batches;
    private final AuthorizationService authorization;
    private final UserPermissions userPermissions;

    /** A request after validation: who gets the documents and which ids are candidates. */
    private record Prepared(Scope scope, Target target, boolean holding, UUID holdingDepartmentId, List<UUID> ids,
                            String reason, String kind) {
    }

    @Transactional
    public BatchSummaryResponse transfer(BulkTransferRequest request) {
        rejectOffboardingReason(request);
        UUID tenantId = TenantContext.getTenantId();
        UUID actorId = TenantContext.getUserId();
        locks.applyLockTimeout();
        Prepared prepared = prepare(tenantId, actorId, request);
        return execute(tenantId, actorId, request, prepared, request.sourceUserId(), request.sourceDepartmentId(), 0);
    }

    /**
     * The write half of a bulk transfer, also used by offboarding. {@code request} supplies mode/ids/exclusions/
     * note/grant; the transaction and the 5s lock timeout are the caller's.
     */
    BatchSummaryResponse executeFor(UUID tenantId, UUID actorId, BulkTransferRequest request, int flaggedTasks) {
        Prepared prepared = prepare(tenantId, actorId, request);
        return execute(tenantId, actorId, request, prepared, request.sourceUserId(), request.sourceDepartmentId(),
                flaggedTasks);
    }

    private BatchSummaryResponse execute(UUID tenantId, UUID actorId, BulkTransferRequest request, Prepared prepared,
                                         UUID sourceUserId, UUID sourceDepartmentId, int flaggedTasks) {
        Scope scope = prepared.scope();
        UUID batchId = UUID.randomUUID();
        List<UUID> ids = prepared.ids();
        List<OwnedDocument> locked = locks.lockDocuments(tenantId, ids);

        // classify what is not (or no longer) there, then what this caller may not touch
        Map<String, List<UUID>> skipped = new LinkedHashMap<>();
        Set<UUID> lockedIds = locked.stream().map(OwnedDocument::documentId).collect(Collectors.toSet());
        classifyMissing(tenantId, scope, ids, lockedIds, skipped);
        List<OwnedDocument> eligible = filterByScope(tenantId, scope, locked, skipped);

        var ctx = new OwnershipTransferEngine.Context(tenantId, actorId, names.nameOf(tenantId, actorId), prepared.target(),
                prepared.reason(), batchId, request.grant(), request.note());
        OwnershipTransferEngine.Outcome outcome = engine.run(ctx, eligible);
        if (outcome.alreadyOwner() > 0) {
            Set<UUID> moved = new HashSet<>(outcome.transferredIds());
            skipped.put(SkippedDocuments.ALREADY_OWNER, eligible.stream().map(OwnedDocument::documentId)
                    .filter(id -> !moved.contains(id)).collect(Collectors.toList()));
        }
        Map<String, Integer> summary = new LinkedHashMap<>();
        skipped.forEach((reason, list) -> summary.put(reason, list.size()));
        OwnershipTransferBatch batch = engine.recordBatch(ctx, prepared.kind(), sourceUserId, sourceDepartmentId,
                ids.size(), outcome, summary, flaggedTasks);

        if (!outcome.transferredIds().isEmpty()) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("batchId", batchId);
            value.put("kind", prepared.kind());
            value.put("reason", prepared.reason());
            value.put("sourceUserId", sourceUserId);
            value.put("sourceDepartmentId", sourceDepartmentId);
            value.put("newOwnerUserId", prepared.target().ownerUserId());
            value.put("newOwnerName", prepared.target().ownerName());
            value.put("newOwnerDepartmentId", prepared.target().ownerDepartmentId());
            value.put("ownershipState", prepared.target().state());
            value.put("total", ids.size());
            value.put("transferred", outcome.transferredIds().size());
            value.put("skipped", summary);
            value.put("accessGranted", outcome.accessGranted());
            value.put("convertedToRestricted", outcome.converted());
            auditService.event(AuditActionCatalog.OWNERSHIP_BATCH_TRANSFERRED)
                    .objectType(AuditObjectTypes.OWNERSHIP_BATCH)
                    .objectId(batchId)
                    .resourceName(prepared.kind() + " ownership transfer")
                    .outcome(AuditOutcome.SUCCESS)
                    .newValue(value)
                    .reason(request.note())
                    .log();
            events.publishEvent(new OwnershipTransferredEvent(tenantId, batchId, prepared.kind(), actorId,
                    prepared.target().ownerUserId(), prepared.target().ownerDepartmentId(),
                    outcome.transferredIds().size(), outcome.previousOwnerCounts(), null, null, prepared.holding(),
                    prepared.holdingDepartmentId()));
        }
        return summary(tenantId, batch, skippedList(skipped, scope), outcome.newOwnerLacksViewPermission(),
                prepared.holding());
    }

    /** What the same request would do, without doing it - the confirmation step of the bulk dialog. */
    @Transactional(readOnly = true)
    public BulkPreviewResponse preview(BulkTransferRequest request) {
        rejectOffboardingReason(request);
        UUID tenantId = TenantContext.getTenantId();
        UUID actorId = TenantContext.getUserId();
        Prepared prepared = prepare(tenantId, actorId, request);
        Scope scope = prepared.scope();
        List<OwnedDocument> read = locks.readDocuments(tenantId, prepared.ids());
        Map<String, List<UUID>> skipped = new LinkedHashMap<>();
        classifyMissing(tenantId, scope, prepared.ids(), read.stream().map(OwnedDocument::documentId)
                .collect(Collectors.toSet()), skipped);
        List<OwnedDocument> eligible = filterByScope(tenantId, scope, read, skipped);
        Target target = prepared.target();
        List<OwnedDocument> movable = new ArrayList<>();
        List<UUID> already = new ArrayList<>();
        for (OwnedDocument document : eligible) {
            if (target.ownerUserId().equals(document.ownerUserId())
                    && java.util.Objects.equals(target.ownerDepartmentId(), document.ownerDepartmentId())
                    && target.state().equals(document.ownershipState())) {
                already.add(document.documentId());
            } else {
                movable.add(document);
            }
        }
        if (!already.isEmpty()) {
            skipped.put(SkippedDocuments.ALREADY_OWNER, already);
        }
        Plan plan = accessService.plan(tenantId, target.ownerUserId(), movable);
        boolean grant = request.grant();
        Map<UUID, PersonRef> people = names.persons(tenantId, List.of(target.ownerUserId()));
        return new BulkPreviewResponse(prepared.ids().size(), movable.size(), skippedList(skipped, scope),
                grant ? plan.convertToRestricted().size() : 0, grant ? plan.addEntry().size() : 0,
                plan.newOwnerLacksViewPermission(), people.get(target.ownerUserId()), department(tenantId, target),
                prepared.holding());
    }

    @Transactional(readOnly = true)
    public BatchSummaryResponse batch(UUID batchId) {
        UUID tenantId = TenantContext.getTenantId();
        OwnershipTransferBatch batch = batches.findByBatchIdAndTenantId(batchId, tenantId)
                .orElseThrow(() -> new ResourceNotFoundException("Batch", batchId));
        if (!authorization.isTenantAdmin() && !TenantContext.getUserId().equals(batch.getCreatedBy())) {
            throw new ResourceNotFoundException("Batch", batchId);
        }
        List<SkippedDocuments> skipped = batch.getSkippedSummary().entrySet().stream()
                .map(e -> new SkippedDocuments(e.getKey(), e.getValue(), List.of())).toList();
        boolean holding = OwnershipTransfer.REASON_HOLDING.equals(batch.getReason());
        boolean lacksView = !userPermissions.holds(tenantId, batch.getNewOwnerUserId(), PermissionCodes.DOCM_DOCUMENTS_VIEW);
        return summary(tenantId, batch, skipped, lacksView, holding);
    }

    // ---------------------------------------------------------------- preparation

    /** "offboarding" is only ever set by the offboarding action, which also deactivates the account. */
    private static void rejectOffboardingReason(BulkTransferRequest request) {
        if (OwnershipTransfer.REASON_OFFBOARDING.equals(request.reason())) {
            throw OwnershipException.invalid("OWNERSHIP_REASON_INVALID", "reason must be \"manual\" or \"reorganization\".");
        }
    }

    private Prepared prepare(UUID tenantId, UUID actorId, BulkTransferRequest request) {
        Scope scope = ownershipScope.requireNonEmpty(tenantId, actorId);
        String mode = request.mode();
        if (!"all".equals(mode) && !"selected".equals(mode)) {
            throw OwnershipException.invalid("OWNERSHIP_MODE_INVALID", "mode must be \"all\" or \"selected\".");
        }
        String reason = request.reason() == null ? OwnershipTransfer.REASON_MANUAL : request.reason();
        if (!OwnershipTransfer.REASON_MANUAL.equals(reason) && !OwnershipTransfer.REASON_REORGANIZATION.equals(reason)
                && !OwnershipTransfer.REASON_OFFBOARDING.equals(reason)) {
            throw OwnershipException.invalid("OWNERSHIP_REASON_INVALID", "reason must be \"manual\" or \"reorganization\".");
        }
        BulkTransferRequest.TargetSpec spec = request.target();
        boolean holding = "holding".equals(spec.type());
        if (!holding && !"user".equals(spec.type())) {
            throw OwnershipException.invalid("OWNERSHIP_TARGET_INVALID", "target.type must be \"user\" or \"holding\".");
        }
        Target target;
        UUID holdingDepartmentId = null;
        if (holding) {
            holdingDepartmentId = spec.departmentId() != null ? spec.departmentId() : departmentOfSource(tenantId, request);
            var resolved = holdingResolver.resolve(tenantId, holdingDepartmentId, request.sourceUserId());
            target = new Target(resolved.userId(), resolved.name(), resolved.departmentId(),
                    com.oak.dms.document.Document.OWNERSHIP_HOLDING);
            reason = OwnershipTransfer.REASON_HOLDING;
        } else {
            target = targets.resolve(tenantId, spec.newOwnerUserId(), spec.newOwnerDepartmentId(),
                    com.oak.dms.document.Document.OWNERSHIP_ASSIGNED);
        }

        Set<UUID> excluded = request.excludeDocumentIds() == null ? Set.of() : new HashSet<>(request.excludeDocumentIds());
        List<UUID> ids;
        if ("all".equals(mode)) {
            if ((request.sourceUserId() == null) == (request.sourceDepartmentId() == null)) {
                throw OwnershipException.invalid("OWNERSHIP_SOURCE_REQUIRED",
                        "Name exactly one source: sourceUserId or sourceDepartmentId.");
            }
            queryService.requireInScope(tenantId, scope, request.sourceUserId(), request.sourceDepartmentId());
            ids = candidates(tenantId, scope, request, excluded);
        } else {
            if (request.documentIds() == null || request.documentIds().isEmpty()) {
                throw OwnershipException.invalid("OWNERSHIP_SELECTION_EMPTY", "Select at least one document.");
            }
            Set<UUID> chosen = new LinkedHashSet<>(request.documentIds());
            chosen.removeAll(excluded);
            if (chosen.size() > MAX_DOCUMENTS) {
                throw tooLarge(chosen.size());
            }
            ids = new ArrayList<>(chosen);
            ids.sort(com.oak.dms.relationship.helper.RelationshipKeys::compare);
        }
        String kind = OwnershipTransfer.REASON_REORGANIZATION.equals(reason) ? OwnershipTransferBatch.KIND_REORGANIZATION
                : OwnershipTransfer.REASON_OFFBOARDING.equals(reason) ? OwnershipTransferBatch.KIND_OFFBOARDING
                : OwnershipTransferBatch.KIND_BULK;
        return new Prepared(scope, target, holding, holdingDepartmentId, ids, reason, kind);
    }

    private UUID departmentOfSource(UUID tenantId, BulkTransferRequest request) {
        if (request.sourceDepartmentId() != null) {
            return request.sourceDepartmentId();
        }
        if (request.sourceUserId() == null) {
            return null;
        }
        List<UUID> found = jdbc.queryForList("select department_id from users where tenant_id = :t and user_id = :u"
                        + " and department_id is not null",
                new MapSqlParameterSource("t", tenantId).addValue("u", request.sourceUserId()), UUID.class);
        return found.isEmpty() ? null : found.get(0);
    }

    /** "All" candidates, ascending by id; refuses more than 5,000 unless the caller asked for a smaller {@code limit}. */
    private List<UUID> candidates(UUID tenantId, Scope scope, BulkTransferRequest request, Set<UUID> excluded) {
        Set<UUID> owners = ownershipScope.usersInScope(tenantId, scope);
        DocumentAccessView view = scope.unrestricted() ? null : documentAccess.forCaller();
        OwnedDocumentsSql sql = new OwnedDocumentsSql(tenantId).owner(request.sourceUserId())
                .department(request.sourceDepartmentId()).exclude(excluded).scope(scope, owners, view, true);
        long total = queryService.count(sql);
        Integer limit = request.limit();
        if (limit != null && (limit < 1 || limit > MAX_DOCUMENTS)) {
            throw OwnershipException.invalid("OWNERSHIP_LIMIT_INVALID", "limit must be between 1 and " + MAX_DOCUMENTS + ".");
        }
        if (limit == null && total > MAX_DOCUMENTS) {
            throw tooLarge(total);
        }
        int take = limit == null ? MAX_DOCUMENTS : limit;
        sql.param("take", take);
        List<UUID> ids = jdbc.queryForList(CANDIDATE_IDS.formatted(sql.where()), sql.params(), UUID.class);
        return new ArrayList<>(ids);
    }

    private static OwnershipException tooLarge(long total) {
        return new OwnershipException(org.springframework.http.HttpStatus.UNPROCESSABLE_CONTENT, "OWNERSHIP_BATCH_TOO_LARGE",
                "A transfer can move at most " + MAX_DOCUMENTS + " documents at once; " + total
                        + " were requested. Transfer them in several steps (the screen does this for you).",
                Map.of("total", total, "max", MAX_DOCUMENTS));
    }

    // ---------------------------------------------------------------- classification

    /** Ids that did not come back from the lock/read: purged (admin sees the reason) or simply not there. */
    private void classifyMissing(UUID tenantId, Scope scope, List<UUID> ids, Set<UUID> present,
                                 Map<String, List<UUID>> skipped) {
        List<UUID> missing = ids.stream().filter(id -> !present.contains(id)).toList();
        if (missing.isEmpty()) {
            return;
        }
        Set<UUID> purged = new HashSet<>(jdbc.queryForList("select document_id from documents where tenant_id = :t"
                + " and document_id in (:ids) and purged_at is not null",
                new MapSqlParameterSource("t", tenantId).addValue("ids", missing), UUID.class));
        List<UUID> notFound = new ArrayList<>();
        List<UUID> gone = new ArrayList<>();
        for (UUID id : missing) {
            // A manager is not told a purged document existed: for them it is just not found.
            if (purged.contains(id) && scope.unrestricted()) {
                gone.add(id);
            } else {
                notFound.add(id);
            }
        }
        if (!gone.isEmpty()) {
            skipped.put(SkippedDocuments.PURGED, gone);
        }
        if (!notFound.isEmpty()) {
            skipped.put(SkippedDocuments.NOT_FOUND, notFound);
        }
    }

    /** A manager's limits on locked rows: hidden documents count as not found, other departments' owners as out of scope. */
    private List<OwnedDocument> filterByScope(UUID tenantId, Scope scope, List<OwnedDocument> locked,
                                              Map<String, List<UUID>> skipped) {
        if (scope.unrestricted()) {
            return locked;
        }
        DocumentAccessView view = documentAccess.forCaller();
        Set<UUID> owners = ownershipScope.usersInScope(tenantId, scope);
        List<OwnedDocument> eligible = new ArrayList<>();
        for (OwnedDocument document : locked) {
            if (!view.canSee(document.accessStub())) {
                skipped.computeIfAbsent(SkippedDocuments.NOT_FOUND, k -> new ArrayList<>()).add(document.documentId());
            } else if (!owners.contains(document.ownerUserId())) {
                skipped.computeIfAbsent(SkippedDocuments.OUT_OF_SCOPE, k -> new ArrayList<>()).add(document.documentId());
            } else {
                eligible.add(document);
            }
        }
        return eligible;
    }

    /** Skipped groups for the response: ids are listed only for reasons that reveal nothing the caller could not know. */
    private List<SkippedDocuments> skippedList(Map<String, List<UUID>> skipped, Scope scope) {
        List<SkippedDocuments> list = new ArrayList<>();
        skipped.forEach((reason, documents) -> {
            boolean listIds = !SkippedDocuments.NOT_FOUND.equals(reason);
            list.add(new SkippedDocuments(reason, documents.size(),
                    listIds ? documents.stream().limit(LISTED_IDS).toList() : List.of()));
        });
        return list;
    }

    private BatchSummaryResponse summary(UUID tenantId, OwnershipTransferBatch batch, List<SkippedDocuments> skipped,
                                         boolean lacksView, boolean holding) {
        Map<UUID, PersonRef> people = names.persons(tenantId, List.of(batch.getNewOwnerUserId(), batch.getCreatedBy()));
        DepartmentRef department = batch.getNewOwnerDepartmentId() == null ? null
                : names.departments(tenantId, Collections.singletonList(batch.getNewOwnerDepartmentId()))
                .get(batch.getNewOwnerDepartmentId());
        return new BatchSummaryResponse(batch.getBatchId(), batch.getKind(), batch.getReason(), batch.getTotalCount(),
                batch.getTransferredCount(), batch.getSkippedCount(), skipped, batch.getAccessGrantedCount(),
                batch.getConvertedToRestrictedCount(), batch.getFlaggedTaskCount(), lacksView,
                names.person(people, batch.getNewOwnerUserId(), null), department, holding,
                names.person(people, batch.getCreatedBy(), null), batch.getCreatedAt(), batch.getCompletedAt());
    }

    private DepartmentRef department(UUID tenantId, Target target) {
        return target.ownerDepartmentId() == null ? null
                : names.departments(tenantId, Collections.singletonList(target.ownerDepartmentId()))
                .get(target.ownerDepartmentId());
    }
}
