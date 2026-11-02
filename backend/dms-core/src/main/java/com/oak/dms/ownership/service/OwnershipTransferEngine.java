package com.oak.dms.ownership.service;

import com.oak.dms.ownership.entity.OwnershipTransferBatch;
import com.oak.dms.ownership.helper.OwnedDocument;
import com.oak.dms.ownership.repository.OwnershipTransferBatchRepository;
import com.oak.dms.ownership.service.OwnershipAccessService.Plan;
import com.oak.dms.ownership.service.OwnershipTargets.Target;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The write side shared by single, bulk and offboarding transfers. Takes documents that are already locked
 * (ascending id) and, in chunks of {@value #CHUNK}: gives the new owner access where needed (D4), appends the
 * history rows with one {@code INSERT ... SELECT} <em>before</em> the documents change (so the prior owner is
 * captured), then updates the documents with one set-based {@code UPDATE}. History is append-only, so the
 * {@code access_granted} flag is decided before the insert and never patched afterwards.
 */
@Component
@RequiredArgsConstructor
public class OwnershipTransferEngine {

    public static final int CHUNK = 500;

    private final NamedParameterJdbcTemplate jdbc;
    private final OwnershipAccessService accessService;
    private final OwnershipTransferBatchRepository batches;

    public record Context(UUID tenantId, UUID actorId, String actorName, Target target, String reason, UUID batchId,
                          boolean grantAccess, String note) {
    }

    public record Outcome(List<UUID> transferredIds, int alreadyOwner, int accessGranted, int converted,
                          boolean newOwnerLacksViewPermission, Map<UUID, Integer> previousOwnerCounts) {
    }

    /** Documents already held exactly as the target says (same owner, department, state) are skipped, not rewritten. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Outcome run(Context ctx, List<OwnedDocument> locked) {
        Target target = ctx.target();
        List<OwnedDocument> todo = new ArrayList<>();
        int already = 0;
        for (OwnedDocument document : locked) {
            if (target.ownerUserId().equals(document.ownerUserId())
                    && java.util.Objects.equals(target.ownerDepartmentId(), document.ownerDepartmentId())
                    && target.state().equals(document.ownershipState())) {
                already++;
            } else {
                todo.add(document);
            }
        }
        List<UUID> transferred = new ArrayList<>();
        Set<UUID> granted = new HashSet<>();
        Set<UUID> converted = new HashSet<>();
        boolean lacksView = false;
        Map<UUID, Integer> previous = new LinkedHashMap<>();
        for (int from = 0; from < todo.size(); from += CHUNK) {
            List<OwnedDocument> chunk = todo.subList(from, Math.min(todo.size(), from + CHUNK));
            Set<UUID> chunkGranted = Set.of();
            if (ctx.grantAccess()) {
                Plan plan = accessService.plan(ctx.tenantId(), target.ownerUserId(), chunk);
                OwnershipAccessService.Outcome outcome = accessService.apply(ctx.tenantId(), ctx.actorId(), plan);
                chunkGranted = outcome.granted();
                granted.addAll(outcome.granted());
                converted.addAll(outcome.converted());
                lacksView = outcome.newOwnerLacksViewPermission();
            }
            List<UUID> ids = chunk.stream().map(OwnedDocument::documentId).toList();
            jdbc.update("""
                    insert into ownership_transfers (tenant_id, document_id, previous_owner_user_id, previous_owner_department_id,
                        new_owner_user_id, new_owner_department_id, reason, batch_id, actor_user_id, access_granted, note,
                        previous_owner_name, new_owner_name, actor_name, transferred_at)
                    select d.tenant_id, d.document_id, d.owner_user_id, d.owner_department_id,
                           :owner, :department, :reason, :batch, :actor, (d.document_id in (:granted)), :note,
                           (select coalesce(nullif(u.display_name, ''), u.email) from users u where u.user_id = d.owner_user_id),
                           :ownerName, :actorName, clock_timestamp()
                      from documents d
                     where d.tenant_id = :t and d.document_id in (:ids)
                    """, new MapSqlParameterSource("owner", target.ownerUserId()).addValue("department", target.ownerDepartmentId())
                    .addValue("reason", ctx.reason()).addValue("batch", ctx.batchId()).addValue("actor", ctx.actorId())
                    .addValue("granted", chunkGranted.isEmpty() ? List.of(new UUID(0, 0)) : chunkGranted)
                    .addValue("note", ctx.note()).addValue("ownerName", truncate(target.ownerName()))
                    .addValue("actorName", truncate(ctx.actorName())).addValue("t", ctx.tenantId()).addValue("ids", ids));
            jdbc.update("""
                    update documents set owner_user_id = :owner, owner_department_id = :department, ownership_state = :state,
                           updated_by = :actor, updated_at = now()
                     where tenant_id = :t and document_id in (:ids)
                    """, new MapSqlParameterSource("owner", target.ownerUserId()).addValue("department", target.ownerDepartmentId())
                    .addValue("state", target.state()).addValue("actor", ctx.actorId()).addValue("t", ctx.tenantId())
                    .addValue("ids", ids));
            for (OwnedDocument document : chunk) {
                previous.merge(document.ownerUserId(), 1, Integer::sum);
                transferred.add(document.documentId());
            }
        }
        return new Outcome(transferred, already, granted.size(), converted.size(), lacksView, previous);
    }

    /** Records the run once, complete (the FK from the history rows is deferred, so order within the tx is free). */
    @Transactional(propagation = Propagation.MANDATORY)
    public OwnershipTransferBatch recordBatch(Context ctx, String kind, UUID sourceUserId, UUID sourceDepartmentId,
                                              int total, Outcome outcome, Map<String, Integer> skippedSummary,
                                              int flaggedTasks) {
        int skipped = total - outcome.transferredIds().size();
        OffsetDateTime now = OffsetDateTime.now();
        return batches.save(OwnershipTransferBatch.builder()
                .batchId(ctx.batchId())
                .tenantId(ctx.tenantId())
                .kind(kind)
                .sourceUserId(sourceUserId)
                .sourceDepartmentId(sourceDepartmentId)
                .newOwnerUserId(ctx.target().ownerUserId())
                .newOwnerDepartmentId(ctx.target().ownerDepartmentId())
                .reason(ctx.reason())
                .totalCount(total)
                .transferredCount(outcome.transferredIds().size())
                .skippedCount(skipped)
                .skippedSummary(new LinkedHashMap<>(skippedSummary))
                .accessGrantedCount(outcome.accessGranted())
                .convertedToRestrictedCount(outcome.converted())
                .flaggedTaskCount(flaggedTasks)
                .createdBy(ctx.actorId())
                .createdAt(now)
                .completedAt(now)
                .note(ctx.note())
                .build());
    }

    private static String truncate(String name) {
        return name == null ? null : name.length() > 200 ? name.substring(0, 200) : name;
    }
}
