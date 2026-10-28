package com.oak.dms.folder;

import com.oak.dms.common.sql.LikePatterns;
import com.oak.dms.folder.FolderAccessEntry.PrincipalType;
import com.oak.dms.folder.dto.FolderDtos.AccessPrincipal;
import com.oak.dms.folder.dto.FolderDtos.FolderAccessEntryRequest;
import com.oak.dms.folder.dto.FolderDtos.FolderAccessEntryResponse;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.security.rbac.entity.Role;
import com.oak.dms.security.rbac.repository.RoleRepository;
import com.oak.dms.security.rbac.repository.UserRoleRepository;
import com.oak.dms.user.User;
import com.oak.dms.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The people and roles side of access control, shared by folder access
 * (V53) and document access (V54): who the caller is, the picker search,
 * checking a submitted list, and describing a saved one. Needs
 * {@link TenantContext}.
 */
@Component
@RequiredArgsConstructor
public class AccessPrincipals {

    /** Stands in for "no roles" / "no ids" so an IN clause is never empty. */
    static final UUID NONE = new UUID(0, 0);
    private static final int PICKER_LIMIT = 10;

    private final UserRoleRepository userRoleRepository;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;

    /** One user or role at a level - a folder's or a document's entry, before or after saving. */
    public record Grant(PrincipalType type, UUID principalId, FolderAccessLevel level) {

        String key() {
            return type + ":" + principalId;
        }
    }

    /** The caller's active role ids, never empty (so they can go straight into an IN clause). */
    public List<UUID> callerRoleIds() {
        return roleIdsOf(TenantContext.getTenantId(), TenantContext.getUserId());
    }

    /** {@link #callerRoleIds()} for another user - access worked out on someone else's behalf. */
    public List<UUID> roleIdsOf(UUID tenantId, UUID userId) {
        List<UUID> roles = userRoleRepository.findActiveRoleIds(userId, tenantId);
        return roles.isEmpty() ? List.of(NONE) : roles;
    }

    /**
     * A submitted list, checked: known types and levels, people and roles that
     * exist in the tenant, one entry each (the higher level wins a duplicate).
     */
    public Map<String, Grant> parse(List<FolderAccessEntryRequest> entries) {
        Map<String, Grant> wanted = new LinkedHashMap<>();
        if (entries == null) {
            return wanted;
        }
        for (FolderAccessEntryRequest entry : entries) {
            PrincipalType type = parseEnum(PrincipalType.class, entry.principalType(), "principal type");
            FolderAccessLevel level = parseEnum(FolderAccessLevel.class, entry.level(), "access level");
            if (level == FolderAccessLevel.none) {
                throw FolderException.badRequest("FOLDER_ACCESS_INVALID", "Pick view, edit or manage.");
            }
            Grant grant = new Grant(type, entry.principalId(), level);
            wanted.merge(grant.key(), grant, (a, b) -> a.level().atLeast(b.level()) ? a : b);
        }
        requireExist(wanted.values());
        return wanted;
    }

    /** Adds the caller as a manager unless they already manage through the list (themselves or a role). */
    public void keepCallerAsManager(Map<String, Grant> wanted) {
        UUID userId = TenantContext.getUserId();
        Set<UUID> myRoles = Set.copyOf(callerRoleIds());
        boolean managed = wanted.values().stream().anyMatch(g -> g.level() == FolderAccessLevel.manage
                && ((g.type() == PrincipalType.user && g.principalId().equals(userId))
                || (g.type() == PrincipalType.role && myRoles.contains(g.principalId()))));
        if (!managed) {
            Grant me = new Grant(PrincipalType.user, userId, FolderAccessLevel.manage);
            wanted.put(me.key(), me);
        }
    }

