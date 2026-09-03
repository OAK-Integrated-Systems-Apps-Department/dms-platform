package com.oak.dms.security.auth;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.stereotype.Component;

/**
 * Lets the exact same OAuth2-resource-server JWT validation ({@code
 * SecurityConfig}, {@code JwtAuthConverter}, {@code TenantContextFilter})
 * serve both kinds of caller dms-core has:
 * <ul>
 *   <li>the browser SPA, which never sees the raw token - it rides along
 *       in the httpOnly {@code dms_access_token} cookie set by
 *       {@link AuthCookieService};</li>
 *   <li>any other API client (service-to-service calls, Postman, a future
 *       mobile app) that sends a normal {@code Authorization: Bearer ...}
 *       header.</li>
 * </ul>
 * The header is always checked first and wins if present, so a caller
 * that explicitly supplies a token is never silently overridden by a
 * stale cookie. Everything downstream of this resolver is completely
 * unaware of which source the token came from - it's just a JWT string
 * either way.
 */
@Component
public class CookieBearerTokenResolver implements BearerTokenResolver {

    private final DefaultBearerTokenResolver headerResolver = new DefaultBearerTokenResolver();

    @Override
    public String resolve(HttpServletRequest request) {
        String fromHeader = headerResolver.resolve(request);
        if (fromHeader != null) {
            return fromHeader;
        }
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (AuthCookieService.ACCESS_TOKEN_COOKIE.equals(cookie.getName())) {
                String value = cookie.getValue();
                return (value == null || value.isBlank()) ? null : value;
            }
        }
        return null;
    }
}
