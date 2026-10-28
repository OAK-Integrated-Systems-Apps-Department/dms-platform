package com.oak.dms.workflow.helper;

import com.oak.dms.document.Document;
import com.oak.dms.document.DocumentRepository;
import com.oak.dms.security.auth.UserPermissions;
import com.oak.dms.security.rbac.catalog.PermissionCodes;
import com.oak.dms.security.rbac.repository.RolePermissionRepository;
import com.oak.dms.user.User;
import com.oak.dms.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Who workflow mails go to - shared by the notification listeners (runs after commit, no TenantContext). */
@Component
@RequiredArgsConstructor
public class WorkflowRecipients {

    private final UserRepository userRepository;
    private final RolePermissionRepository rolePermissionRepository;
    private final UserPermissions userPermissions;
    private final DocumentRepository documentRepository;

    /** Of these ids: active, not deleted users of the tenant with an email address, in order. */
    public Map<UUID, User> activeUsers(Collection<UUID> ids, UUID tenantId) {
        Map<UUID, User> users = new LinkedHashMap<>();
        Set<UUID> wanted = new LinkedHashSet<>();
        ids.stream().filter(Objects::nonNull).forEach(wanted::add);
        if (wanted.isEmpty()) {
            return users;
        }
        Map<UUID, User> found = new LinkedHashMap<>();
        userRepository.findAllById(wanted).forEach(u -> found.put(u.getUserId(), u));
        for (UUID id : wanted) {
            User u = found.get(id);
            if (u != null && tenantId.equals(u.getTenantId()) && !u.isDeleted()
                    && u.getStatus() == User.UserStatus.active && u.getEmail() != null && !u.getEmail().isBlank()) {
                users.put(id, u);
            }
        }
        return users;
    }

    public User activeUser(UUID id, UUID tenantId) {
        return id == null ? null : activeUsers(Set.of(id), tenantId).get(id);
    }

    /** Holders of wa.reassign-workflow-task plus the tenant admins - who can unblock a stuck task. */
    public Set<UUID> reassignersAndAdmins(UUID tenantId) {
        Set<UUID> ids = new LinkedHashSet<>(
                rolePermissionRepository.findUserIdsWithPermission(tenantId, PermissionCodes.WA_TASK_REASSIGN));
        ids.addAll(userPermissions.tenantAdminIds(tenantId));
        return ids;
    }

    public String documentTitle(UUID documentId) {
        return documentRepository.findById(documentId).map(Document::getTitle).orElse("a document");
    }
}
