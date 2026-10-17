package com.oak.dms.folder;

import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.security.rbac.repository.RoleRepository;
import com.oak.dms.security.rbac.repository.UserRoleRepository;
import com.oak.dms.user.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The access picker's search: what reaches the user and role queries for what the person typed. */
class AccessPrincipalsSearchTest {

    private final UUID tenantId = UUID.randomUUID();
    private UserRepository users;
    private RoleRepository roles;
    private AccessPrincipals principals;

    @BeforeEach
    void setUp() {
        users = mock(UserRepository.class);
        roles = mock(RoleRepository.class);
        principals = new AccessPrincipals(mock(UserRoleRepository.class), users, roles);
        TenantContext.set(tenantId, UUID.randomUUID());
        when(users.searchActive(eq(tenantId), any(), any(Pageable.class))).thenReturn(List.of());
        when(roles.searchByName(eq(tenantId), any(), any(Pageable.class))).thenReturn(List.of());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private String userPattern() {
        ArgumentCaptor<String> pattern = ArgumentCaptor.forClass(String.class);
        verify(users).searchActive(eq(tenantId), pattern.capture(), any(Pageable.class));
        return pattern.getValue();
    }

    @Test
    void whatIsTypedBecomesALowerCasedContainsPattern() {
        principals.search("  Jo Smith ");

        assertThat(userPattern()).isEqualTo("%jo smith%");
    }

    @Test
    void aBlankSearchListsEveryone_notAnExactMatchOnNothing() {
        principals.search("   ");

        assertThat(userPattern()).isNull();
    }

    @Test
    void aMissingSearchListsEveryone() {
        principals.search(null);

        assertThat(userPattern()).isNull();
    }

    @Test
    void wildcardsTypedByThePersonAreLiteral_forUsersAndRolesAlike() {
        principals.search("50%_off");

        assertThat(userPattern()).isEqualTo("%50\\%\\_off%");
        verify(roles).searchByName(eq(tenantId), eq("50\\%\\_off"), any(Pageable.class));
    }
}
