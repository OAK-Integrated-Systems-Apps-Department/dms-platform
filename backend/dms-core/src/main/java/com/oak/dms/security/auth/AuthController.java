package com.oak.dms.security.auth;

import com.oak.dms.security.auth.dto.ForgotPasswordRequest;
import com.oak.dms.security.auth.dto.LoginRequest;
import com.oak.dms.security.auth.dto.MfaRequiredResponse;
import com.oak.dms.security.auth.dto.ResetPasswordRequest;
import com.oak.dms.security.auth.dto.ValidateResetTokenResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * Every endpoint here is intentionally public (see the permit-list in
 * {@link com.oak.dms.security.SecurityConfig}) - a caller can't be
 * authenticated yet when they're trying to authenticate. Each one is
 * therefore its own trust boundary and is treated accordingly: strict
 * input validation, IP-based rate limiting via {@link LoginAttemptLimiter},
 * generic error messages that never leak account existence, and
 * {@code Cache-Control: no-store} so nothing here is ever cached by a
 * shared proxy on the way back to the browser.
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Validated
@Tag(name = "Auth", description = "Session login/logout against Keycloak, cookie-based")
public class AuthController {

    private final AuthService authService;
    private final LoginAttemptLimiter rateLimiter;
    private final PasswordResetService passwordResetService;

    @GetMapping("/mfa-required")
    @Operation(summary = "Check whether a username requires a TOTP code, without revealing whether it exists")
    public ResponseEntity<MfaRequiredResponse> isMfaRequired(
            @RequestParam @NotBlank @Size(max = 255) String username,
            HttpServletRequest request) {
        rateLimiter.checkAndRecord(request);
        return noStore(new MfaRequiredResponse(authService.isMfaRequired(username)));
    }

    @PostMapping("/login")
    @Operation(summary = "Sign in with username/password (+ TOTP if enrolled); sets the session cookies")
    public ResponseEntity<Void> login(@Valid @RequestBody LoginRequest loginRequest,
                                       HttpServletRequest request,
                                       HttpServletResponse response) {
        rateLimiter.checkAndRecord(request);
        authService.login(loginRequest.username(), loginRequest.password(), loginRequest.totp(),
                loginRequest.rememberMe(), response);
        return noStoreVoid();
    }

    @PostMapping("/refresh")
    @Operation(summary = "Silently renew the session from the refresh-token cookie")
    public ResponseEntity<Void> refresh(HttpServletRequest request, HttpServletResponse response) {
        rateLimiter.checkAndRecord(request);
        authService.refresh(request, response);
        return noStoreVoid();
    }

    @PostMapping("/logout")
    @Operation(summary = "End the session: revoke the refresh token in Keycloak and clear the session cookies")
    public ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        authService.logout(request, response);
        return noStoreVoid();
    }

    /**
     * Always responds 200 with the same generic body whether or not
     * {@code usernameOrEmail} matches an account - see
     * {@link PasswordResetService#requestReset} for why. The frontend's
     * "Check your email" copy is written accordingly and doesn't need
     * anything from this response body.
     */
    @PostMapping("/forgot-password")
    @Operation(summary = "Request a password reset email; always responds the same way regardless of match")
    public ResponseEntity<Void> forgotPassword(@Valid @RequestBody ForgotPasswordRequest requestBody,
                                                HttpServletRequest request) {
        rateLimiter.checkAndRecord(request);
        passwordResetService.requestReset(requestBody.usernameOrEmail().trim(), request);
        return noStoreVoid();
    }

    @GetMapping("/reset-password/validate")
    @Operation(summary = "Check whether a password reset token is still valid, before rendering the set-new-password form")
    public ResponseEntity<ValidateResetTokenResponse> validateResetToken(
            @RequestParam @NotBlank @Size(max = 512) String token,
            HttpServletRequest request) {
        rateLimiter.checkAndRecord(request);
        var kind = passwordResetService.validateToken(token);
        return noStore(new ValidateResetTokenResponse(kind.isPresent(), kind.orElse(null)));
    }

    @PostMapping("/reset-password")
    @Operation(summary = "Redeem a password reset token and set a new password, entirely on dms-core's own domain")
    public ResponseEntity<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest requestBody,
                                               HttpServletRequest request) {
        rateLimiter.checkAndRecord(request);
        passwordResetService.resetPassword(requestBody.token(), requestBody.newPassword());
        return noStoreVoid();
    }

    private <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .body(body);
    }

    private ResponseEntity<Void> noStoreVoid() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .build();
    }
}
