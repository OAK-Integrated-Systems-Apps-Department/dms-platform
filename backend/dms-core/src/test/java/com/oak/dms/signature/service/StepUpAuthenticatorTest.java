package com.oak.dms.signature.service;

import com.oak.dms.security.auth.KeycloakAuthClient;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.security.auth.dto.KeycloakTokenResponse;
import com.oak.dms.signature.exception.SignatureException;
import com.oak.dms.user.User;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Decision 2: explicit consent is checked by the caller/DTO validation; this covers step-up itself and the impersonation refusal. */
@ExtendWith(MockitoExtension.class)
class StepUpAuthenticatorTest {

    @Mock
    private KeycloakAuthClient authClient;

    private StepUpAuthenticator authenticator;

    @BeforeEach
    void setUp() {
        authenticator = new StepUpAuthenticator(authClient);
        TenantContext.set(UUID.randomUUID(), UUID.randomUUID());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private User user(boolean mfaEnabled) {
        return User.builder().userId(UUID.randomUUID()).username("signer").mfaEnabled(mfaEnabled).build();
    }

    @Test
    void refusesWhileImpersonating() {
        TenantContext.setImpersonator(UUID.randomUUID(), "Real Admin");

        assertThatThrownBy(() -> authenticator.verify(user(false), "password", null))
                .isInstanceOf(SignatureException.class)
                .hasMessageContaining("impersonating");
        verify(authClient, never()).passwordGrant(anyString(), anyString(), anyString());
    }

    @Test
    void rejectsABlankPassword() {
        assertThatThrownBy(() -> authenticator.verify(user(false), "", null))
                .isInstanceOf(SignatureException.class)
                .hasMessageContaining("password");
    }

    @Test
    void requiresOtpWhenMfaIsEnabled() {
        assertThatThrownBy(() -> authenticator.verify(user(true), "password", null))
                .isInstanceOf(SignatureException.class)
                .hasMessageContaining("authenticator");
        assertThatThrownBy(() -> authenticator.verify(user(true), "password", "  "))
                .isInstanceOf(SignatureException.class)
                .hasMessageContaining("authenticator");
    }

    @Test
    void doesNotRequireOtpWhenMfaIsDisabled() {
        when(authClient.passwordGrant("signer", "password", null))
                .thenReturn(new KeycloakAuthClient.GrantResult.Success(
                        new KeycloakTokenResponse("access", 300, "refresh-token", 1800, "Bearer")));

        authenticator.verify(user(false), "password", null);

        verify(authClient).revokeRefreshToken("refresh-token");
    }

    @Test
    void wrongPasswordOrOtpThrowsStepUpFailed() {
        when(authClient.passwordGrant("signer", "wrong", null)).thenReturn(new KeycloakAuthClient.GrantResult.Failure(false, "invalid_grant"));

        assertThatThrownBy(() -> authenticator.verify(user(false), "wrong", null))
                .isInstanceOfSatisfying(SignatureException.class,
                        e -> org.assertj.core.api.Assertions.assertThat(e.getCode()).isEqualTo("SIGNATURE_STEP_UP_FAILED"));
    }

    @Test
    void revokesTheStepUpGrantsRefreshTokenOnSuccess() {
        when(authClient.passwordGrant("signer", "password", "123456"))
                .thenReturn(new KeycloakAuthClient.GrantResult.Success(
                        new KeycloakTokenResponse("access", 300, "refresh-token", 1800, "Bearer")));

        authenticator.verify(user(true), "password", "123456");

        verify(authClient).revokeRefreshToken("refresh-token");
    }
}