    /** Names for a saved list; entries for a deleted user or role no longer match anyone and are left out. */
    public List<FolderAccessEntryResponse> describe(List<Grant> grants) {
        UUID tenantId = TenantContext.getTenantId();
        Map<UUID, User> users = userRepository
                .findAllByUserIdInAndTenantIdAndDeletedFalse(ids(grants, PrincipalType.user), tenantId).stream()
                .collect(Collectors.toMap(User::getUserId, Function.identity()));
        Map<UUID, Role> roles = roleRepository
                .findAllByRoleIdInAndTenantIdAndDeletedFalse(ids(grants, PrincipalType.role), tenantId).stream()
                .collect(Collectors.toMap(Role::getRoleId, Function.identity()));
        List<FolderAccessEntryResponse> out = new ArrayList<>();
        for (Grant g : grants) {
            if (g.type() == PrincipalType.user && users.containsKey(g.principalId())) {
                User u = users.get(g.principalId());
                out.add(new FolderAccessEntryResponse("user", u.getUserId(), nameOf(u), u.getEmail(), g.level().name()));
            } else if (g.type() == PrincipalType.role && roles.containsKey(g.principalId())) {
                Role r = roles.get(g.principalId());
                out.add(new FolderAccessEntryResponse("role", r.getRoleId(), r.getRoleName(), "Role", g.level().name()));
            }
        }
        return out;
    }

    /** Users and roles matching {@code q}, for the access picker. */
    public List<AccessPrincipal> search(String q) {
        UUID tenantId = TenantContext.getTenantId();
        String term = q == null ? "" : q.strip();
        List<AccessPrincipal> results = new ArrayList<>();
        // searchActive takes a LIKE pattern (lower-cased, wrapped in %), or null for everyone - the
        // raw term used to go in, which only ever matched a whole name typed in lower case.
        userRepository.searchActive(tenantId, term.isEmpty() ? null : LikePatterns.contains(term),
                PageRequest.of(0, PICKER_LIMIT))
                .forEach(u -> results.add(new AccessPrincipal(PrincipalType.user.name(), u.getUserId(), nameOf(u),
                        u.getEmail())));
        roleRepository.searchByName(tenantId, LikePatterns.escape(term), PageRequest.of(0, PICKER_LIMIT))
                .forEach(r -> results.add(new AccessPrincipal(PrincipalType.role.name(), r.getRoleId(),
                        r.getRoleName(), "Role")));
        return results;
    }

    /** An audit-friendly picture of a setting: its mode and "type:id:level" per entry. */
    static Map<String, Object> snapshot(FolderAccessMode mode, Collection<Grant> grants) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("accessMode", mode.name());
        value.put("entries", grants.stream().map(g -> g.key() + ":" + g.level()).toList());
        return value;
    }

    static <E extends Enum<E>> E parseEnum(Class<E> type, String value, String what) {
        try {
            return Enum.valueOf(type, value == null ? "" : value.strip().toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw FolderException.badRequest("FOLDER_ACCESS_INVALID", "Unknown " + what + " \"" + value + "\".");
        }
    }

    private void requireExist(Collection<Grant> grants) {
        UUID tenantId = TenantContext.getTenantId();
        Set<UUID> userIds = ids(grants, PrincipalType.user);
        Set<UUID> roleIds = ids(grants, PrincipalType.role);
        if (!userIds.isEmpty()
                && userRepository.findAllByUserIdInAndTenantIdAndDeletedFalse(userIds, tenantId).size() != userIds.size()) {
            throw FolderException.badRequest("FOLDER_ACCESS_INVALID", "One of the chosen people no longer exists.");
        }
        if (!roleIds.isEmpty()
                && roleRepository.findAllByRoleIdInAndTenantIdAndDeletedFalse(roleIds, tenantId).size() != roleIds.size()) {
            throw FolderException.badRequest("FOLDER_ACCESS_INVALID", "One of the chosen roles no longer exists.");
        }
    }

    private static Set<UUID> ids(Collection<Grant> grants, PrincipalType type) {
        return grants.stream().filter(g -> g.type() == type).map(Grant::principalId).collect(Collectors.toSet());
    }

    private static String nameOf(User user) {
        if (user.getDisplayName() != null && !user.getDisplayName().isBlank()) {
            return user.getDisplayName();
        }
        return user.getUsername() != null ? user.getUsername() : user.getEmail();
    }
}
