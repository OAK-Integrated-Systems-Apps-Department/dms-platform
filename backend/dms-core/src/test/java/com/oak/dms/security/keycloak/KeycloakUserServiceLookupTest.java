package com.oak.dms.security.keycloak;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.admin.client.resource.UsersResource;
import org.keycloak.representations.idm.UserRepresentation;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link KeycloakUserService#findByUsernameOrEmail}: the lookup behind
 * forgot-password, the MFA-required check and last-login tracking, which
 * all accept "username or email" but used to match usernames only.
 */
class KeycloakUserServiceLookupTest {

    private UsersResource users;
    private KeycloakUserService service;

    @BeforeEach
    void setUp() {
        Keycloak keycloak = mock(Keycloak.class);
        RealmResource realm = mock(RealmResource.class);
        users = mock(UsersResource.class);
        KeycloakAdminProperties properties = new KeycloakAdminProperties();
        properties.setRealm("oak-dms");
        when(keycloak.realm("oak-dms")).thenReturn(realm);
        when(realm.users()).thenReturn(users);
        when(users.searchByUsername(anyString(), anyBoolean())).thenReturn(List.of());
        when(users.searchByEmail(anyString(), anyBoolean())).thenReturn(List.of());
        service = new KeycloakUserService(keycloak, properties);
    }

    @Test
    void username_matchesExactUsername_withoutAnEmailLookup() {
        UserRepresentation admin = user("kc-1", "admin", "admin@oakis.biz");
        when(users.searchByUsername("admin", true)).thenReturn(List.of(admin));

        assertThat(service.findByUsernameOrEmail("admin")).containsSame(admin);
        verify(users, never()).searchByEmail(anyString(), anyBoolean());
    }

    @Test
    void email_whoseUsernameDiffers_isFoundByExactEmail() {
        // The production bug: username "admin", person types their email.
        UserRepresentation admin = user("kc-1", "admin", "admin@oakis.biz");
        when(users.searchByEmail("admin@oakis.biz", true)).thenReturn(List.of(admin));

        assertThat(service.findByUsernameOrEmail("admin@oakis.biz")).containsSame(admin);
    }

    @Test
    void usernameMatch_winsOverEmailMatch() {
        // An account whose username is literally an email address.
        UserRepresentation byUsername = user("kc-1", "someone@oakis.biz", "other@oakis.biz");
        when(users.searchByUsername("someone@oakis.biz", true)).thenReturn(List.of(byUsername));

        assertThat(service.findByUsernameOrEmail("someone@oakis.biz")).containsSame(byUsername);
        verify(users, never()).searchByEmail(anyString(), anyBoolean());
    }

    @Test
    void valueWithoutAt_isNeverLookedUpAsEmail() {
        assertThat(service.findByUsernameOrEmail("nobody")).isEmpty();
        verify(users, never()).searchByEmail(anyString(), anyBoolean());
    }

    @Test
    void unknownEmail_isEmpty() {
        assertThat(service.findByUsernameOrEmail("ghost@oakis.biz")).isEmpty();
    }

    private static UserRepresentation user(String id, String username, String email) {
        UserRepresentation user = new UserRepresentation();
        user.setId(id);
        user.setUsername(username);
        user.setEmail(email);
        return user;
    }
}
