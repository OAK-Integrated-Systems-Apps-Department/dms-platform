package com.oak.dms.security.auth;

import com.oak.dms.security.auth.dto.KeycloakTokenResponse;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * The only place in dms-core that constructs the session cookies. Cookie
 * <em>names</em> are hardcoded (not config-driven) because they must match
 * {@code lib/auth/cookie-names.ts} and {@code proxy.ts} on the frontend
 * exactly - changing them here without changing them there silently
 * breaks login.
 * <p>
 * Both cookies are httpOnly: neither is ever meant to be readable by
 * frontend JavaScript. The frontend's Next.js {@code proxy.ts} can still
 * read the access-token cookie to gate routes because it runs
 * server-side (Next middleware), not in the browser - httpOnly only
 * blocks {@code document.cookie}, not a server reading the cookie header
 * it was sent.
 */
@Component
@RequiredArgsConstructor
public class AuthCookieService {

    public static final String ACCESS_TOKEN_COOKIE = "dms_access_token";
    public static final String REFRESH_TOKEN_COOKIE = "dms_refresh_token";
    /** Carries the signed {@link ImpersonationTokenCodec} grant - see {@link ImpersonationContextFilter}. Independent of the two cookies above: a token refresh never touches this one, and this one never touches the real session's own expiry. */
    public static final String IMPERSONATION_COOKIE = "dms_impersonation";

    private final AuthCookieProperties properties;

    /**
     * Writes both session cookies onto the response.
     *
     * @param rememberMe if false, both cookies are issued as browser-session
     *                   cookies (no Max-Age) so they disappear when the
     *                   browser closes; if true, the refresh cookie persists
     *                   for its full Keycloak-issued lifetime so the user
     *                   stays signed in across restarts. The access-token
     *                   cookie's Max-Age always tracks its own short JWT
     *                   lifetime regardless of rememberMe - there's no
     *                   benefit to a short-lived, sensitive token outliving
     *                   its own validity, and a fresh one is reissued
     *                   transparently by /api/v1/auth/refresh.
     */
    public void writeSessionCookies(HttpServletResponse response, KeycloakTokenResponse tokens, boolean rememberMe) {
        addCookie(response, ACCESS_TOKEN_COOKIE, tokens.accessToken(), tokens.expiresInSeconds());
        addCookie(response, REFRESH_TOKEN_COOKIE, tokens.refreshToken(),
                rememberMe ? tokens.refreshExpiresInSeconds() : -1);
    }

    /** Overwrites both cookies with an immediately-expired, empty value so the browser deletes them. */
    public void clearSessionCookies(HttpServletResponse response) {
        addCookie(response, ACCESS_TOKEN_COOKIE, "", 0);
        addCookie(response, REFRESH_TOKEN_COOKIE, "", 0);
        // A logout must always end an active impersonation session too -
        // there is no scenario where staying "logged out but still
        // impersonating" makes sense. Harmless no-op if none was active.
        clearImpersonationCookie(response);
    }

    public void writeImpersonationCookie(HttpServletResponse response, String token, long maxAgeSeconds) {
        addCookie(response, IMPERSONATION_COOKIE, token, maxAgeSeconds);
    }

    public void clearImpersonationCookie(HttpServletResponse response) {
        addCookie(response, IMPERSONATION_COOKIE, "", 0);
    }

    private void addCookie(HttpServletResponse response, String name, String value, long maxAgeSeconds) {
        ResponseCookie.ResponseCookieBuilder builder = ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(properties.isSecure())
                .sameSite(properties.getSameSite())
                .path(properties.getPath());
        if (properties.getDomain() != null && !properties.getDomain().isBlank()) {
            builder.domain(properties.getDomain());
        }
        // maxAgeSeconds < 0 means "session cookie" (ResponseCookie's default
        // when Max-Age is simply never set) - only attach Max-Age when we
        // actually want the cookie to survive the browser closing.
        if (maxAgeSeconds >= 0) {
            builder.maxAge(maxAgeSeconds);
        }
        response.addHeader(HttpHeaders.SET_COOKIE, builder.build().toString());
    }
}
