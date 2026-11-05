package com.oak.dms.security.keycloak;

import com.oak.dms.common.exception.KeycloakIntegrationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.admin.client.resource.UserProfileResource;
import org.keycloak.admin.client.resource.UserResource;
import org.keycloak.admin.client.resource.UsersResource;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.keycloak.representations.userprofile.config.UPAttribute;
import org.keycloak.representations.userprofile.config.UPAttributePermissions;
import org.keycloak.representations.userprofile.config.UPConfig;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Renaming an account through Keycloak needs the realm's {@code editUsernameAllowed} switched on - which on its own would
 * let every user rename THEMSELVES in Keycloak's account console. So the realm's user profile is first made to let only
 * administrators edit the username, and only then is the setting turned on.
 */
class KeycloakUserServiceRenameTest {

    private final UUID id = UUID.randomUUID();
    private RealmResource realm;
    private UserProfileResource profile;
    private UserResource user;
    private RealmRepresentation realmRepresentation;
    private UPConfig config;
    private KeycloakUserService service;

    @BeforeEach
    void setUp() {
        Keycloak keycloak = mock(Keycloak.class);
        realm = mock(RealmResource.class);
        UsersResource users = mock(UsersResource.class);
        profile = mock(UserProfileResource.class);
        user = mock(UserResource.class);
        KeycloakAdminProperties properties = new KeycloakAdminProperties();
        properties.setRealm("oak-dms");
        when(keycloak.realm("oak-dms")).thenReturn(realm);
        when(realm.users()).thenReturn(users);
        when(users.userProfile()).thenReturn(profile);
        when(users.get(id.toString())).thenReturn(user);

        // As the realm ships: the username editable by the users themselves, the setting off.
        config = new UPConfig();
        config.addOrReplaceAttribute(new UPAttribute("username",
                new UPAttributePermissions(Set.of("admin", "user"), Set.of("admin", "user"))));
        when(profile.getConfiguration()).thenReturn(config);
        realmRepresentation = new RealmRepresentation();
        realmRepresentation.setRealm("oak-dms");
        realmRepresentation.setEditUsernameAllowed(false);
        when(realm.toRepresentation()).thenReturn(realmRepresentation);
        UserRepresentation existing = new UserRepresentation();
        existing.setUsername("old.name");
        when(user.toRepresentation()).thenReturn(existing);

        service = new KeycloakUserService(keycloak, properties);
    }

    @Test
    void aRename_firstLetsOnlyAdministratorsEditTheUsername_thenTurnsTheSettingOn_thenRenames() {
        service.updateProfile(id, "Ada", "Admin", null, "new.name");

        InOrder order = inOrder(profile, realm, user);
        order.verify(profile).update(config);
        order.verify(realm).update(realmRepresentation);
        ArgumentCaptor<UserRepresentation> renamed = ArgumentCaptor.forClass(UserRepresentation.class);
        order.verify(user).update(renamed.capture());
        assertThat(renamed.getValue().getUsername()).isEqualTo("new.name");

        assertThat(config.getAttribute("username").getPermissions().getEdit()).containsExactly("admin");
        assertThat(realmRepresentation.isEditUsernameAllowed()).isTrue();
    }

    @Test
    void whoCanSeeTheUsername_isLeftAlone() {
        service.updateProfile(id, "Ada", "Admin", null, "new.name");

        assertThat(config.getAttribute("username").getPermissions().getView()).containsExactlyInAnyOrder("admin", "user");
    }

    @Test
    void asecondRename_findsItAllDone_andChangesNothingInTheRealm() {
        service.updateProfile(id, "Ada", "Admin", null, "first.name");
        org.mockito.Mockito.clearInvocations(profile, realm);

        service.updateProfile(id, "Ada", "Admin", null, "second.name");

        verify(profile, never()).update(any(UPConfig.class));
        verify(realm, never()).update(any(RealmRepresentation.class));
        verify(user, org.mockito.Mockito.times(2)).update(any(UserRepresentation.class));
    }

    @Test
    void withoutARename_theRealmIsNotTouchedAtAll() {
        service.updateProfile(id, "Ada", "Admin", "new@example.invalid");

        verify(profile, never()).getConfiguration();
        verify(profile, never()).update(any(UPConfig.class));
        verify(realm, never()).update(any(RealmRepresentation.class));
        verify(user).update(any(UserRepresentation.class));
    }

    @Test
    void ifTheRealmHasNoUsernameAttribute_itWontRename_ratherThanLeaveWhoMayRenameUncontrolled() {
        config.setAttributes(new java.util.ArrayList<>());

        assertThatThrownBy(() -> service.updateProfile(id, "Ada", "Admin", null, "new.name"))
                .isInstanceOf(KeycloakIntegrationException.class)
                .hasMessageContaining("no username attribute");

        verify(realm, never()).update(any(RealmRepresentation.class));
        verify(user, never()).update(any(UserRepresentation.class));
    }

    @Test
    void anAttributeWithNoPermissionsAtAll_getsAdministratorsOnlyEditing_andTheDefaultViewing() {
        config.addOrReplaceAttribute(new UPAttribute("username"));

        service.updateProfile(id, "Ada", "Admin", null, "new.name");

        UPAttributePermissions permissions = config.getAttribute("username").getPermissions();
        assertThat(permissions.getEdit()).containsExactly("admin");
        assertThat(permissions.getView()).containsExactlyInAnyOrder("admin", "user");
    }
}
