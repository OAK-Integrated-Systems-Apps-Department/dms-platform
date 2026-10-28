package com.oak.dms.user;

import com.oak.dms.audit.helper.AuditEventBuilder;
import com.oak.dms.audit.service.AuditService;
import com.oak.dms.common.exception.EmailInUseException;
import com.oak.dms.common.exception.KeycloakUserAlreadyExistsException;
import com.oak.dms.common.exception.UsernameInUseException;
import com.oak.dms.outbox.OutboxEventPublisher;
import com.oak.dms.security.auth.AuthorizationService;
import com.oak.dms.security.auth.PasswordResetService;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.security.keycloak.KeycloakUserService;
import com.oak.dms.tenant.TenantRepository;
import com.oak.dms.user.dto.CreateUserRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Inviting a user: an email or username another account has is refused before anything is created. */
class UserServiceImplCreateTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID NOBODY = new UUID(0, 0);

    private final UserRepository users = mock(UserRepository.class);
    private final KeycloakUserService keycloak = mock(KeycloakUserService.class);
    private final AuditService audit = mock(AuditService.class);
    private final UserServiceImpl service = new UserServiceImpl(users, mock(UserMapper.class), keycloak,
            mock(PasswordResetService.class), mock(OutboxEventPublisher.class), mock(AuthorizationService.class),
            mock(TenantRepository.class), audit);

    @BeforeEach
    void setUp() {
        TenantContext.set(TENANT, UUID.randomUUID());
        when(audit.event(anyString())).thenReturn(mock(AuditEventBuilder.class, Answers.RETURNS_SELF));
        when(users.saveAndFlush(any())).thenAnswer(call -> {
            User user = call.getArgument(0);
            user.setUserId(UUID.randomUUID());
            return user;
        });
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static CreateUserRequest invite(String email, String username) {
        return new CreateUserRequest(email, username, "Nimal", "Perera");
    }

    @Test
    void anEmailAUserInTheTenantHas_isRefused_beforeAnyAccountIsMade() {
        when(users.existsByTenantIdAndEmailIgnoreCaseAndUserIdNot(TENANT, "nimal@example.com", NOBODY)).thenReturn(true);

        assertThatThrownBy(() -> service.createUser(invite(" nimal@example.com ", "nimal")))
                .isInstanceOf(EmailInUseException.class).hasMessageContaining("already used by another account");
        verify(keycloak, never()).createUser(any(), any(), any(), any(), any());
    }

    @Test
    void anEmailAnAccountElsewhereHas_getsTheSameAnswer() {
        when(keycloak.emailTakenByAnotherAccount("nimal@example.com", NOBODY)).thenReturn(true);

        assertThatThrownBy(() -> service.createUser(invite("nimal@example.com", "nimal")))
                .isInstanceOf(EmailInUseException.class);
        verify(keycloak, never()).createUser(any(), any(), any(), any(), any());
    }

    @Test
    void aUsernameAUserInTheTenantHas_isRefused() {
        when(users.existsByTenantIdAndUsernameIgnoreCase(TENANT, "nimal")).thenReturn(true);

        assertThatThrownBy(() -> service.createUser(invite("nimal@example.com", "nimal")))
                .isInstanceOf(UsernameInUseException.class).hasMessageContaining("username is already taken");
    }

    @Test
    void keycloakRefusingAFreeEmail_meansTheUsernameIsTakenElsewhere() {
        when(keycloak.createUser(any(), any(), any(), any(), any()))
                .thenThrow(new KeycloakUserAlreadyExistsException("nimal@example.com", "nimal"));

        assertThatThrownBy(() -> service.createUser(invite("nimal@example.com", "nimal")))
                .isInstanceOf(UsernameInUseException.class);
    }

    @Test
    void aGoodInvite_createsTheAccountWithTheTrimmedEmail() {
        UUID keycloakId = UUID.randomUUID();
        when(keycloak.createUser(any(), any(), any(), any(), any())).thenReturn(keycloakId);

        service.createUser(invite("  nimal@example.com ", " nimal "));

        verify(keycloak).createUser("nimal", "nimal@example.com", "Nimal", "Perera", TENANT);
    }

    @Test
    void whenTheRowCantBeSaved_theKeycloakAccountJustMadeIsRemovedAgain() {
        UUID keycloakId = UUID.randomUUID();
        when(keycloak.createUser(any(), any(), any(), any(), any())).thenReturn(keycloakId);
        doThrow(new DataIntegrityViolationException("uq_users_tenant_email")).when(users).saveAndFlush(any());
        when(users.existsByTenantIdAndEmailIgnoreCaseAndUserIdNot(eq(TENANT), eq("nimal@example.com"), any()))
                .thenReturn(false, true); // free when checked first, taken by the time the row is saved

        assertThatThrownBy(() -> service.createUser(invite("nimal@example.com", "nimal")))
                .isInstanceOf(EmailInUseException.class);
        verify(keycloak).deleteUser(keycloakId);
    }
}
