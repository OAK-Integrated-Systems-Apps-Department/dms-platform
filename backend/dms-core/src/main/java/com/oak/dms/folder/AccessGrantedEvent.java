package com.oak.dms.folder;

import com.oak.dms.folder.AccessPrincipals.Grant;
import com.oak.dms.folder.FolderAccessEntry.PrincipalType;
import com.oak.dms.security.auth.TenantContext;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * People or roles were newly put on a folder's or a document's own access list
 * (so it was shared with them). Published inside the transaction that saved
 * the list; the notification module tells the grantees (3.2, N10). Published
 * as an event rather than a call so the folder module needs nothing from the
 * notification module. Only NEW principals count - a change of level for
 * someone already on the list is not "shared with you".
 *
 * @param objectType "document" or "folder"
 * @param userIds    users named directly
 * @param roleIds    roles named; the listener expands them to their members
 */
public record AccessGrantedEvent(UUID tenantId, String objectType, UUID objectId, String objectName,
                                 UUID actorUserId, List<UUID> userIds, List<UUID> roleIds) {

    /** The event for whoever is on {@code after} but was not on {@code before}; empty when nobody is new. */
    static Optional<AccessGrantedEvent> of(String objectType, UUID objectId, String objectName,
                                           Collection<Grant> before, Collection<Grant> after) {
        Set<String> had = new HashSet<>();
        before.forEach(g -> had.add(g.key()));
        List<UUID> users = new ArrayList<>();
        List<UUID> roles = new ArrayList<>();
        for (Grant grant : after) {
            if (had.contains(grant.key())) {
                continue;
            }
            (grant.type() == PrincipalType.user ? users : roles).add(grant.principalId());
        }
        if (users.isEmpty() && roles.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new AccessGrantedEvent(TenantContext.getTenantId(), objectType, objectId, objectName,
                TenantContext.getUserId(), List.copyOf(users), List.copyOf(roles)));
    }
}
