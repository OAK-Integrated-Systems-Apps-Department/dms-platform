package com.oak.dms.signature.service;

import com.oak.dms.security.auth.KeycloakAuthClient;
import com.oak.dms.security.auth.TenantContext;
import com.oak.dms.signature.exception.SignatureException;
import com.oak.dms.user.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Re-checks the signer's own password (and TOTP, when they have MFA
 * enabled) right before a signature is recorded - explicit proof of intent
 * at the moment of signing, not just a valid session cookie. Refuses
 * outright while impersonating (decision 2): a delegate signs in their own
 * name, but nobody signs "as" someone else.
 * <p>
 * Never logs the password or TOTP - see {@link KeycloakAuthClient}'s own
 * doc comment, which this reuses rather than re-implementing credential
 * verification. The refresh token the grant creates is revoked immediately
 * after use (best-effort - {@link KeycloakAuthClient#revokeRefreshToken}
 * never throws): it exists only to prove this moment, never to become a
 * second usable session.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StepUpAuthenticator {

    private final KeycloakAuthClient authClient;

    public void verify(User user, String password, String totp) {
        if (TenantContext.getImpersonatorIdOrNull() != null) {
            throw SignatureException.forbidden("SIGNATURE_IMPERSONATION_FORBIDDEN",
                    "Signing is not allowed while impersonating another user.");
        }
        if (password == null || password.isBlank()) {
            throw SignatureException.badRequest("SIGNATURE_PASSWORD_REQUIRED", "Re-enter your password to sign.");
        }
        if (user.isMfaEnabled() && (totp == null || totp.isBlank())) {
            throw SignatureException.badRequest("SIGNATURE_OTP_REQUIRED",
                    "Enter your authenticator app code to sign.");
        }

        KeycloakAuthClient.GrantResult result = authClient.passwordGrant(user.getUsername(), password, totp);
        if (result instanceof KeycloakAuthClient.GrantResult.Failure) {
            throw SignatureException.badRequest("SIGNATURE_STEP_UP_FAILED",
                    "Your password or authenticator app code is incorrect.");
        }
        KeycloakAuthClient.GrantResult.Success success = (KeycloakAuthClient.GrantResult.Success) result;
        String refreshToken = success.tokens().refreshToken();
        if (refreshToken != null && !refreshToken.isBlank()) {
            authClient.revokeRefreshToken(refreshToken);
        }
    }
}
