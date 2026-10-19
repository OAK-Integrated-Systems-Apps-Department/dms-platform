package com.oak.dms.user;

import com.oak.dms.audit.helper.AuditEventBuilder;
import com.oak.dms.audit.service.AuditService;
import com.oak.dms.common.exception.EmailInUseException;
import com.oak.dms.common.exception.KeycloakUserAlreadyExistsException;
import com.oak.dms.outbox.OutboxEventPublisher;
import com.oak.dms.security.auth.AuthorizationService;
import com.oak.dms.security.auth.PasswordResetService;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.security.keycloak.KeycloakUserService;
import com.oak.dms.tenant.TenantRepository;
import com.oak.dms.user.dto.UpdateUserRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Editing a user: name and email reach their Keycloak account; an email another account has is refused. */
class UserServiceImplUpdateTest {

    private static final UUID TENANT = UUID.randomUUID();

    private final UserRepository users = mock(UserRepository.class);
    private final KeycloakUserService keycloak = mock(KeycloakUserService.class);
    private final AuditService audit = mock(AuditService.class);
    private final UserServiceImpl service = new UserServiceImpl(users, mock(UserMapper.class), keycloak,
            mock(PasswordResetService.class), mock(OutboxEventPublisher.class), mock(AuthorizationService.class),
            mock(TenantRepository.class), audit, mock(com.oak.dms.signature.service.SigningKeyService.class));

    private User user;

    @BeforeEach
    void setUp() {
        TenantContext.set(TENANT, UUID.randomUUID());
        user = User.builder().userId(UUID.randomUUID()).tenantId(TENANT).keycloakId(UUID.randomUUID())
                .username("hj.local").email("old@example.com").firstName("Hansaka").lastName("S")
                .status(User.UserStatus.active).build();
        when(users.findById(user.getUserId())).thenReturn(Optional.of(user));
        when(users.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        when(audit.event(anyString())).thenReturn(mock(AuditEventBuilder.class, Answers.RETURNS_SELF));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private UpdateUserRequest request(String email) {
        return new UpdateUserRequest("Hansaka", "Sudusinghe", email, false, UpdateUserRequest.Status.active);
    }

    @Test
    void aNewEmail_isSaved_andWrittenToKeycloakWithTheName() {
        service.updateUser(user.getUserId(), request("  new@example.com "));

        assertThat(user.getEmail()).isEqualTo("new@example.com");
        assertThat(user.getLastName()).isEqualTo("Sudusinghe");
        verify(keycloak).updateProfile(user.getKeycloakId(), "Hansaka", "Sudusinghe", "new@example.com");
    }

    @Test
    void anEmailAnotherUserInTheTenantHas_isRefused_andNothingChanges() {
        when(users.existsByTenantIdAndEmailIgnoreCaseAndUserIdNot(TENANT, "taken@example.com", user.getUserId()))
                .thenReturn(true);

        assertThatThrownBy(() -> service.updateUser(user.getUserId(), request("taken@example.com")))
                .isInstanceOf(EmailInUseException.class).hasMessageContaining("already used by another account");
        assertThat(user.getEmail()).isEqualTo("old@example.com");
        verify(users, never()).saveAndFlush(any());
        verify(keycloak, never()).updateProfile(any(), any(), any(), any());
    }

    @Test
    void anEmailAnAccountInAnotherTenantHas_getsTheSameAnswer() {
        when(keycloak.emailTakenByAnotherAccount("elsewhere@example.com", user.getKeycloakId())).thenReturn(true);

        assertThatThrownBy(() -> service.updateUser(user.getUserId(), request("elsewhere@example.com")))
                .isInstanceOf(EmailInUseException.class).hasMessageContaining("already used by another account");
    }

    @Test
    void theSameOrNoEmail_leavesItAlone_butTheNameStillReachesKeycloak() {
        service.updateUser(user.getUserId(), request("old@example.com"));
        service.updateUser(user.getUserId(), request(null));

        assertThat(user.getEmail()).isEqualTo("old@example.com");
        verify(keycloak, never()).emailTakenByAnotherAccount(any(), any());
        verify(keycloak, org.mockito.Mockito.times(2))
                .updateProfile(eq(user.getKeycloakId()), eq("Hansaka"), eq("Sudusinghe"), isNull());
    }

    @Test
    void keycloakRefusingTheEmailAtTheLastMoment_isTheSameRefusal() {
        doThrow(new KeycloakUserAlreadyExistsException("race@example.com", null))
                .when(keycloak).updateProfile(any(), any(), any(), eq("race@example.com"));

        assertThatThrownBy(() -> service.updateUser(user.getUserId(), request("race@example.com")))
                .isInstanceOf(EmailInUseException.class);
    }
}
