package com.oak.dms.security.keycloak;

import com.oak.dms.common.exception.DuplicateResourceException;
import com.oak.dms.common.exception.KeycloakIntegrationException;
import com.oak.dms.common.exception.KeycloakUserAlreadyExistsException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.admin.client.resource.UsersResource;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Thin wrapper around the Keycloak Admin REST API for the DMS-domain
 * operations the dms-core needs. Keycloak remains the source of
 * truth for credentials/MFA; this class never touches passwords beyond
 * setting an initial temporary one and always requires the user to
 * verify/change it via Keycloak's own flows.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KeycloakUserService {

    private final Keycloak keycloakAdminClient;
    private final KeycloakAdminProperties properties;

    private RealmResource realm() {
        return keycloakAdminClient.realm(properties.getRealm());
    }

    /**
     * Resolves whatever a person typed as their login id - username or
     * email - to their Keycloak account, the same way Keycloak's own login
     * does ({@code loginWithEmailAllowed: true}). Exact username match
     * first; only if that finds nothing and the value looks like an email,
     * an exact email match. {@code users().search(value, true)} alone only
     * ever matches usernames, which silently broke every "username or
     * email" lookup for people whose username isn't their email.
     * <p>
     * Deliberately doesn't catch anything: every caller already treats a
     * lookup failure in its own way (fail open, no-op, or log-and-skip).
     */
    public Optional<UserRepresentation> findByUsernameOrEmail(String usernameOrEmail) {
        UsersResource users = realm().users();
        List<UserRepresentation> matches = users.searchByUsername(usernameOrEmail, true);
        if (matches.isEmpty() && usernameOrEmail.contains("@")) {
            matches = users.searchByEmail(usernameOrEmail, true);
        }
        return matches.stream().findFirst();
    }

    /**
     * Creates the account in Keycloak, sets a temporary password, marks it
     * for a required update on first login, and stamps the tenant_id
     * attribute that the realm's protocol mapper projects into the
     * "tenant_id" access token claim.
     * <p>
     * {@code username} becomes the Keycloak account's actual username (the
     * real login id, distinct from {@code email} - see
     * {@link com.oak.dms.user.User#getUsername()}'s own doc comment), not
     * a copy of the email. {@code loginWithEmailAllowed: true} in the realm
     * config (see {@code infra/local/keycloak/oak-dms-realm.json}) means
     * the direct-grant login endpoint still accepts either one, so this
     * doesn't affect signing in with an email address.
     *
     * @return the Keycloak-assigned subject id (user id) to store as
     *         users.keycloak_id in our own database.
     */
    public UUID createUser(String username, String email, String firstName, String lastName, UUID tenantId) {
        UsersResource usersResource = realm().users();

        UserRepresentation representation = new UserRepresentation();
        representation.setUsername(username);
        representation.setEmail(email);
        representation.setEnabled(true);
        representation.setEmailVerified(false);
        representation.setFirstName(firstName);
        representation.setLastName(lastName == null ? "" : lastName);
        representation.setAttributes(Map.of("tenant_id", List.of(tenantId.toString())));

        try (Response response = usersResource.create(representation)) {
            if (response.getStatus() == Response.Status.CONFLICT.getStatusCode()) {
                throw new KeycloakUserAlreadyExistsException(email, username);
            }
            if (response.getStatus() != Response.Status.CREATED.getStatusCode()) {
                throw new KeycloakIntegrationException(
                        "Failed to create user, status=" + response.getStatus());
            }
            String location = response.getLocation().getPath();
            UUID keycloakId = UUID.fromString(location.substring(location.lastIndexOf('/') + 1));

            setTemporaryPassword(keycloakId, UUID.randomUUID().toString());

            log.info("Created user {} for tenant {}", keycloakId, tenantId);

            return keycloakId;
        } catch (KeycloakUserAlreadyExistsException | KeycloakIntegrationException e) {
            throw e;
        } catch (Exception e) {
            throw new KeycloakIntegrationException("Failed to create user for " + email, e);
        }
    }

    public void setTemporaryPassword(UUID keycloakId, String temporaryPassword) {
        CredentialRepresentation credential = new CredentialRepresentation();
        credential.setType(CredentialRepresentation.PASSWORD);
        credential.setValue(temporaryPassword);
        credential.setTemporary(true);
        try {
            realm().users().get(keycloakId.toString()).resetPassword(credential);
        } catch (Exception e) {
            throw new KeycloakIntegrationException("Failed to set temporary password for " + keycloakId, e);
        }
    }

    public void disableUser(UUID keycloakId) {
        updateEnabled(keycloakId, false);
    }

    public void enableUser(UUID keycloakId) {
        updateEnabled(keycloakId, true);
    }

    /**
     * Sets the user's real, permanent password directly - used only by
     * {@link com.oak.dms.security.auth.PasswordResetService} once it has
     * independently verified a valid dms-core-issued reset token. Unlike
     * {@link #setTemporaryPassword}, {@code temporary} is false: the
     * person just chose this password themselves on our own
     * {@code /update-password} page, so there's nothing left to force a
     * follow-up change for. Keycloak's Admin API still enforces the
     * realm's password policy on this call - a policy violation surfaces
     * as a {@link jakarta.ws.rs.BadRequestException} (HTTP 400), which
     * the caller is expected to translate into a user-facing message
     * rather than letting it fall through as a generic integration error.
     */
    public void setPassword(UUID keycloakId, String newPassword) {
        CredentialRepresentation credential = new CredentialRepresentation();
        credential.setType(CredentialRepresentation.PASSWORD);
        credential.setValue(newPassword);
        credential.setTemporary(false);
        realm().users().get(keycloakId.toString()).resetPassword(credential);
    }

    private void updateEnabled(UUID keycloakId, boolean enabled) {
        try {
            UserRepresentation representation = realm().users().get(keycloakId.toString()).toRepresentation();
            representation.setEnabled(enabled);
            realm().users().get(keycloakId.toString()).update(representation);
        } catch (Exception e) {
            throw new KeycloakIntegrationException(
                    "Failed to " + (enabled ? "enable" : "disable") + " Keycloak user " + keycloakId, e);
        }
    }

    public void deleteUser(UUID keycloakId) {
        try (Response response = realm().users().delete(keycloakId.toString())) {
            if (response.getStatus() >= 300) {
                throw new KeycloakIntegrationException(
                        "Failed to delete Keycloak user " + keycloakId + ", status=" + response.getStatus());
            }
        } catch (KeycloakIntegrationException e) {
            throw e;
        } catch (Exception e) {
            throw new KeycloakIntegrationException("Failed to delete Keycloak user " + keycloakId, e);
        }
    }

    /**
     * Creates the realm role backing a tenant-defined DMS role.
     * <p>
     * The Keycloak-side name is the DMS role name verbatim (no tenant
     * prefix) so what an admin sees in the Keycloak console matches what's
     * in the DMS UI/DB exactly. This relies on
     * {@code com.oak.dms.security.rbac.service.impl.RoleServiceImpl} enforcing a
     * realm-wide (cross-tenant) uniqueness check
     * ({@code RoleRepository#existsByRoleNameAndDeletedFalse}) before this
     * is ever called, since Keycloak realm roles are unique per realm and
     * every tenant shares one realm here. If that check is ever skipped, a
     * 409 from Keycloak below is treated as a genuine naming conflict
     * (surfaced as {@link DuplicateResourceException}), NOT silently
     * absorbed as "already created" - unlike a per-tenant-namespaced name,
     * a plain name colliding could mean a different tenant already owns
     * it, and treating that as success would let this tenant's users end
     * up sharing another tenant's realm role.
     */
    public void createRealmRole(UUID tenantId, String roleName, String description) {
        String realmRoleName = realmRoleName(roleName);
        try {
            RoleRepresentation representation = new RoleRepresentation();
            representation.setName(realmRoleName);
            representation.setDescription(description);
            realm().roles().create(representation);
        } catch (WebApplicationException e) {
            if (e.getResponse() != null && e.getResponse().getStatus() == Response.Status.CONFLICT.getStatusCode()) {
                throw new DuplicateResourceException(
                        "Role name '" + roleName + "' is not available. Please choose another name.");
            }
            throw keycloakFailure("create realm role " + realmRoleName, e);
        } catch (Exception e) {
            throw new KeycloakIntegrationException("Failed to create realm role " + realmRoleName, e);
        }
    }

    /** Keeps the realm role name in sync when a tenant renames a DMS role. */
    public void renameRealmRole(UUID tenantId, String oldRoleName, String newRoleName) {
        String oldRealmRoleName = realmRoleName(oldRoleName);
        String newRealmRoleName = realmRoleName(newRoleName);
        try {
            RoleRepresentation representation = realm().roles().get(oldRealmRoleName).toRepresentation();
            representation.setName(newRealmRoleName);
            realm().roles().get(oldRealmRoleName).update(representation);
        } catch (WebApplicationException e) {
            if (e.getResponse() != null && e.getResponse().getStatus() == Response.Status.CONFLICT.getStatusCode()) {
                throw new DuplicateResourceException(
                        "Role name '" + newRoleName + "' is not available. Please choose another name.");
            }
            throw keycloakFailure("rename realm role " + oldRealmRoleName + " to " + newRealmRoleName, e);
        } catch (Exception e) {
            throw new KeycloakIntegrationException(
                    "Failed to rename realm role " + oldRealmRoleName + " to " + newRealmRoleName, e);
        }
    }

    public void assignRealmRole(UUID tenantId, UUID keycloakId, String roleName) {
        String realmRoleName = realmRoleName(roleName);
        try {
            var role = realm().roles().get(realmRoleName).toRepresentation();
            realm().users().get(keycloakId.toString()).roles().realmLevel().add(List.of(role));
        } catch (WebApplicationException e) {
            throw keycloakFailure("assign realm role " + realmRoleName + " to " + keycloakId, e);
        } catch (Exception e) {
            throw new KeycloakIntegrationException(
                    "Failed to assign realm role " + realmRoleName + " to " + keycloakId, e);
        }
    }

    /**
     * Assigns one of the three fixed platform realm roles (PLATFORM_ADMIN,
     * TENANT_ADMIN, MEMBER) to a Keycloak user by its literal name. Unlike
     * {@link #assignRealmRole}, these are not tenant-defined DMS roles from
     * the {@code roles} table - they're baked into the realm import and
     * read verbatim off the JWT's {@code realm_access.roles} by
     * {@code JwtAuthConverter}, so the name passed here is used exactly as
     * given, with no per-tenant transformation.
     */
    public void assignPlatformRealmRole(UUID keycloakId, String platformRoleName) {
        try {
            var role = realm().roles().get(platformRoleName).toRepresentation();
            realm().users().get(keycloakId.toString()).roles().realmLevel().add(List.of(role));
        } catch (WebApplicationException e) {
            throw keycloakFailure("assign platform realm role " + platformRoleName + " to " + keycloakId, e);
        } catch (Exception e) {
            throw new KeycloakIntegrationException(
                    "Failed to assign platform realm role " + platformRoleName + " to " + keycloakId, e);
        }
    }

    public void removeRealmRole(UUID tenantId, UUID keycloakId, String roleName) {
        String realmRoleName = realmRoleName(roleName);
        try {
            var role = realm().roles().get(realmRoleName).toRepresentation();
            realm().users().get(keycloakId.toString()).roles().realmLevel().remove(List.of(role));
        } catch (WebApplicationException e) {
            throw keycloakFailure("remove realm role " + realmRoleName + " from " + keycloakId, e);
        } catch (Exception e) {
            throw new KeycloakIntegrationException(
                    "Failed to remove realm role " + realmRoleName + " from " + keycloakId, e);
        }
    }

    /**
     * The two fixed platform realm roles surfaced by this method — see
     * {@link #assignPlatformRealmRole}, which still supports assigning
     * PLATFORM_ADMIN itself (it's a real, valid realm role, just not one
     * shown back through the DMS user screens this list feeds - a
     * cross-tenant platform administrator isn't scoped to any one tenant's
     * user management view the way TENANT_ADMIN/MEMBER are, so listing it
     * here isn't meaningful in the same way). Not stored anywhere in
     * dms-core's own database.
     */
    private static final Set<String> PLATFORM_ROLE_NAMES = Set.of("TENANT_ADMIN", "MEMBER");

    /**
     * Returns whichever of the platform roles in {@link #PLATFORM_ROLE_NAMES}
     * this Keycloak user directly holds (deliberately excludes
     * PLATFORM_ADMIN even if held - see that constant's doc comment).
     * <p>
     * Until this existed, there was no way to answer "does this user have
     * TENANT_ADMIN?" outside of decoding that specific user's own live JWT
     * at the moment they're logged in — nothing queryable for an arbitrary
     * user from an admin screen. This calls Keycloak's own role-mappings
     * API instead, so it works for any user regardless of whether they're
     * currently logged in.
     * <p>
     * Uses {@code realmLevel().listAll()} (the user's directly-assigned
     * realm roles) rather than {@code listEffective()} (which would also
     * expand composite roles) - deliberately, so realm plumbing roles like
     * {@code default-roles-<realm>}, {@code offline_access}, and
     * {@code uma_authorization} (all real assignments, but meaningless to
     * show as a "role" in the DMS UI) don't need filtering out here beyond
     * the explicit platform-role allowlist below. Any tenant-defined DMS
     * role this user also holds is deliberately excluded too - those are
     * already fully surfaced via {@code UserRoleController} /
     * {@code user_roles}; this method exists specifically to surface the
     * roles that aren't visible anywhere else.
     */
    public List<String> getPlatformRealmRoles(UUID keycloakId) {
        try {
            return realm().users().get(keycloakId.toString())
                    .roles().realmLevel().listAll()
                    .stream()
                    .map(RoleRepresentation::getName)
                    .filter(PLATFORM_ROLE_NAMES::contains)
                    .toList();
        } catch (WebApplicationException e) {
            throw keycloakFailure("list platform realm roles for " + keycloakId, e);
        } catch (Exception e) {
            throw new KeycloakIntegrationException(
                    "Failed to list platform realm roles for " + keycloakId, e);
        }
    }

    /**
     * Keycloak ids of every user directly assigned the realm role
     * {@code roleName} (e.g. {@code TENANT_ADMIN}), across all tenants -
     * the caller narrows to one tenant by matching {@code users.keycloak_id}.
     * Paged through Keycloak's role-members API. Used to include tenant
     * admins (who hold every permission implicitly, see
     * {@code AuthorizationService#hasFullAccess}) as quarantine
     * notification recipients.
     */
    public List<UUID> listRealmRoleMemberIds(String roleName) {
        final int pageSize = 100;
        try {
            List<UUID> ids = new ArrayList<>();
            for (int first = 0; ; first += pageSize) {
                List<UserRepresentation> page = realm().roles().get(roleName).getUserMembers(first, pageSize);
                page.forEach(user -> ids.add(UUID.fromString(user.getId())));
                if (page.size() < pageSize) {
                    return ids;
                }
            }
        } catch (WebApplicationException e) {
            throw keycloakFailure("list members of realm role " + roleName, e);
        } catch (Exception e) {
            throw new KeycloakIntegrationException("Failed to list members of realm role " + roleName, e);
        }
    }

    /**
     * Builds a {@link KeycloakIntegrationException} that preserves the
     * status code and response body from the Keycloak Admin REST API, so
     * the real cause (403 permission denied, 404 unknown role, 409
     * conflict, etc.) is visible in application logs instead of being
     * collapsed into a generic "Bad Gateway" with no detail. Response
     * bodies are best-effort: some Keycloak error paths return an empty
     * body, in which case we fall back to the HTTP reason phrase.
     */
    private static KeycloakIntegrationException keycloakFailure(String action, WebApplicationException e) {
        Response response = e.getResponse();
        int status = response != null ? response.getStatus() : -1;
        String body = "<no response body>";
        if (response != null) {
            try {
                body = response.readEntity(String.class);
            } catch (Exception ignored) {
                // Body already consumed or not text; keep the placeholder.
            }
        }
        return new KeycloakIntegrationException(
                "Failed to " + action + " (keycloak status=" + status + "): " + body, e);
    }

    /**
     * Keycloak-side role name for a DMS role. Deliberately just the plain
     * DMS role name (no tenant qualifier) so the name in the Keycloak
     * console matches the DMS UI/DB exactly. Realm-wide uniqueness across
     * tenants is enforced one layer up, in
     * {@code RoleServiceImpl#createRole}/{@code #updateRole}, before this
     * class is ever called - see {@link #createRealmRole} for what happens
     * if that's ever bypassed and a collision reaches Keycloak anyway.
     */
    private static String realmRoleName(String roleName) {
        return roleName;
    }
}