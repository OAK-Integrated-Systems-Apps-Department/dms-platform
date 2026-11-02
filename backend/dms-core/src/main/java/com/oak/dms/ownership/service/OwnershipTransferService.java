package com.oak.dms.ownership.service;

import com.oak.dms.audit.catalog.AuditActionCatalog;
import com.oak.dms.audit.catalog.AuditObjectTypes;
import com.oak.dms.audit.enums.AuditOutcome;
import com.oak.dms.audit.service.AuditService;
import com.oak.dms.common.exception.ResourceNotFoundException;
import com.oak.dms.folder.DocumentAccessService;
import com.oak.dms.folder.DocumentAccessService.DocumentAccessView;
import com.oak.dms.ownership.dto.DepartmentRef;
import com.oak.dms.ownership.dto.PersonRef;
import com.oak.dms.ownership.dto.TransferDocumentRequest;
import com.oak.dms.ownership.dto.TransferDocumentResponse;
import com.oak.dms.ownership.dto.TransferPreviewResponse;
import com.oak.dms.ownership.entity.OwnershipTransfer;
import com.oak.dms.ownership.entity.OwnershipTransferBatch;
import com.oak.dms.ownership.event.OwnershipTransferredEvent;
import com.oak.dms.ownership.helper.OwnedDocument;
import com.oak.dms.ownership.helper.OwnershipException;
import com.oak.dms.ownership.helper.OwnershipLocks;
import com.oak.dms.ownership.helper.OwnershipNames;
import com.oak.dms.ownership.helper.OwnershipScope;
import com.oak.dms.ownership.helper.OwnershipScope.Scope;
import com.oak.dms.ownership.service.OwnershipAccessService.Plan;
import com.oak.dms.ownership.service.OwnershipTargets.Target;
import com.oak.dms.security.auth.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Moves one document to a new owner (DOT-FR-002), outside or inside an offboarding. Everything keyed by the
 * document id - relationships, versions, comments, workflow runs, audit records - is left alone (D12); only
 * the owner columns change and a history row is appended.
 */
@Service
@RequiredArgsConstructor
public class OwnershipTransferService {

    private final OwnershipLocks locks;
    private final OwnershipScope ownershipScope;
    private final OwnershipTargets targets;
    private final OwnershipTransferEngine engine;
    private final OwnershipAccessService accessService;
    private final OwnershipNames names;
    private final DocumentAccessService documentAccess;
    private final AuditService auditService;
    private final ApplicationEventPublisher events;
    private final NamedParameterJdbcTemplate jdbc;

    @Transactional
    public TransferDocumentResponse transferOne(UUID documentId, TransferDocumentRequest request) {
        UUID tenantId = TenantContext.getTenantId();
        UUID actorId = TenantContext.getUserId();
        Scope scope = ownershipScope.requireNonEmpty(tenantId, actorId);

        locks.applyLockTimeout();
        // The document first: one that is missing, purged, in another tenant or hidden answers 404 whatever the target is.
        OwnedDocument document = lockAndCheck(tenantId, scope, documentId);
        Target target = targets.resolve(tenantId, request.newOwnerUserId(), request.newOwnerDepartmentId(),
                com.oak.dms.document.Document.OWNERSHIP_ASSIGNED);
        if (sameOwner(document, target)) {
            throw OwnershipException.unchanged();
        }

        UUID batchId = UUID.randomUUID();
        String actorName = names.nameOf(tenantId, actorId);
        var ctx = new OwnershipTransferEngine.Context(tenantId, actorId, actorName, target,
                OwnershipTransfer.REASON_MANUAL, batchId, request.grant(), request.note());
        OwnershipTransferEngine.Outcome outcome = engine.run(ctx, List.of(document));
        engine.recordBatch(ctx, OwnershipTransferBatch.KIND_SINGLE, document.ownerUserId(), document.ownerDepartmentId(),
                1, outcome, Map.of(), 0);

        boolean granted = outcome.accessGranted() > 0;
        boolean converted = outcome.converted() > 0;
        Map<UUID, PersonRef> people = names.persons(tenantId, List.of(document.ownerUserId(), target.ownerUserId()));
        Map<String, Object> previous = ownerSnapshot(document.ownerUserId(), people.get(document.ownerUserId()),
                document.ownerDepartmentId(), document.ownershipState());
        Map<String, Object> next = ownerSnapshot(target.ownerUserId(), people.get(target.ownerUserId()),
                target.ownerDepartmentId(), target.state());
        next.put("accessGranted", granted);
        next.put("convertedToRestricted", converted);
        next.put("batchId", batchId);
        auditService.event(AuditActionCatalog.DOCUMENT_OWNERSHIP_TRANSFERRED)
                .objectType(AuditObjectTypes.DOCUMENT)
                .objectId(documentId)
                .resourceName(document.title())
                .outcome(AuditOutcome.SUCCESS)
                .previousValue(previous)
                .newValue(next)
                .reason(request.note())
                .log();
        events.publishEvent(new OwnershipTransferredEvent(tenantId, batchId, OwnershipTransferBatch.KIND_SINGLE, actorId,
                target.ownerUserId(), target.ownerDepartmentId(), 1, outcome.previousOwnerCounts(), documentId,
                document.title(), false, null));

        UUID transferId = jdbc.queryForObject("select transfer_id from ownership_transfers where tenant_id = :t"
                        + " and batch_id = :b and document_id = :d",
                new MapSqlParameterSource("t", tenantId).addValue("b", batchId).addValue("d", documentId), UUID.class);
        PersonRef previousOwner = names.person(people, document.ownerUserId(), null);
        DepartmentRef department = names.departments(tenantId, java.util.Collections.singletonList(target.ownerDepartmentId()))
                .get(target.ownerDepartmentId());
        return new TransferDocumentResponse(transferId, batchId, documentId, previousOwner,
                people.get(target.ownerUserId()), department, granted, converted, outcome.newOwnerLacksViewPermission());
    }

