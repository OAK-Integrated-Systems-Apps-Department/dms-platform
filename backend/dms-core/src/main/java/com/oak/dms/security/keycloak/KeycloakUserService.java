package com.oak.dms.security.keycloak;

import com.oak.dms.common.exception.KeycloakIntegrationException;
import jakarta.ws.rs.core.Response;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.admin.client.resource.UsersResource;
import org.keycloak.representations.idm.CredentialRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
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
     * Creates the account in Keycloak, sets a temporary password, marks it
     * for a required update on first login, and stamps the tenant_id
     * attribute that the realm's protocol mapper projects into the
     * "tenant_id" access token claim.
     *
     * @return the Keycloak-assigned subject id (user id) to store as
     *         users.keycloak_id in our own database.
     */
    public UUID createUser(String email, String displayName, UUID tenantId) {
        UsersResource usersResource = realm().users();

        UserRepresentation representation = new UserRepresentation();
        representation.setUsername(email);
        representation.setEmail(email);
        representation.setEnabled(true);
        representation.setEmailVerified(false);
        String[] nameParts = displayName.trim().split("\\s+", 2);
        representation.setFirstName(nameParts[0]);
        representation.setLastName(nameParts.length > 1 ? nameParts[1] : "");
        representation.setAttributes(Map.of("tenant_id", List.of(tenantId.toString())));

        try (Response response = usersResource.create(representation)) {
            if (response.getStatus() != 201) {
                throw new KeycloakIntegrationException(
                        "Failed to create Keycloak user, status=" + response.getStatus());
            }
            String location = response.getLocation().getPath();
            UUID keycloakId = UUID.fromString(location.substring(location.lastIndexOf('/') + 1));

            setTemporaryPassword(keycloakId, UUID.randomUUID().toString());
            sendRequiredActionsEmail(keycloakId);

            log.info("Created Keycloak user {} for tenant {}", keycloakId, tenantId);
            return keycloakId;
        } catch (KeycloakIntegrationException e) {
            throw e;
        } catch (Exception e) {
            throw new KeycloakIntegrationException("Failed to create Keycloak user for " + email, e);
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

    /** Emails the user a link to verify their address and set their own password. */
    public void sendRequiredActionsEmail(UUID keycloakId) {
        try {
            realm().users().get(keycloakId.toString())
                    .executeActionsEmail(List.of("VERIFY_EMAIL", "UPDATE_PASSWORD"));
        } catch (Exception e) {
            // Non-fatal: the account exists and can still be activated by an
            // administrator resending the action email later.
            log.warn("Failed to send required-actions email to Keycloak user {}: {}", keycloakId, e.getMessage());
        }
    }

    public void disableUser(UUID keycloakId) {
        updateEnabled(keycloakId, false);
    }

    public void enableUser(UUID keycloakId) {
        updateEnabled(keycloakId, true);
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

    public void assignRealmRole(UUID keycloakId, String roleName) {
        try {
            var role = realm().roles().get(roleName).toRepresentation();
            realm().users().get(keycloakId.toString()).roles().realmLevel().add(List.of(role));
        } catch (Exception e) {
            throw new KeycloakIntegrationException(
                    "Failed to assign realm role " + roleName + " to " + keycloakId, e);
        }
    }

    public void removeRealmRole(UUID keycloakId, String roleName) {
        try {
            var role = realm().roles().get(roleName).toRepresentation();
            realm().users().get(keycloakId.toString()).roles().realmLevel().remove(List.of(role));
        } catch (Exception e) {
            throw new KeycloakIntegrationException(
                    "Failed to remove realm role " + roleName + " from " + keycloakId, e);
        }
    }
}
