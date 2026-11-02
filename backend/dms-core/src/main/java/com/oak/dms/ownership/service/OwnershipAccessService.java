package com.oak.dms.ownership.service;

import com.oak.dms.folder.DocumentAccessService;
import com.oak.dms.folder.DocumentAccessService.DocumentAccessView;
import com.oak.dms.folder.Folder;
import com.oak.dms.ownership.helper.OwnedDocument;
import com.oak.dms.security.auth.UserPermissions;
import com.oak.dms.security.rbac.catalog.PermissionCodes;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * D4: "Also give the new owner access". Works out which transferred documents the new owner could not open and
 * gives them a Can manage entry, so DOT-NFR-003 holds.
 * <p>
 * A document's own entries ({@code document_access}) only count while {@code documents.access_mode = 'restricted'}.
 * So a document that already has its own list just gets an entry, but one that follows a restricted folder is
 * switched to {@code restricted} with the folder's effective entries <em>copied</em> first - nobody who could open
 * it loses access, including the previous owner - and the new owner added as manager. Every write goes through
 * {@code document_access} / {@code documents.access_mode}, whose DB triggers (V61) bump the access generation, so
 * the access cache is invalidated without a manual call.
 */
@Service
@RequiredArgsConstructor
public class OwnershipAccessService {

    private final DocumentAccessService documentAccess;
    private final UserPermissions userPermissions;
    private final NamedParameterJdbcTemplate jdbc;

    /** What the new owner needs: an entry on documents with their own list, a switch to restricted (with copied entries) on the rest. */
    public record Plan(UUID newOwnerUserId, Set<UUID> addEntry, Map<UUID, UUID> convertToRestricted,
                       boolean newOwnerLacksViewPermission) {

        public Set<UUID> allNeeding() {
            Set<UUID> all = new HashSet<>(addEntry);
            all.addAll(convertToRestricted.keySet());
            return all;
        }

        public int needingCount() {
            return addEntry.size() + convertToRestricted.size();
        }
    }

    /** What was written. */
    public record Outcome(Set<UUID> granted, Set<UUID> converted, boolean newOwnerLacksViewPermission) {

        public static Outcome none(boolean lacksView) {
            return new Outcome(Set.of(), Set.of(), lacksView);
        }
    }

    /** Read-only: which of {@code documents} the new owner can't open today, and how each would be fixed. */
    @Transactional(readOnly = true)
    public Plan plan(UUID tenantId, UUID newOwnerUserId, Collection<OwnedDocument> documents) {
        boolean lacksView = !userPermissions.holds(tenantId, newOwnerUserId, PermissionCodes.DOCM_DOCUMENTS_VIEW);
        if (documents.isEmpty()) {
            return new Plan(newOwnerUserId, Set.of(), Map.of(), lacksView);
        }
        DocumentAccessView view = documentAccess.forUser(tenantId, newOwnerUserId);
        Set<UUID> addEntry = new HashSet<>();
        Map<UUID, UUID> convert = new LinkedHashMap<>();
        for (OwnedDocument document : documents) {
            if (view.canSee(document.accessStub())) {
                continue;
            }
            if (document.restricted()) {
                addEntry.add(document.documentId());
                continue;
            }
            // Follows its folder and the new owner can't open it: the governing folder is restricted.
            UUID governing = view.folders().governingFolder(document.folderId()).map(Folder::getFolderId).orElse(null);
            if (governing == null) {
                // Should not happen (an open folder gives manage); fall back to a plain own-list entry.
                governing = document.folderId();
            }
            convert.put(document.documentId(), governing);
        }
        return new Plan(newOwnerUserId, addEntry, convert, lacksView);
    }

    /** Writes the plan. Call inside the transfer's transaction, with the documents already locked. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Outcome apply(UUID tenantId, UUID actorId, Plan plan) {
        if (plan.needingCount() == 0) {
            return Outcome.none(plan.newOwnerLacksViewPermission());
        }
        // Group conversions by the folder whose entries are copied.
        Map<UUID, List<UUID>> byFolder = new LinkedHashMap<>();
        plan.convertToRestricted().forEach((documentId, folderId) ->
                byFolder.computeIfAbsent(folderId, k -> new ArrayList<>()).add(documentId));
        for (Map.Entry<UUID, List<UUID>> group : byFolder.entrySet()) {
            if (group.getKey() == null) {
                continue;
            }
            jdbc.update("""
                    insert into document_access (tenant_id, document_id, principal_type, principal_id, access_level, created_by)
                    select d.tenant_id, d.document_id, fa.principal_type, fa.principal_id, fa.access_level, :actor
                      from documents d
                      join folder_access fa on fa.tenant_id = d.tenant_id and fa.folder_id = :folder
                     where d.tenant_id = :t and d.document_id in (:ids)
                    on conflict (document_id, principal_type, principal_id) do nothing
                    """, new MapSqlParameterSource("actor", actorId).addValue("folder", group.getKey())
                    .addValue("t", tenantId).addValue("ids", group.getValue()));
        }
        Set<UUID> all = plan.allNeeding();
        jdbc.update("""
                insert into document_access (tenant_id, document_id, principal_type, principal_id, access_level, created_by)
                select d.tenant_id, d.document_id, 'user', :owner, 'manage', :actor
                  from documents d
                 where d.tenant_id = :t and d.document_id in (:ids)
                on conflict (document_id, principal_type, principal_id) do update set access_level = 'manage'
                """, new MapSqlParameterSource("owner", plan.newOwnerUserId()).addValue("actor", actorId)
                .addValue("t", tenantId).addValue("ids", all));
        if (!plan.convertToRestricted().isEmpty()) {
            jdbc.update("update documents set access_mode = 'restricted' where tenant_id = :t and document_id in (:ids)"
                            + " and access_mode <> 'restricted'",
                    new MapSqlParameterSource("t", tenantId).addValue("ids", plan.convertToRestricted().keySet()));
        }
        return new Outcome(all, new HashSet<>(plan.convertToRestricted().keySet()), plan.newOwnerLacksViewPermission());
    }
}