    /** What {@link #transferOne} would do, writing nothing - for the dialog's confirmation step. */
    @Transactional(readOnly = true)
    public TransferPreviewResponse preview(UUID documentId, TransferDocumentRequest request) {
        UUID tenantId = TenantContext.getTenantId();
        UUID actorId = TenantContext.getUserId();
        Scope scope = ownershipScope.requireNonEmpty(tenantId, actorId);
        OwnedDocument document = read(tenantId, documentId);
        checkVisibleAndInScope(tenantId, scope, document);
        Target target = targets.resolve(tenantId, request.newOwnerUserId(), request.newOwnerDepartmentId(),
                com.oak.dms.document.Document.OWNERSHIP_ASSIGNED);
        Plan plan = accessService.plan(tenantId, target.ownerUserId(), List.of(document));
        Map<UUID, PersonRef> people = names.persons(tenantId, List.of(document.ownerUserId(), target.ownerUserId()));
        DepartmentRef department = names.departments(tenantId, java.util.Collections.singletonList(target.ownerDepartmentId()))
                .get(target.ownerDepartmentId());
        boolean convert = !plan.convertToRestricted().isEmpty();
        return new TransferPreviewResponse(documentId, names.person(people, document.ownerUserId(), null),
                people.get(target.ownerUserId()), department, sameOwner(document, target), plan.needingCount() == 0,
                request.grant() && !plan.addEntry().isEmpty(), request.grant() && convert,
                plan.newOwnerLacksViewPermission());
    }

    private OwnedDocument lockAndCheck(UUID tenantId, Scope scope, UUID documentId) {
        List<OwnedDocument> locked = locks.lockDocuments(tenantId, Set.of(documentId));
        if (locked.isEmpty()) {
            throw new ResourceNotFoundException("Document", documentId);
        }
        checkVisibleAndInScope(tenantId, scope, locked.get(0));
        return locked.get(0);
    }

    private void checkVisibleAndInScope(UUID tenantId, Scope scope, OwnedDocument document) {
        if (scope.unrestricted()) {
            return;
        }
        DocumentAccessView view = documentAccess.forCaller();
        if (!view.canSee(document.accessStub())) {
            throw new ResourceNotFoundException("Document", document.documentId());
        }
        if (!ownershipScope.ownerInScope(tenantId, scope, document.ownerUserId())) {
            throw OwnershipException.scopeDenied();
        }
    }

    /** A non-locking read for the preview; purged or missing = 404. */
    private OwnedDocument read(UUID tenantId, UUID documentId) {
        List<OwnedDocument> rows = locks.readDocuments(tenantId, Set.of(documentId));
        if (rows.isEmpty()) {
            throw new ResourceNotFoundException("Document", documentId);
        }
        return rows.get(0);
    }

    private static boolean sameOwner(OwnedDocument document, Target target) {
        return target.ownerUserId().equals(document.ownerUserId())
                && java.util.Objects.equals(target.ownerDepartmentId(), document.ownerDepartmentId())
                && target.state().equals(document.ownershipState());
    }

    private static Map<String, Object> ownerSnapshot(UUID userId, PersonRef person, UUID departmentId, String state) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("ownerUserId", userId);
        map.put("ownerName", person == null ? null : person.name());
        map.put("ownerDepartmentId", departmentId);
        map.put("ownershipState", state);
        return map;
    }
}
